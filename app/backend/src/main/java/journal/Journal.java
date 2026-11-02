package journal;

import config.ExchangeConfig;
import io.aeron.Aeron;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.driver.exceptions.ActiveDriverException;
import org.agrona.CloseHelper;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.status.CountersReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * The event journal (SRS §3.9): an embedded Aeron media driver and Archive inside the
 * exchange process, and the recorded IPC streams. One continuous recording per stream (D10).
 *
 * Storage. The media driver directory ({@code AERON_DIR}) is working state: a stale one is
 * deleted when the driver starts, and it is deleted again on clean shutdown. The driver is
 * not told to delete unconditionally, because that would destroy a second process's live
 * driver on the same directory; instead the driver's own start-up check refuses to start
 * while another driver's heartbeat in that directory is fresh (ActiveDriverException), and
 * deletes the directory otherwise. The Archive directory ({@code EXCHANGE_DATA_DIR/archive})
 * holds the recordings and is never cleared; its mark file likewise refuses a second live
 * Archive on the same directory. Archive writes reach the page cache, not the disk
 * ({@code fileSyncLevel 0}), which is exactly the durability SRS §3.9 states.
 *
 * IPC only, no network channel and no UDP socket: the Archive's UDP control channel is
 * disabled (clients use its IPC local control channel), control responses are IPC, recording
 * events are disabled, and the replication channel, which Aeron requires to be set but reads
 * only on a replicate request this venue never issues, is IPC as well.
 *
 * Crash checks (P15-5). Every recorded frame carries a CRC32C (the Archive's record checksum),
 * so when the Archive repairs a recording left active by a crash it can tell a complete last
 * frame from a torn one, even when the frame straddles a page and its bytes past the boundary
 * are zeros. A genuinely torn frame (a machine crash) still stops startup, with exact
 * instructions ({@link #launch}). A restart within the driver's 10 s liveness window of a crash
 * is retried once ({@link #launchRetryingOnce}).
 *
 * Threading: driver and Archive each run in SHARED mode, one thread apiece. The publications
 * this class opens are each used by one writer thread; open and close are startup and shutdown
 * calls from one thread.
 */
public final class Journal implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Journal.class);

    /** Every journal stream is in-process IPC (SRS §3.9). */
    public static final String IPC_CHANNEL = "aeron:ipc";
    /** Sequenced commands: written by the sequencer, read by the engine agent. */
    public static final int INBOUND_STREAM_ID = 1001;
    /** Execution events: written by the outbound recorder (P15-6), read by recovery and RESEND. */
    public static final int OUTBOUND_STREAM_ID = 1002;
    /** Where startup replays are delivered. Never recorded: recordings subscribe to their own stream id only. */
    public static final int REPLAY_STREAM_ID = 1101;
    /** Where RESEND replays of the outbound recording are delivered (P15-6). Never recorded. */
    public static final int RESEND_STREAM_ID = 1102;

    /** Bound on every start-up and shutdown wait. */
    static final long AWAIT_TIMEOUT_MS = 10_000L;

    /**
     * Wait before the one retry of a launch refused by a fresh heartbeat: longer than the driver
     * timeout and the Archive mark file's liveness window (both 10 s), so a crashed run's
     * heartbeat has aged out and a live instance's has not.
     */
    public static final long CRASH_RETRY_DELAY_MS = 11_000L;

    private final ExchangeConfig config;
    private final ArchivingMediaDriver driver;
    private final Aeron aeron;
    private final AeronArchive archive;
    private final List<RecordedPublication> opened = new ArrayList<>();
    private final List<Subscription> subscriptions = new ArrayList<>();
    private boolean closed;

    private Journal(ExchangeConfig config, ArchivingMediaDriver driver, Aeron aeron, AeronArchive archive) {
        this.config = config;
        this.driver = driver;
        this.aeron = aeron;
        this.archive = archive;
    }

    /**
     * Starts the media driver and Archive and connects to them.
     *
     * @throws io.aeron.driver.exceptions.ActiveDriverException if a live driver owns {@code AERON_DIR}
     * @throws IllegalStateException if a live Archive owns the Archive directory
     */
    public static Journal launch(ExchangeConfig config) {
        ArchivingMediaDriver driver = null;
        Aeron aeron = null;
        AeronArchive archive = null;
        try {
            try {
                driver = ArchivingMediaDriver.launch(driverContext(config), archiveContext(config));
            } catch (ArchiveException e) {
                if (String.valueOf(e.getMessage()).contains("straddling page boundary")) {
                    throw new IllegalStateException(tornFrameInstructions(config, e.getMessage()), e);
                }
                throw e;
            }
            aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(config.aeronDir().toString()));
            archive = AeronArchive.connect(new AeronArchive.Context()
                    .aeron(aeron)
                    .controlRequestChannel(AeronArchive.Configuration.localControlChannel())
                    .controlRequestStreamId(AeronArchive.Configuration.localControlStreamId())
                    .controlResponseChannel(IPC_CHANNEL));
        } catch (RuntimeException e) {
            CloseHelper.quietCloseAll(archive, aeron, driver);
            throw e;
        }
        log.info("Journal started: archive={} aeron={} termLength={} segmentLength={}",
                config.archiveDir(), config.aeronDir(), config.journalTermLength(), config.journalSegmentLength());
        return new Journal(config, driver, aeron, archive);
    }

    /**
     * {@link #launch}, retried once after {@code retryDelayMs} if the first attempt is refused
     * because a driver or Archive heartbeat in this run's directories is still fresh (P15-3
     * finding). That happens for up to 10 s after a crash, and always while another instance is
     * running. The retry is safe in both cases: both checks run before anything is deleted or
     * the catalog is opened, so a live instance is never touched, and its heartbeat stays fresh,
     * so the retry is refused the same way and the caller fails. One retry, never a loop.
     */
    public static Journal launchRetryingOnce(ExchangeConfig config, long retryDelayMs) {
        try {
            return launch(config);
        } catch (RuntimeException e) {
            if (!isLiveHeartbeat(e)) throw e;
            log.warn("A media driver or Archive heartbeat in {} or {} is fresh: either the previous run crashed"
                    + " less than 10 s ago or another instance is running. Retrying once in {} ms.",
                    config.aeronDir(), config.archiveDir(), retryDelayMs);
        }
        long deadline = System.nanoTime() + retryDelayMs * 1_000_000L;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
        try {
            return launch(config);
        } catch (RuntimeException e) {
            if (isLiveHeartbeat(e)) {
                throw new IllegalStateException("Another exchange instance is running on " + config.aeronDir()
                        + " or " + config.archiveDir() + ": its heartbeat was still fresh after " + retryDelayMs
                        + " ms. Stop it first, or give this instance its own EXCHANGE_DATA_DIR and AERON_DIR.", e);
            }
            throw e;
        }
    }

    /** A refusal because another driver or Archive owns the directory, judged by its heartbeat. */
    static boolean isLiveHeartbeat(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ActiveDriverException) return true;
            if (t instanceof IllegalStateException && String.valueOf(t.getMessage()).contains("active mark file detected")) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the operator does when the Archive cannot verify a recording's last frame: once frames
     * carry a checksum, only a frame torn by a machine crash gets here. Repair is not automatic,
     * because truncating a frame drops a command the engine may already have matched and
     * reported; the operator decides, with the tool and paths spelled out.
     */
    static String tornFrameInstructions(ExchangeConfig config, String archiveMessage) {
        Path archiveDir = config.archiveDir();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        String nl = System.lineSeparator();
        return "The journal's last recorded frame may be incomplete (a crash cut it mid-write), so the Archive"
                + " refuses to open the recording. Aeron said: " + String.valueOf(archiveMessage).replace('\n', ' ')
                + nl + "  1. Stop. Back up " + archiveDir + " (copy the whole directory)."
                + nl + "  2. Run: \"" + java + "\" --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -cp \""
                + classpath + "\" io.aeron.archive.ArchiveTool \"" + archiveDir + "\" verify"
                + nl + "  3. When asked about the straddling fragment, answer y to truncate that one frame. It is the"
                + " last command received before the crash; clients may have seen executions for it."
                + nl + "  4. Restart. If a snapshot is then beyond the journal, startup says which snapshots to move aside.";
    }

    private static MediaDriver.Context driverContext(ExchangeConfig config) {
        return new MediaDriver.Context()
                .aeronDirectoryName(config.aeronDir().toString())
                .dirDeleteOnStart(false)      // the driver deletes a stale dir itself and refuses a live one
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED)
                .ipcTermBufferLength(config.journalTermLength());
    }

    private static Archive.Context archiveContext(ExchangeConfig config) {
        return new Archive.Context()
                .archiveDir(config.archiveDir().toFile())
                .deleteArchiveOnStart(false)
                .segmentFileLength(config.journalSegmentLength())
                .fileSyncLevel(0)
                .recordChecksum(Crc32cChecksum.INSTANCE)   // lets crash repair verify a page-straddling last frame
                .threadingMode(ArchiveThreadingMode.SHARED)
                .controlChannelEnabled(false)
                .recordingEventsEnabled(false)
                .replicationChannel(IPC_CHANNEL)
                .archiveClientContext(new AeronArchive.Context().controlResponseChannel(IPC_CHANNEL));
    }

    /** The configuration this journal was launched with. */
    public ExchangeConfig config() {
        return config;
    }

    /** True once {@link #close()} has run. */
    public boolean isClosed() {
        return closed;
    }

    /** The connected Archive client, for replays. */
    public AeronArchive archive() {
        return archive;
    }

    /** Every recording of {@code streamId} on the IPC channel. D10 keeps this to at most one. */
    public List<RecordingDescriptor> findRecordings(int streamId) {
        List<RecordingDescriptor> found = new ArrayList<>();
        archive.listRecordingsForUri(0, Integer.MAX_VALUE, IPC_CHANNEL, streamId,
                (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sessionId, recordedStreamId, strippedChannel, originalChannel, sourceIdentity) -> {
                    if (recordedStreamId == streamId) {
                        found.add(new RecordingDescriptor(recordingId, startPosition, stopPosition,
                                initialTermId, segmentFileLength, termBufferLength, mtuLength, recordedStreamId));
                    }
                });
        return found;
    }

    /** The one recording of {@code streamId}, or null if the stream has never been recorded. */
    public RecordingDescriptor findRecording(int streamId) {
        List<RecordingDescriptor> found = findRecordings(streamId);
        if (found.size() > 1) {
            throw new IllegalStateException("Stream " + streamId + " has " + found.size()
                    + " recordings; the journal must be one continuous recording per stream (D10)");
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Opens the single writer's publication for {@code streamId}, recorded before it is returned.
     *
     * On first run the Archive starts a recording of the stream; on every later run it extends
     * the existing one, the publication starting exactly at the recording's stop position with
     * the recording's own initial term id, term length and MTU (D10). The call returns only once
     * the Archive's recording counter for this publication's session exists (and, when extending,
     * names the existing recording) and the publication is connected, so nothing offered to it
     * can go unrecorded.
     */
    public RecordedPublication openRecordedPublication(int streamId) {
        if (closed) throw new IllegalStateException("journal closed");
        RecordingDescriptor existing = findRecording(streamId);

        final String channel;
        final long subscriptionId;
        final int termLength;
        if (existing == null) {
            termLength = config.journalTermLength();
            channel = new ChannelUriStringBuilder().media("ipc").termLength(termLength).build();
            subscriptionId = archive.startRecording(IPC_CHANNEL, streamId, SourceLocation.LOCAL);
        } else {
            if (existing.stopPosition() == AeronArchive.NULL_POSITION) {
                throw new IllegalStateException("Recording " + existing.recordingId()
                        + " of stream " + streamId + " is still active; it cannot be extended");
            }
            termLength = existing.termBufferLength();
            if (termLength != config.journalTermLength()) {
                log.warn("Stream {} keeps its recording's term length {}; configured {} applies to new recordings only",
                        streamId, termLength, config.journalTermLength());
            }
            channel = new ChannelUriStringBuilder()
                    .media("ipc")
                    .initialPosition(existing.stopPosition(), existing.initialTermId(), termLength)
                    .mtu(existing.mtuLength())
                    .build();
            subscriptionId = archive.extendRecording(existing.recordingId(), IPC_CHANNEL, streamId, SourceLocation.LOCAL);
        }

        ExclusivePublication publication = null;
        try {
            publication = aeron.addExclusivePublication(channel, streamId);
            long recordingId = awaitRecording(publication);
            if (existing != null && recordingId != existing.recordingId()) {
                throw new IllegalStateException("Stream " + streamId + " started recording " + recordingId
                        + " instead of extending " + existing.recordingId());
            }
            RecordedPublication recorded = new RecordedPublication(
                    recordingId,
                    publication,
                    subscriptionId,
                    existing == null ? publication.position() : existing.startPosition(),
                    publication.position(),
                    termLength,
                    existing != null);
            opened.add(recorded);
            log.info("Stream {} recording {} {} at position {}", streamId, recordingId,
                    existing == null ? "started" : "extended", recorded.joinPosition());
            return recorded;
        } catch (RuntimeException e) {
            CloseHelper.quietClose(publication);
            archive.tryStopRecording(subscriptionId);
            throw e;
        }
    }

    /**
     * Subscribes to {@code recorded}'s stream and returns the image of its publication session,
     * waiting (bounded) until it is connected. An IPC subscriber joins at the publication's
     * current position and sees nothing offered before it, so the engine agent's image is taken
     * here, before the sequencer offers anything; the image's join position proves it.
     * The subscription is closed by {@link #close()}.
     */
    public Image subscribe(RecordedPublication recorded) {
        if (closed) throw new IllegalStateException("journal closed");
        ExclusivePublication publication = recorded.publication();
        Subscription subscription = aeron.addSubscription(IPC_CHANNEL, publication.streamId());
        subscriptions.add(subscription);
        IdleStrategy idle = new SleepingMillisIdleStrategy(1);
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        Image image;
        while ((image = subscription.imageBySessionId(publication.sessionId())) == null) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("No image of stream " + publication.streamId()
                        + " session " + publication.sessionId() + " within " + AWAIT_TIMEOUT_MS + " ms");
            }
            idle.idle();
        }
        return image;
    }

    /**
     * The D11 gate for {@code recorded}: the Archive's recording position counter of its
     * recording, which only advances once bytes are written to the recording file.
     */
    public RecordingPositionGate recordingPositionGate(RecordedPublication recorded) {
        CountersReader counters = aeron.countersReader();
        int counterId = RecordingPos.findCounterIdByRecording(counters, recorded.recordingId(), archive.archiveId());
        if (counterId == CountersReader.NULL_COUNTER_ID) {
            throw new IllegalStateException("No recording position counter for recording " + recorded.recordingId());
        }
        return new RecordingPositionGate(counters, counterId, recorded.recordingId());
    }

    /** Waits for the Archive's recording counter for this publication's session. */
    private long awaitRecording(ExclusivePublication publication) {
        CountersReader counters = aeron.countersReader();
        IdleStrategy idle = new SleepingMillisIdleStrategy(1);
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        int counterId;
        while ((counterId = RecordingPos.findCounterIdBySession(
                counters, publication.sessionId(), archive.archiveId())) == CountersReader.NULL_COUNTER_ID
                || !publication.isConnected()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Archive did not start recording stream "
                        + publication.streamId() + " within " + AWAIT_TIMEOUT_MS + " ms");
            }
            idle.idle();
        }
        return RecordingPos.getRecordingId(counters, counterId);
    }

    /**
     * Stops every recording cleanly and shuts down the Archive and driver. Call after every
     * writer has stopped offering and every reader has stopped polling. Subscriptions are closed
     * first; then for each stream it waits, bounded, until the recording has reached the
     * publication's position, so everything offered is on file.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Subscription subscription : subscriptions) {
            CloseHelper.quietClose(subscription);
        }
        subscriptions.clear();
        RuntimeException failure = null;
        for (RecordedPublication recorded : opened) {
            try {
                awaitRecorded(recorded);
                archive.stopRecording(recorded.recordingSubscriptionId());
            } catch (RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            CloseHelper.quietClose(recorded.publication());
        }
        opened.clear();
        CloseHelper.quietCloseAll(archive, aeron, driver);
        if (failure != null) throw failure;
    }

    private void awaitRecorded(RecordedPublication recorded) {
        long target = recorded.publication().position();
        IdleStrategy idle = new SleepingMillisIdleStrategy(1);
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (archive.getRecordingPosition(recorded.recordingId()) < target) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Recording " + recorded.recordingId()
                        + " did not reach position " + target + " within " + AWAIT_TIMEOUT_MS + " ms");
            }
            idle.idle();
        }
    }
}
