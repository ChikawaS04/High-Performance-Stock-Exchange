package reporter;

import codec.ExecutionEventCodec;
import codec.OrderEventCodec;
import config.ExchangeConfig;
import db.Database;
import db.Migrator;
import event.ExecutionEvent;
import event.OrderEvent;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import journal.Journal;
import journal.JournaledPipeline;
import journal.RecordedPublication;
import journal.RecordingDescriptor;
import journal.RecordingPositionGate;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.ReporterStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.locks.LockSupport;

/**
 * Moves the journal into PostgreSQL (SRS 3.11): one thread named {@value #THREAD_NAME}, reading both
 * recordings through Archive replay and writing orders, executions, the audit trail and candles, with
 * the checkpoint in the same transaction. It never reads a ring and never touches the engine or the
 * gateway, so a slow or absent database delays reporting, never matching (SRS 5.9).
 *
 * <b>Merge.</b> The outbound stream drives. An event names the inbound seq of the command that caused
 * it, and D11 guarantees that command is already recorded, so the Reporter reads commands up to that
 * seq, then applies the event. A batch therefore only ever references commands committed earlier or in
 * the same transaction, and the deferred foreign keys cannot fail at commit unless something is wrong.
 * A batch never ends between a command and its event: only a NEW can have more events after a batch
 * boundary, and its row is what a resume reloads.
 *
 * <b>Resume.</b> The checkpoint holds both positions, so a resume costs nothing per journal length.
 * The rest of the state comes from rows the same transactions wrote. The checkpoint also names its
 * journal; a mismatch, a position beyond the recording, or a first seq that does not follow refuses.
 *
 * <b>Failure.</b> There is one path back: close the connection (rolling back the batch) and the replays,
 * drop all state, wait with back-off, reload from the checkpoint. So catching up after an outage, a stall
 * and a resume after a crash are one code path. A failure that retrying cannot fix (SQLState class 22 or
 * 23, or any broken invariant) stops the Reporter with one ERROR and leaves the venue trading; nothing is
 * ever skipped.
 *
 * <b>Stopping.</b> The thread checks the stop flag before every poll and never polls inside JDBC, so one
 * still alive after {@link #stop()}'s bound is in JDBC and will not touch a replay's mapped buffers again:
 * closing the journal is safe. It is a daemon thread. It is never interrupted, because the Archive client
 * it shares with the journal treats an interrupt as an error.
 */
public final class Reporter implements ReporterStatus, AutoCloseable {

    /** Where connections come from; a seam for tests of a database that comes and goes. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection connect() throws SQLException;
    }

    public static final String THREAD_NAME = "reporter";

    private static final Logger log = LoggerFactory.getLogger(Reporter.class);

    private static final int FRAGMENT_LIMIT = 64;
    private static final long IDLE_SLICE_NANOS = 1_000_000L;
    private static final long BACKOFF_SLICE_MS = 50L;

    private final ConnectionSource connections;
    private final Runnable schemaStep;
    private final Journal journal;
    private final RecordedPublication inbound;
    private final RecordedPublication outbound;
    private final ReporterTiming timing;
    private final Runnable beforeCommit;
    private final Thread thread;

    private final OrderEventCodec commandCodec = new OrderEventCodec();
    private final ExecutionEventCodec eventCodec = new ExecutionEventCodec();
    private final OrderEvent command = new OrderEvent();
    private final ExecutionEvent event = new ExecutionEvent();
    private final ReporterBatch batch = new ReporterBatch();

    private volatile boolean stopping;
    private volatile String state = "STARTING";
    private volatile long checkpointInboundSeq;
    private volatile long checkpointOutboundSeq;
    private volatile String failure;
    /** Told after a commit that recorded a closed date for clearing (P15-11); null when nothing listens. */
    private volatile Runnable onRunPending;

    // Reporter thread only.
    private boolean schemaReady;
    private boolean started;
    private int failuresInARow;
    private long lastErrorNanos;
    private long suppressedErrors;

