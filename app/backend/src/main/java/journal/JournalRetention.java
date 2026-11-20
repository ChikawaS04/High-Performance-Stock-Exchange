package journal;

import config.ExchangeConfig;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import recovery.RestoredSnapshot;
import recovery.SnapshotStore;
import util.JournalStatus;
import util.ReporterStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Journal retention (P16-7, D14): moves each recording's start forward with the Archive's segment
 * purge, so the journal stays one continuous recording per stream (P15 D10) and the disk does not
 * fill. {@link RetentionPlan} decides where each start may go; this class gathers what it needs,
 * carries the decision out and reports it.
 *
 * <b>Thread (R6).</b> One daemon thread, {@value #THREAD_NAME}, never the engine thread or an event
 * loop. For each run it connects its own Archive client on the journal's Aeron client (the journal's
 * client is not shared across threads for this) and closes it at the end, so no keep-alive is needed.
 * It is never interrupted; {@link #close()} sets a flag, unparks and joins, bounded.
 *
 * <b>Schedule.</b> The first periodic run {@link RetentionTiming#firstRunDelayMs()} after start, then
 * one each {@link RetentionTiming#intervalMs()} after the last completed periodic run (an hour in
 * production); an extra run after each session close, when the snapshot writer has written the close
 * snapshot ({@link #requestRun()}); and a retry {@link RetentionTiming#retryAfterRefusalMs()} after
 * the Archive refused a purge because a replay was reading below the new start.
 *
 * <b>A run.</b> Nothing is purged on doubt (R7): the venue must be running; with {@code DB_URL} set,
 * the Reporter must be RUNNING with a committed checkpoint; each stream must have exactly its one
 * recording; the snapshots must be readable; the outbound recording must have caught up with the
 * recorder (so rule 3 has a bound). Leftover segment files below a start (a purge cut short by a
 * crash or a shutdown) are deleted first. Then each recording whose start moves is purged. The
 * Archive itself refuses to purge below an active replay, so a RESEND or the Reporter is never read
 * from under; such a refusal is a skip, retried soon.
 *
 * <b>Status (R8).</b> One immutable {@link JournalStatus}, replaced on this thread after each run
 * and every {@link RetentionTiming#refreshMs()}: start and recorded positions, bytes on disk (a file
 * listing, never on an event loop), the last run's time and outcome.
 */
public final class JournalRetention implements AutoCloseable {

    public static final String THREAD_NAME = "journal-retention";

    private static final Logger log = LoggerFactory.getLogger(JournalRetention.class);

    private final Journal journal;
    private final Path archiveDir;
    private final RecordedPublication inbound;
    private final RecordedPublication outbound;
    private final RecordingPositionGate inboundGate;
    private final RecordingPositionGate outboundGate;
    private final SnapshotStore store;
    private final boolean enabled;
    private final int days;
    private final boolean reporterRequired;
    private final Supplier<ReporterStatus> reporter;
    private final BooleanSupplier venueRunning;
    private final LongSupplier recorderSeq;
    private final RetentionTiming timing;
    private final RetentionSkipLog skipLog = new RetentionSkipLog();
    private final Thread thread;

    private volatile JournalStatus status;
    private volatile boolean stopping;
    private volatile boolean runRequested;
    private volatile long completedRuns;

    // Retention thread only.
    private long inboundStart;
    private long outboundStart;
    private int closesSeen;
    private long lastRunTs = -1L;
    private String outcome = JournalStatus.NOT_RUN;
    private String skipReason;
    private boolean started;

    /**
     * @param reporter     the Reporter's status, or null while none is attached
     * @param venueRunning whether the venue is running (no fail-stop)
     * @param recorderSeq  the outbound recorder's last offered seq
     */
    JournalRetention(Journal journal, RecordedPublication inbound, RecordedPublication outbound,
                     RecordingPositionGate inboundGate, RecordingPositionGate outboundGate, SnapshotStore store,
                     Supplier<ReporterStatus> reporter, BooleanSupplier venueRunning, LongSupplier recorderSeq,
                     RetentionTiming timing) {
        this.journal = journal;
        this.archiveDir = journal.config().archiveDir();
        this.inbound = inbound;
        this.outbound = outbound;
        this.inboundGate = inboundGate;
        this.outboundGate = outboundGate;
        this.store = store;
        this.enabled = journal.config().journalRetentionEnabled();
        this.days = journal.config().journalRetentionDays();
        this.reporterRequired = journal.config().dbUrl() != null;
        this.reporter = reporter;
        this.venueRunning = venueRunning;
        this.recorderSeq = recorderSeq;
        this.timing = timing;
        this.inboundStart = inbound.startPosition();
        this.outboundStart = outbound.startPosition();
        if (!enabled) outcome = JournalStatus.DISABLED;
        this.status = buildStatus();
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
        if (days == 0 && reporterRequired) {
            log.warn("{}=0 with {} set: the journal keeps only the current day, so a nightly database backup"
                    + " restored later cannot resume (its checkpoint falls before the journal's start)",
                    ExchangeConfig.JOURNAL_RETENTION_DAYS, ExchangeConfig.DB_URL);
        }
    }

    void start() {
        started = true;
        thread.start();
    }

    /** Asks for a run as soon as the thread is free. Any thread; never blocks (the close-snapshot hook). */
    public void requestRun() {
        runRequested = true;
        LockSupport.unpark(thread);
    }

    /** The journal object {@code /health} reports. Any thread: one volatile read. */
    public JournalStatus status() {
        return status;
    }

    /** Runs completed so far, skipped ones included. Any thread. */
    public long completedRuns() {
        return completedRuns;
    }

    /**
     * Stops the thread, bounded. Call before the journal closes.
     *
     * @return false if it did not stop in time: it may still read the journal's mapped counters, so the
     *         journal must not be closed
     */
    @Override
    public void close() {
        stop();
    }

    boolean stop() {
        stopping = true;
        if (!started) return true;
        LockSupport.unpark(thread);
        try {
            thread.join(timing.stopTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.warn("Journal retention did not stop within {} ms", timing.stopTimeoutMs());
            return false;
        }
        return true;
    }

    private void run() {
        long now = System.currentTimeMillis();
        long nextPeriodic = saturatedAdd(now, timing.firstRunDelayMs());
        long nextRefresh = saturatedAdd(now, timing.refreshMs());
        long retryAt = Long.MAX_VALUE;
        while (!stopping) {
            now = System.currentTimeMillis();
            boolean periodic = now >= nextPeriodic;
            boolean retry = now >= retryAt;
            if (runRequested || periodic || retry) {
                runRequested = false;
                RetentionPlan.Skip skipped = runSafely();
                completedRuns++;
                now = System.currentTimeMillis();
                if (periodic) nextPeriodic = saturatedAdd(now, timing.intervalMs());
                retryAt = skipped == RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY
                        ? saturatedAdd(now, timing.retryAfterRefusalMs()) : Long.MAX_VALUE;
                nextRefresh = saturatedAdd(now, timing.refreshMs());
                continue;
            }
            if (now >= nextRefresh) {
                status = buildStatus();
                nextRefresh = saturatedAdd(now, timing.refreshMs());
            }
            long wake = Math.min(Math.min(nextPeriodic, retryAt), nextRefresh);
            long waitMs = Math.max(1L, Math.min(wake - now, 1_000L));
            LockSupport.parkNanos(this, waitMs * 1_000_000L);
        }
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return (sum < a) ? Long.MAX_VALUE : sum;
    }

    /** One run; never throws. Returns the skip reason, or null. */
    private RetentionPlan.Skip runSafely() {
        RetentionPlan.Skip skip;
        String detail;
        try {
            Result result = runOnce();
            skip = result.skip;
            detail = result.detail;
        } catch (RuntimeException e) {
            skip = RetentionPlan.Skip.UNEXPECTED;
            detail = e.toString();
            log.error("Journal retention run failed; nothing more is purged until the next run", e);
        }
        lastRunTs = System.currentTimeMillis() * 1_000_000L;
        if (skip != null) {
            outcome = JournalStatus.SKIPPED;
            skipReason = skip + ": " + detail;
            RetentionSkipLog.Line line = skipLog.onSkip(skip, detail, System.currentTimeMillis());
            if (line != null) {
                if (line.level() == RetentionSkipLog.Level.WARN) log.warn(line.text()); else log.info(line.text());
            }
        } else {
            skipReason = null;
            RetentionSkipLog.Line line = skipLog.onRun();
            if (line != null) log.info(line.text());
        }
        status = buildStatus();
        return skip;
    }

    private record Result(RetentionPlan.Skip skip, String detail) {
    }

    private Result skip(RetentionPlan.Skip skip, String detail) {
        return new Result(skip, detail);
    }

    private Result runOnce() {
        if (!enabled) {
            outcome = JournalStatus.DISABLED;
            return new Result(null, "disabled");
        }
        if (!venueRunning.getAsBoolean()) {
            return skip(RetentionPlan.Skip.VENUE_NOT_RUNNING, "recovery not complete or a fail-stop was reported");
        }
        long reporterIn = -1L;
        long reporterOut = -1L;
        if (reporterRequired) {
            ReporterStatus r = reporter.get();
            if (r == null) return skip(RetentionPlan.Skip.REPORTER_NOT_RUNNING, "no Reporter attached yet");
            if (!"RUNNING".equals(r.state())) return skip(RetentionPlan.Skip.REPORTER_NOT_RUNNING, r.state());
            reporterIn = r.checkpointInboundPosition();
            reporterOut = r.checkpointOutboundPosition();
            if (!"RUNNING".equals(r.state())) return skip(RetentionPlan.Skip.REPORTER_NOT_RUNNING, r.state());
            if (reporterIn < 0 || reporterOut < 0) {
                return skip(RetentionPlan.Skip.REPORTER_NOT_RUNNING, "RUNNING with no committed checkpoint yet");
            }
        }

        try (AeronArchive client = journal.connectArchiveClient()) {
            RecordingDescriptor in = describe(client, Journal.INBOUND_STREAM_ID, inbound.recordingId());
            RecordingDescriptor out = describe(client, Journal.OUTBOUND_STREAM_ID, outbound.recordingId());
            if (in == null || out == null) {
                return skip(RetentionPlan.Skip.DESCRIPTOR_UNAVAILABLE, "a recording is missing, duplicated or replaced");
            }
            inboundStart = in.startPosition();
            outboundStart = out.startPosition();
            deleteLeftovers(client, in);
            deleteLeftovers(client, out);

            List<RestoredSnapshot> snapshots;
            try {
                snapshots = store.listValid();
            } catch (IOException e) {
                return skip(RetentionPlan.Skip.SNAPSHOTS_UNREADABLE, e.toString());
            }

            long settledSeq = settledOutboundSeq();
            if (settledSeq < 0) {
                return skip(RetentionPlan.Skip.OUTBOUND_NOT_SETTLED, "the outbound recording did not catch up with the"
                        + " recorder within " + timing.settleTimeoutMs() + " ms");
            }
            RetentionPlan.Stream inStream = new RetentionPlan.Stream(in.recordingId(), in.startPosition(),
                    inboundGate.limit(), in.termBufferLength(), in.segmentFileLength());
            RetentionPlan.Stream outStream = new RetentionPlan.Stream(out.recordingId(), out.startPosition(),
                    outboundGate.limit(), out.termBufferLength(), out.segmentFileLength());
            RetentionPlan.Decision decision = RetentionPlan.decide(days, inStream, outStream, snapshots, settledSeq,
                    reporterRequired, reporterIn, reporterOut);
            closesSeen = decision.closesSeen();
            if (decision.skip() != null) return skip(decision.skip(), decision.detail());

            boolean purged = false;
            Result refused = purge(client, "inbound", in, decision.inboundNewStart());
            if (refused == null) {
                purged |= decision.inboundNewStart() > in.startPosition();
                refused = purge(client, "outbound", out, decision.outboundNewStart());
                if (refused == null) purged |= decision.outboundNewStart() > out.startPosition();
            }
            if (refused != null) return refused;
            outcome = purged ? JournalStatus.PURGED : JournalStatus.NOTHING_TO_PURGE;
            if (!purged) {
                log.info("Journal retention: nothing to purge ({})", decision.detail());
            }
            return new Result(null, decision.detail());
        }
    }

    /** The one recording of {@code streamId}, if it is still {@code expectedId}; null otherwise. */
    private static RecordingDescriptor describe(AeronArchive client, int streamId, long expectedId) {
        List<RecordingDescriptor> found = Journal.findRecordings(client, streamId);
        if (found.size() != 1 || found.get(0).recordingId() != expectedId) return null;
        return found.get(0);
    }

    /** Segment files below the start: a purge whose deletes were cut short. */
    private void deleteLeftovers(AeronArchive client, RecordingDescriptor recording) {
        SortedMap<Long, Long> files = Journal.segmentFiles(archiveDir, recording.recordingId());
        if (files.isEmpty() || files.firstKey() >= recording.startPosition()) return;
        long count = client.deleteDetachedSegments(recording.recordingId());
        log.info("Journal retention: deleting {} leftover segment file(s) of recording {} below its start {}",
                count, recording.recordingId(), recording.startPosition());
    }

    /**
     * An outbound seq known to be in the outbound recording: the recorder's seq, once the Archive's
     * recorded position has reached the publication position read after it. -1 if it did not in time.
     */
    private long settledOutboundSeq() {
        long seq = recorderSeq.getAsLong();
        long publication = outbound.publication().position();
        long deadline = System.currentTimeMillis() + timing.settleTimeoutMs();
        while (outboundGate.limit() < publication) {
            if (System.currentTimeMillis() > deadline || stopping) return -1L;
            LockSupport.parkNanos(1_000_000L);
        }
        return seq;
    }

    /** Purges {@code recording} up to {@code newStart} if that moves it; returns a skip if the Archive refuses. */
    private Result purge(AeronArchive client, String stream, RecordingDescriptor recording, long newStart) {
        if (newStart <= recording.startPosition()) return null;
        try {
            long files = client.purgeSegments(recording.recordingId(), newStart);
            if (recording.streamId() == Journal.INBOUND_STREAM_ID) inboundStart = newStart; else outboundStart = newStart;
            log.info("Journal retention purged the {} recording {}: start {} -> {}, {} segment file(s)", stream,
                    recording.recordingId(), recording.startPosition(), newStart, files);
            return null;
        } catch (ArchiveException e) {
            String message = String.valueOf(e.getMessage());
            RetentionPlan.Skip skip = message.contains("replay in progress")
                    ? RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY : RetentionPlan.Skip.ARCHIVE_REFUSED;
            return skip(skip, "the Archive refused to purge the " + stream + " recording to " + newStart + ": "
                    + message.replace('\n', ' '));
        }
    }

    private JournalStatus buildStatus() {
        return new JournalStatus(
                recording(inbound.recordingId(), inboundStart, inboundGate),
                recording(outbound.recordingId(), outboundStart, outboundGate),
                enabled, days, closesSeen, lastRunTs, outcome, skipReason, System.currentTimeMillis() * 1_000_000L);
    }

    private JournalStatus.Recording recording(long recordingId, long start, RecordingPositionGate gate) {
        long bytes = 0;
        for (long size : Journal.segmentFiles(archiveDir, recordingId).values()) bytes += size;
        long recorded;
        try {
            recorded = gate.limit();
        } catch (RuntimeException e) {
            recorded = -1L;
        }
        return new JournalStatus.Recording(recordingId, start, recorded, bytes);
    }
}