    /**
     * @param connections  the database, with autocommit set here
     * @param schemaStep   brings the schema up to date; run until it has succeeded once, since a database
     *                     that was down at startup could not take it then
     * @param beforeCommit test seam, null in production: runs between a batch's writes and its commit
     */
    public Reporter(ConnectionSource connections, Runnable schemaStep, Journal journal, RecordedPublication inbound,
                    RecordedPublication outbound, ReporterTiming timing, Runnable beforeCommit) {
        this.connections = connections;
        this.schemaStep = schemaStep;
        this.journal = journal;
        this.inbound = inbound;
        this.outbound = outbound;
        this.timing = timing;
        this.beforeCommit = beforeCommit;
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
    }

    /** The Reporter of a venue whose {@code DB_URL} is set: Main's call. */
    public static Reporter forVenue(ExchangeConfig config, JournaledPipeline pipeline) {
        Database database = Database.from(config);
        Migrator migrator = new Migrator(database, config.dbMigrateOnStart());
        return new Reporter(database::connect, migrator::run, pipeline.journal(), pipeline.inboundRecording(),
                pipeline.outboundRecording(), ReporterTiming.PRODUCTION, null);
    }

    /**
     * Runs {@code listener} on the Reporter thread after each commit that recorded a pending clearing run,
     * and each time the Reporter enters RUNNING, so clearing sees at once that it is no longer held
     * (P15-12; before, {@code /health} could show "reporter STARTING" for up to one clearing poll).
     * It must not block: clearing's is an unpark. Set before {@link #start()}.
     */
    public void onRunPending(Runnable listener) {
        this.onRunPending = listener;
    }

    public void start() {
        started = true;
        thread.start();
    }

    /**
     * Stops, bounded. Events after the last commit are written on the next start. Call before the journal
     * closes.
     *
     * @return whether the thread finished in time; if not it is inside JDBC, and will not poll again
     */
    public boolean stop() {
        stopping = true;
        if (!started) {
            return true;
        }
        try {
            thread.join(timing.stopTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean stopped = !thread.isAlive();
        if (!stopped) {
            log.warn("The Reporter did not stop within {} ms; it is waiting on the database and will not read the"
                    + " journal again", timing.stopTimeoutMs());
        }
        return stopped;
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public String state() {
        return state;
    }

    @Override
    public long inboundSeq() {
        return checkpointInboundSeq;
    }

    @Override
    public long outboundSeq() {
        return checkpointOutboundSeq;
    }

    /** Why the Reporter stopped itself, or null. */
    public String failure() {
        return failure;
    }

    private void run() {
        while (!stopping) {
            try (Connection connection = connections.connect()) {
                connection.setAutoCommit(false);
                if (!schemaReady) {
                    schemaStep.run();
                    schemaReady = true;
                }
                follow(connection);
            } catch (ReporterInconsistencyException e) {
                fail(e.getMessage(), e);
                return;
            } catch (Exception e) {
                if (ReporterStore.isDataFailure(e)) {
                    fail("The database refused a row the journal requires: " + e.getMessage() + " (SQLState "
                            + sqlState(e) + "). Nothing was skipped; the journal and this database disagree.", e);
                    return;
                }
                if (!stopping) {
                    backOff(e);
                }
            } catch (Error e) {
                fail("The Reporter failed: " + e, e);
                return;
            }
        }
        state = "STOPPED";
    }

    private void fail(String message, Throwable cause) {
        failure = message;
        state = "FAILED";
        log.error("The Reporter stopped at checkpoint inbound seq {}, outbound seq {}; matching is unaffected: {}",
                checkpointInboundSeq, checkpointOutboundSeq, message, cause);
    }

    /** One pass with a connection: reload from the checkpoint, then run until something fails or stop. */
    private void follow(Connection connection) throws SQLException {
        RecordingPositionGate inboundGate = journal.recordingPositionGate(inbound);
        RecordingPositionGate outboundGate = journal.recordingPositionGate(outbound);
        ReporterStore.JournalIdentity identity = journalIdentity();

        ReporterStore.Checkpoint checkpoint = ReporterStore.loadCheckpoint(connection);
        checkSameJournal(checkpoint, identity);
        EventAttributor attributor = new EventAttributor(ReporterStore.loadResume(connection, checkpoint));
        long inboundFrom = (checkpoint.inboundSeq() == 0L) ? inbound.startPosition() : checkpoint.inboundPosition();
        long outboundFrom = (checkpoint.outboundSeq() == 0L) ? outbound.startPosition() : checkpoint.outboundPosition();
        checkWithinRecording("inbound", inboundFrom, inboundGate.limit());
        checkWithinRecording("outbound", outboundFrom, outboundGate.limit());

        checkpointInboundSeq = checkpoint.inboundSeq();
        checkpointOutboundSeq = checkpoint.outboundSeq();
        batch.clear();
        if (failuresInARow == 0) {
            log.info("The Reporter resumes from inbound seq {} (position {}), outbound seq {} (position {})",
                    checkpoint.inboundSeq(), inboundFrom, checkpoint.outboundSeq(), outboundFrom);
        }
        state = "RUNNING";
        Runnable running = onRunPending;
        if (running != null) {
            running.run();
        }

        Pass pass = new Pass(attributor, inboundFrom, outboundFrom);
        try (RecordingFollower commands = new RecordingFollower(journal.archive(), inbound.recordingId(),
                     Journal.REPORTER_INBOUND_STREAM_ID, inboundFrom);
             RecordingFollower events = new RecordingFollower(journal.archive(), outbound.recordingId(),
                     Journal.REPORTER_OUTBOUND_STREAM_ID, outboundFrom)) {
            long lastProgress = System.nanoTime();
            while (!stopping) {
                int work = 0;
                if (pass.neededCommand > 0L) {
                    work += commands.poll(pass.commandHandler, FRAGMENT_LIMIT, inboundGate.limit());
                    pass.rethrow();
                    if (attributor.inboundSeq() >= pass.neededCommand) {
                        pass.neededCommand = 0L;
                    }
                }
                if (pass.neededCommand == 0L) {
                    work += events.poll(pass.eventHandler, FRAGMENT_LIMIT, outboundGate.limit());
                    pass.rethrow();
                }

                long now = System.nanoTime();
                if (!pass.awaitingEvent && batch.due(timing, now)) {
                    flush(connection, attributor, pass, identity);
                    lastProgress = System.nanoTime();
                } else if (work > 0) {
                    lastProgress = now;
                } else {
                    boolean owed = pass.neededCommand > 0L || events.position() < outboundGate.limit();
                    if (owed && now - lastProgress > timing.stallTimeoutMs() * 1_000_000L) {
                        throw new IllegalStateException("The Reporter's replay made no progress for "
                                + timing.stallTimeoutMs() + " ms: command position " + commands.position()
                                + " of " + inboundGate.limit() + ", event position " + events.position()
                                + " of " + outboundGate.limit() + ", waiting for command " + pass.neededCommand);
                    }
                    if (!owed) {
                        lastProgress = now;
                    }
                    LockSupport.parkNanos(IDLE_SLICE_NANOS);
                }
            }
        }
    }

    private void flush(Connection connection, EventAttributor attributor, Pass pass,
                       ReporterStore.JournalIdentity identity) throws SQLException {
        boolean runsRecorded = !batch.pendingRuns.isEmpty();
        ReporterStore.apply(connection, batch, new ReporterStore.Checkpoint(attributor.inboundSeq(),
                attributor.outboundSeq(), pass.inboundPosition, pass.outboundPosition, attributor.sessionDate(),
                attributor.sessionSymbol(), identity), beforeCommit);
        checkpointInboundSeq = attributor.inboundSeq();
        checkpointOutboundSeq = attributor.outboundSeq();
        batch.clear();
        Runnable listener = onRunPending;
        if (runsRecorded && listener != null) {
            listener.run();
        }
        if (failuresInARow > 0) {
            // Only a commit proves the database is back, so a pass that keeps failing keeps backing off.
            log.info("The Reporter is writing again after {} failure(s), at inbound seq {}, outbound seq {}",
                    failuresInARow, checkpointInboundSeq, checkpointOutboundSeq);
            failuresInARow = 0;
            suppressedErrors = 0;
        }
    }

    private ReporterStore.JournalIdentity journalIdentity() {
        RecordingDescriptor in = journal.findRecording(Journal.INBOUND_STREAM_ID);
        RecordingDescriptor out = journal.findRecording(Journal.OUTBOUND_STREAM_ID);
        return new ReporterStore.JournalIdentity(in.recordingId(), in.startTimestamp(), out.recordingId(), out.startTimestamp());
    }

    /** The P15-5 snapshot rule for the checkpoint: it names its journal, and only that journal may continue it. */
    private void checkSameJournal(ReporterStore.Checkpoint checkpoint, ReporterStore.JournalIdentity identity) {
        if (checkpoint.identity() != null && !checkpoint.identity().equals(identity)) {
            throw new ReporterInconsistencyException("This database's checkpoint (inbound seq " + checkpoint.inboundSeq()
                    + ", outbound seq " + checkpoint.outboundSeq() + ") belongs to another journal: it names "
                    + checkpoint.identity() + ", this journal is " + identity + ". Point " + ExchangeConfig.DB_URL
                    + " at the database for this journal, restore the original " + ExchangeConfig.EXCHANGE_DATA_DIR
                    + ", or, to record this journal here, clear the trading record and reset the checkpoint.");
        }
    }

    private static void checkWithinRecording(String stream, long position, long limit) {
        if (position > limit) {
            throw new ReporterInconsistencyException("The checkpoint's " + stream + " position " + position
                    + " is beyond the recording's end " + limit + ": the journal lost data this database already holds"
                    + " (a machine crash lost its page-cache tail, or it was restored from an older copy).");
        }
    }

    private void backOff(Exception cause) {
        state = "BACKING_OFF";
        batch.clear();
        failuresInARow++;
        long now = System.nanoTime();
        if (failuresInARow == 1 || now - lastErrorNanos >= timing.errorLogIntervalMs() * 1_000_000L) {
            log.error("The Reporter failed and will retry ({} failure(s) in a row, {} not logged); matching is"
                    + " unaffected and it resumes from the checkpoint at inbound seq {}, outbound seq {}: {}",
                    failuresInARow, suppressedErrors, checkpointInboundSeq, checkpointOutboundSeq, cause.toString());
            lastErrorNanos = now;
            suppressedErrors = 0;
        } else {
            suppressedErrors++;
        }
        int shift = Math.min(failuresInARow - 1, 20);
        long delayMs = Math.min(timing.backoffMaxMs(), timing.backoffMinMs() << shift);
        long deadline = now + delayMs * 1_000_000L;
        while (!stopping && System.nanoTime() - deadline < 0) {
            LockSupport.parkNanos(Math.min(BACKOFF_SLICE_MS, delayMs) * 1_000_000L);
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    /** One connection's reading state. Handlers never let an exception escape: an image poll would skip the fragment. */
    private final class Pass {
        final EventAttributor attributor;
        long neededCommand;
        /** An event's commands were read but the event is not applied: a batch must not end here. */
        boolean awaitingEvent;
        long inboundPosition;
        long outboundPosition;
        RuntimeException thrown;

        final ControlledFragmentHandler commandHandler = this::onCommandFragment;
        final ControlledFragmentHandler eventHandler = this::onEventFragment;

        Pass(EventAttributor attributor, long inboundPosition, long outboundPosition) {
            this.attributor = attributor;
            this.inboundPosition = inboundPosition;
            this.outboundPosition = outboundPosition;
        }

        ControlledFragmentHandler.Action onCommandFragment(DirectBuffer buffer, int offset, int length, Header header) {
            try {
                long seq = commandCodec.decode(buffer, offset, command);
                attributor.onCommand(seq, command, batch);
                inboundPosition = header.position();
                return seq >= neededCommand ? ControlledFragmentHandler.Action.BREAK
                        : ControlledFragmentHandler.Action.CONTINUE;
            } catch (RuntimeException e) {
                thrown = e;
                return ControlledFragmentHandler.Action.ABORT;
            }
        }

        ControlledFragmentHandler.Action onEventFragment(DirectBuffer buffer, int offset, int length, Header header) {
            try {
                eventCodec.decode(buffer, offset, event);
                attributor.checkNext(event);
                if (!attributor.ready(event)) {
                    neededCommand = event.commandSeq;
                    awaitingEvent = true;
                    return ControlledFragmentHandler.Action.ABORT;   // redelivered once the command is read
                }
                attributor.onEvent(event, batch, System.nanoTime());
                awaitingEvent = false;
                outboundPosition = header.position();
                return batch.events() >= timing.maxBatchEvents() ? ControlledFragmentHandler.Action.BREAK
                        : ControlledFragmentHandler.Action.CONTINUE;
            } catch (RuntimeException e) {
                thrown = e;
                return ControlledFragmentHandler.Action.ABORT;
            }
        }

        void rethrow() {
            if (thrown != null) {
                throw thrown;
            }
        }
    }
}
