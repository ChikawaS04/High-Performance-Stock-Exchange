package journal;

import codec.ExecutionEventCodec;
import codec.OrderEventCodec;
import com.lmax.disruptor.RingBuffer;
import config.ExchangeConfig;
import engine.CommandApplier;
import engine.CommandHandler;
import engine.EngineAgent;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import event.ExecutionEvent;
import event.ExecutionEventType;
import event.ExecutionHistory;
import event.FailStopExceptionHandler;
import event.InboundPipeline;
import event.OrderEvent;
import event.OrderEventType;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import io.aeron.Image;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import recovery.RecoveryException;
import recovery.RestoredSnapshot;
import recovery.SnapshotScheduler;
import recovery.SnapshotSink;
import recovery.SnapshotStore;
import recovery.SnapshotWriter;
import util.FailStop;
import util.VenueStatus;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * The venue's journaled core (SRS §1.2): inbound ring, sequencer, recorded inbound stream,
 * journal-gated engine agent, outbound and snapshot rings, and the outbound recorder writing the
 * recorded outbound stream. Main and the integration tests both build the venue through this
 * class, so the tests run Main's startup and shutdown order.
 *
 * <pre>
 *   gateway --> [inbound ring] --> Sequencer --> recorded IPC stream 1001 --> EngineAgent
 *       --> MatchingEngineHandler --> [outbound ring] --> OutboundRecorder --> recorded stream 1002
 *                                                     --> subscribers (publisher, TradeLogger)
 *                                 --> [snapshot ring] --> subscribers
 * </pre>
 *
 * Construction (SRS §3.10, D13):
 * <ol>
 *   <li>launch the journal, retrying once if a crashed run's heartbeat is still fresh;</li>
 *   <li>open (start or extend, D10) the outbound recording and read its last event: its seq
 *       {@code L} is the publish floor;</li>
 *   <li>load the newest valid snapshot whose outbound seq is at or below {@code L}, so replay
 *       always regenerates every event the recording lacks; check it names this journal's
 *       inbound recording;</li>
 *   <li>open the recorded inbound publication and the sequencer on it; check the snapshot is not
 *       beyond the journal and the journal is not behind the outbound recording;</li>
 *   <li>subscribe the engine and resolve the D11 gate;</li>
 *   <li>build the handler, the three rings (the recorder registered on the outbound ring with a
 *       fail-stop policy), the snapshot writer and scheduler, and the {@link CommandApplier};
 *       restore the snapshot into the engine and handler.</li>
 * </ol>
 * {@link #start()}: the outbound and snapshot consumers start, so the recorder is consuming; then
 * the engine thread replays the inbound recording from the snapshot through the applier with
 * every event at or below {@code L} suppressed and the recorded event at {@code L} checked, so
 * events produced before a stop but never recorded are published (and recorded) exactly once;
 * then it publishes the recovered BOOK and becomes the agent. Replay runs on the engine thread so
 * each ring keeps a single producer thread. {@code start()} waits, with no fixed timeout, until
 * recovery is done, then (bounded) until the recovered events and book have reached every
 * subscriber, then starts the sequencer. Every replay failure stops startup with a
 * {@link RecoveryException} telling the operator what to do; Main exits with 2.
 *
 * Snapshots: every {@code SNAPSHOT_INTERVAL} applied commands, at every accepted SESSION_CLOSE
 * (P15-8, the handler's session-close hook requests one) ({@link SnapshotScheduler}, encoded on the
 * engine thread, written by the snapshot writer thread), and on graceful shutdown.
 *
 * Data from before the session lifecycle (P15-8). Such a journal holds orders matched with no
 * session open; this engine rejects every order while closed, so replaying it would regenerate a
 * different history. Construction refuses it with a {@link RecoveryException} naming the remedy
 * ({@link #checkNotBeforeSessions}). The check is skipped when the snapshot recovery starts from
 * was written by a build with sessions (it passed the check then) or the journal is empty.
 *
 * Fail-stop. A fatal sequencer or recorder offer, a recorder gap, and any unexpected engine-agent
 * exception go to the {@link FailStop}; none of those threads ever advances past the failure.
 *
 * Graceful shutdown ({@link #shutdown()}) drains by progress, every wait bounded: the inbound
 * ring until the sequencer has journaled everything the gateway published, then the agent until
 * it has applied the last journaled seq; then the outbound and snapshot rings, so the recorder has
 * offered every event; then the snapshot of that state; then the journal closes, waiting for
 * both recordings to reach their publications. The caller stops input (the server) first.
 */
public final class JournaledPipeline implements AutoCloseable, VenueStatus {

    private static final Logger log = LoggerFactory.getLogger(JournaledPipeline.class);

    /** Bound on each shutdown drain and on the post-recovery drains in {@link #start()}. */
    static final long DRAIN_TIMEOUT_MS = 10_000L;

    /**
     * Test seams, all null in production.
     *
     * @param commandTap   called on the applying thread with every decoded command (replayed or
     *                     live) before the engine handler
     * @param snapshotSink where periodic and requested snapshots go instead of the snapshot
     *                     directory; the shutdown snapshot and recovery always use the directory
     * @param beforeRecord called by the outbound recorder with each seq before it is recorded; may
     *                     block (a slow recorder) or throw (a recorder failure)
     */
    public record Seams(CommandHandler commandTap, SnapshotSink snapshotSink, LongConsumer beforeRecord) {
        public static final Seams NONE = new Seams(null, null, null);
    }

    private final Journal journal;
    private final Sequencer sequencer;
    private final OutboundRecorder recorder;
    private final OutboundHistory outboundHistory;
    private final InboundPipeline inbound;
    private final OutboundPipeline outbound;
    private final SnapshotPipeline snapshots;
    private final MatchingEngine engine;
    private final MatchingEngineHandler handler;
    private final CommandApplier applier;
    private final EngineAgent agent;
    private final AgentRunner agentRunner;
    private final FailStop failStop;
    private final SnapshotWriter snapshotWriter;
    private final SnapshotScheduler snapshotScheduler;
    private final ExchangeConfig config;
    private final RecordedPublication inboundRecorded;
    private final long recoveredSnapshotSeq;
    private final long replayFromSeq;
    private final long replayFromPosition;
    private final long outboundFloor;
    private final long constructedNanos;

    private volatile boolean fatal;
    /** Set on the engine thread once replay has finished and the recovered book is on the snapshot ring. */
    private volatile boolean recovered;
    /** Set on the engine thread if replay failed; the agent then never runs. */
    private volatile Throwable recoveryFailure;
    private volatile long replayedCommands;
    private Thread engineThread;
    private boolean started;
    private boolean closed;

    /** The production pipeline. */
    public JournaledPipeline(ExchangeConfig config, MatchingEngine engine, FailStop failStop) {
        this(config, engine, failStop, Seams.NONE);
    }

    /** With a command tap (see {@link Seams#commandTap()}). */
    public JournaledPipeline(ExchangeConfig config, MatchingEngine engine, FailStop failStop, CommandHandler tap) {
        this(config, engine, failStop, new Seams(tap, null, null));
    }

    /** With a command tap and a periodic-snapshot sink (see {@link Seams}). */
    public JournaledPipeline(ExchangeConfig config, MatchingEngine engine, FailStop failStop, CommandHandler tap,
                             SnapshotSink snapshotSink) {
        this(config, engine, failStop, new Seams(tap, snapshotSink, null));
    }

    public JournaledPipeline(ExchangeConfig config, MatchingEngine engine, FailStop failStop, Seams seams) {
        this.failStop = (component, cause) -> {
            fatal = true;
            failStop.fatal(component, cause);
        };
        this.config = config;
        this.engine = engine;
        this.constructedNanos = System.nanoTime();
        Journal launched = Journal.launchRetryingOnce(config, Journal.CRASH_RETRY_DELAY_MS);
        SnapshotWriter writer = null;
        try {
            // The newest snapshot is checked against the journal before anything is opened, so a
            // deleted or replaced journal refuses startup without a recording being started.
            SnapshotStore store = new SnapshotStore(config.snapshotDir());
            RestoredSnapshot newest = loadSnapshot(store, Long.MAX_VALUE);
            checkRecording(newest, launched, config);

            RecordedPublication outboundRecorded = launched.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
            this.recorder = OutboundRecorder.open(launched, outboundRecorded, new BackoffIdleStrategy(),
                    seams.beforeRecord());
            this.outboundFloor = recorder.lastSeqAtOpen();

            RecordedPublication recorded = launched.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            this.inboundRecorded = recorded;
            this.sequencer = Sequencer.open(launched, recorded, new BackoffIdleStrategy());
            checkNotBeyondJournal(newest, recorded, sequencer.lastSeq(), config);
            checkJournalCoversOutbound(sequencer.lastSeq(), outboundFloor, config);

            // Rule 3: recover from a snapshot the outbound recording covers, so replay regenerates
            // every event the recording lacks. A newer one is skipped (logged by the store).
            RestoredSnapshot snapshot = newest;
            if (newest != null && newest.outboundLastSeq() > outboundFloor) {
                snapshot = loadSnapshot(store, outboundFloor);
                checkRecording(snapshot, launched, config);
                checkNotBeyondJournal(snapshot, recorded, sequencer.lastSeq(), config);
            }
            if ((snapshot == null || !snapshot.hasSessionState()) && sequencer.lastSeq() > 0) {
                checkNotBeforeSessions(launched, recorded, outboundRecorded, outboundFloor, config);
            }
            if (outboundFloor == 0 && sequencer.lastSeq() > 0) {
                log.warn("No outbound recording while the inbound journal holds {} commands (data from before P15-6,"
                        + " or the outbound recording was removed): rebuilding the outbound record by replaying the"
                        + " journal from seq 1", sequencer.lastSeq());
            }
            Image image = launched.subscribe(recorded);
            RecordingPositionGate gate = launched.recordingPositionGate(recorded);

            this.outbound = new OutboundPipeline();
            this.outbound.handleEventsWith(recorder, new FailStopExceptionHandler<>("outbound-recorder", this.failStop));
            this.snapshots = new SnapshotPipeline();
            this.handler = new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
            // Without this the engine reports nothing: fills surface through the listener seam.
            engine.setExecutionListener(handler);

            CommandHandler tap = seams.commandTap();
            CommandHandler sink = (tap == null) ? handler : command -> {
                tap.onCommand(command);
                handler.onCommand(command);
            };
            writer = new SnapshotWriter(seams.snapshotSink() == null ? store : seams.snapshotSink());
            this.snapshotWriter = writer;
            this.snapshotScheduler = new SnapshotScheduler(engine, handler, writer, store,
                    config.snapshotInterval(), recorded.recordingId());
            // SRS §3.10: a snapshot at every session close, taken at the SESSION_CLOSE's seq.
            handler.setSessionCloseHook(snapshotScheduler::requestSnapshot);

            long fromSeq = 0;
            long fromPosition = recorded.startPosition();
            if (snapshot != null) {
                snapshot.applyTo(engine, handler);
                fromSeq = snapshot.inboundSeq();
                fromPosition = snapshot.inboundPosition();
            }
            this.replayFromSeq = fromSeq;
            this.replayFromPosition = fromPosition;
            this.recoveredSnapshotSeq = (snapshot == null) ? -1L : snapshot.inboundSeq();
            this.applier = new CommandApplier(sink, snapshotScheduler, fromSeq, fromPosition);

            // The engine thread replays, publishes the recovered BOOK itself, then runs this agent.
            this.agent = new EngineAgent(image, gate, applier, recorded.joinPosition(), null);
            this.agentRunner = new AgentRunner(idleStrategy(config.engineIdle()),
                    t -> this.failStop.fatal("engine-agent", unwrap(t)), null, agent);
            this.inbound = new InboundPipeline(sequencer, new FailStopExceptionHandler<>("sequencer", this.failStop));
            this.outboundHistory = new OutboundHistory(launched.archive(), outboundRecorded,
                    launched.recordingPositionGate(outboundRecorded), this::publishedSeq);
            this.journal = launched;
        } catch (RuntimeException e) {
            if (writer != null) writer.close();
            launched.close();
            throw e;
        }
    }

    private static RestoredSnapshot loadSnapshot(SnapshotStore store, long maxOutboundSeq) {
        try {
            return store.loadNewestValid(maxOutboundSeq);
        } catch (IOException e) {
            throw new RecoveryException("Cannot read the snapshot directory " + store.dir() + ": " + e
                    + ". Fix its permissions, or move it aside to recover by replaying the whole journal.", e);
        }
    }

    /** The snapshot must name this journal's inbound recording, and that recording must exist. */
    private static void checkRecording(RestoredSnapshot snapshot, Journal journal, ExchangeConfig config) {
        if (snapshot == null) return;
        RecordingDescriptor existing = journal.findRecording(Journal.INBOUND_STREAM_ID);
        if (existing == null) {
            throw new RecoveryException("Snapshot at inbound seq " + snapshot.inboundSeq() + " was taken on inbound"
                    + " recording " + snapshot.inboundRecordingId() + ", but " + config.archiveDir()
                    + " holds no inbound recording: the journal was deleted. Restore " + config.archiveDir()
                    + " from backup, or, to start a fresh trading history, delete " + config.snapshotDir() + ".");
        }
        if (existing.recordingId() != snapshot.inboundRecordingId()
                || snapshot.inboundPosition() < existing.startPosition()) {
            throw new RecoveryException("Snapshot at inbound seq " + snapshot.inboundSeq() + " was taken on inbound"
                    + " recording " + snapshot.inboundRecordingId() + " at position " + snapshot.inboundPosition()
                    + ", but the journal in " + config.archiveDir() + " is recording " + existing.recordingId()
                    + " starting at " + existing.startPosition() + ": the journal was replaced. Restore the original "
                    + config.archiveDir() + ", or, if this journal is the intended history, delete "
                    + config.snapshotDir() + ".");
        }
    }

    /** A snapshot beyond the journal's end means the journal lost commands the venue had applied. */
    private static void checkNotBeyondJournal(RestoredSnapshot snapshot, RecordedPublication recorded,
                                              long journalSeq, ExchangeConfig config) {
        if (snapshot == null) return;
        if (snapshot.inboundSeq() > journalSeq || snapshot.inboundPosition() > recorded.joinPosition()) {
            throw new RecoveryException("The journal ends at inbound seq " + journalSeq + " (position "
                    + recorded.joinPosition() + "), but snapshot " + SnapshotStore.fileName(snapshot.inboundSeq())
                    + " records seq " + snapshot.inboundSeq() + " (position " + snapshot.inboundPosition()
                    + "). The journal lost commands the venue had applied: a machine crash lost them from the page"
                    + " cache, or " + config.archiveDir() + " was restored from an older copy. The journal is the"
                    + " authority. To start from it, move every snapshot with seq above " + journalSeq + " out of "
                    + config.snapshotDir() + " and restart; recovery then uses an older snapshot or replays from"
                    + " the start. Clients may have seen executions that are no longer in the journal.");
        }
    }

    /** Executions recorded with no command journal behind them: the inbound journal is missing or empty. */
    private static void checkJournalCoversOutbound(long journalSeq, long outboundSeq, ExchangeConfig config) {
        if (journalSeq == 0 && outboundSeq > 0) {
            throw new RecoveryException("The outbound recording holds executions up to seq " + outboundSeq
                    + " but the inbound journal in " + config.archiveDir() + " is missing or empty, so no execution"
                    + " can be regenerated. Do not delete the archive. Restore " + config.archiveDir()
                    + " from a backup in which both recordings are consistent, or move " + config.dataDir()
                    + " aside to start a fresh trading history.");
        }
    }

    /**
     * Refuses data written before the session lifecycle (P15-8). A venue with sessions rejects every
     * NEW while closed, so before its first SESSION_OPENED it can only have published ORDER_REJECTED:
     * nothing was accepted, so nothing could rest, fill, expire or be cancelled. Data with any of those
     * before the first SESSION_OPENED (or with none ever) was matched without sessions.
     *
     * With an outbound recording the check reads it, which is exact both ways. Without one (data from
     * before P15-6, or a removed recording) it reads the inbound journal: a NEW before the first
     * SESSION_OPEN. That is conservative in one corner, data with sessions whose outbound recording
     * was removed and whose first order came before its first open; the message says so.
     * Both scans stop at the first session open, so data with sessions costs one or a few messages.
     */
    private static void checkNotBeforeSessions(Journal journal, RecordedPublication inbound,
                                               RecordedPublication outbound, long outboundLastSeq,
                                               ExchangeConfig config) {
        JournalReader reader = new JournalReader(journal.archive());
        boolean legacy;
        String evidence;
        if (outboundLastSeq > 0) {
            ExecutionEventCodec codec = new ExecutionEventCodec();
            ExecutionEvent event = new ExecutionEvent();
            ExecutionEventType[] found = {null};
            reader.scan(outbound.recordingId(), (buffer, offset) -> {
                codec.decode(buffer, offset, event);
                if (event.eventType == ExecutionEventType.SESSION_OPENED) return false;
                if (event.eventType != ExecutionEventType.ORDER_REJECTED) {
                    found[0] = event.eventType;
                    return false;
                }
                return true;
            });
            legacy = found[0] != null;
            evidence = "the outbound recording holds " + found[0] + " before any SESSION_OPENED";
        } else {
            OrderEventCodec codec = new OrderEventCodec();
            OrderEvent command = new OrderEvent();
            boolean[] found = {false};
            reader.scan(inbound.recordingId(), (buffer, offset) -> {
                codec.decode(buffer, offset, command);
                if (command.eventType == OrderEventType.SESSION_OPEN) return false;
                if (command.eventType == OrderEventType.NEW_ORDER) {
                    found[0] = true;
                    return false;
                }
                return true;
            });
            legacy = found[0];
            evidence = "the inbound journal holds a NEW before any SESSION_OPEN and there is no outbound recording"
                    + " (if this data was written with sessions and its outbound recording was removed, the first"
                    + " order came before the first open and the data cannot be told apart from older data)";
        }
        if (legacy) {
            throw new RecoveryException("The journal in " + config.archiveDir() + " was written before the session"
                    + " lifecycle (P15-8): " + evidence + ". Its orders were matched with no session open, and this"
                    + " build rejects every order while the session is closed, so replay cannot reproduce that"
                    + " history. Move " + config.dataDir() + " aside (or delete it) to start a fresh trading history;"
                    + " the old data can be recovered only by the build that wrote it (commit ff64995 or earlier).");
        }
    }

    /** Replays the inbound journal tail through the applier, failing on the first command that does not apply. */
    private long replay() {
        RecordedPublication recorded = inboundRecorded;
        try {
            return new JournalReader(journal.archive()).replayFailFast(
                    recorded.recordingId(), replayFromPosition, recorded.joinPosition(), applier::apply);
        } catch (IllegalStateException e) {
            throw new RecoveryException("Recovery replay of inbound recording " + recorded.recordingId()
                    + " from position " + replayFromPosition + " to " + recorded.joinPosition() + " failed: "
                    + e.getMessage() + ". The journal, the snapshot or the outbound recording does not match this"
                    + " build's engine, which must regenerate the recorded executions exactly; keep "
                    + config.dataDir() + " for investigation and run the build that wrote it.", e);
        }
    }

    static IdleStrategy idleStrategy(ExchangeConfig.EngineIdle idle) {
        switch (idle) {
            case BACKOFF:   return new BackoffIdleStrategy();
            case SLEEPING:  return new SleepingMillisIdleStrategy(1);
            case YIELDING:  return new YieldingIdleStrategy();
            case BUSY_SPIN: return new BusySpinIdleStrategy();
            default:        throw new IllegalArgumentException("Unmapped engine idle: " + idle);
        }
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof AgentTerminationException && t.getCause() != null) ? t.getCause() : t;
    }

    /** The ring the gateway publishes into. */
    public RingBuffer<OrderEvent> inboundRing() {
        return inbound.getRingBuffer();
    }

    /** Register execution subscribers here before {@link #start()}. */
    public OutboundPipeline outbound() {
        return outbound;
    }

    /** Register depth subscribers here before {@link #start()}. */
    public SnapshotPipeline snapshots() {
        return snapshots;
    }

    public Journal journal() {
        return journal;
    }

    /** The recorded execution history RESEND reads (D16). */
    public ExecutionHistory outboundHistory() {
        return outboundHistory;
    }

    /**
     * Recovery has completed and no stage has reported a fail-stop. Any thread: reads only volatile
     * fields. It does not track shutdown; the server that reports it is stopped first (Main).
     */
    @Override
    public boolean running() {
        return recovered && !fatal && recoveryFailure == null;
    }

    /** The last inbound seq the sequencer journaled. Any thread. */
    @Override
    public long journaledSeq() {
        return sequencer.journaledSeq();
    }

    /** The last inbound seq the engine applied. Any thread. */
    @Override
    public long appliedSeq() {
        return agent.appliedSeq();
    }

    /** The engine's session state, as the engine thread last published it (P15-8). Any thread. */
    @Override
    public long session() {
        return handler.sessionWord();
    }

    /** The last outbound seq offered to the outbound recording. Any thread. */
    @Override
    public long recordedSeq() {
        return recorder.recordedSeq();
    }

    /** The last seq in the outbound recording when this run opened it: recovery's publish floor (D13). */
    public long outboundFloor() {
        return outboundFloor;
    }

    /**
     * The highest outbound seq published on the outbound ring so far, from any thread, without
     * touching engine state: {@code L + 1 + cursor}, where {@code L} is {@link #outboundFloor()}
     * and the cursor is the ring sequence of the last published slot (-1 before the first).
     *
     * This holds only because the first slot this run claims carries seq {@code L + 1}: recovery
     * restores a snapshot whose outbound seq is at or below {@code L} (rule 3, {@link
     * SnapshotStore#loadNewestValid(long)}), replay regenerates every seq from there with those at
     * or below {@code L} claiming no slot, and a replay ending below {@code L} refuses startup. If
     * that invariant broke, RESEND would stop short of events already broadcast.
     */
    @Override
    public long publishedSeq() {
        return outboundFloor + 1 + outbound.getRingBuffer().getCursor();
    }

    /** The inbound seq of the snapshot recovery started from, or -1 if it started from an empty book. */
    public long recoveredSnapshotSeq() {
        return recoveredSnapshotSeq;
    }

    /** Commands recovery replayed from the journal after the snapshot. Valid once {@link #start()} returns. */
    public long replayedCommands() {
        return replayedCommands;
    }

    /** The engine-thread snapshot trigger; {@link SnapshotScheduler#requestSnapshot()} is the session-close hook (P15-8). */
    public SnapshotScheduler snapshotScheduler() {
        return snapshotScheduler;
    }

    /** The snapshot writer thread's handoff. */
    public SnapshotWriter snapshotWriter() {
        return snapshotWriter;
    }

    /**
     * Starts the venue: outbound consumers (the recorder among them) and snapshot consumers; then
     * the engine thread, which replays the journal tail and publishes the recovered book before
     * running the agent; waits for that with no fixed timeout (a full rebuild can take long), then
     * bounded until the recovered events and book have reached every subscriber; then the
     * sequencer.
     *
     * @throws RecoveryException if replay failed; the venue is then closed and must not be used
     * @throws IllegalStateException if a fail-stop was reported during recovery (tests; production halts)
     */
    public void start() {
        if (started) throw new IllegalStateException("already started");
        started = true;
        outbound.start();
        snapshots.start();
        engineThread = new Thread(this::runEngine, "engine-agent");
        engineThread.setDaemon(true);   // a forgotten close must not wedge the JVM
        engineThread.start();

        while (!recovered && recoveryFailure == null && !fatal && engineThread.isAlive()) {
            LockSupport.parkNanos(1_000_000L);
        }
        if (!recovered || recoveryFailure != null) {
            Throwable cause = recoveryFailure;
            abortStart();
            if (cause instanceof RecoveryException recovery) throw recovery;
            if (cause != null) {
                throw new RecoveryException("Recovery failed: " + cause + ". Keep " + config.dataDir()
                        + " for investigation.", cause);
            }
            throw new IllegalStateException("The venue failed during recovery (fail-stop reported)");
        }
        await(() -> fatal || (outbound.isDrained() && snapshots.isDrained()),
                "the recovered events and book to reach every subscriber", () -> "recorded seq " + recorder.recordedSeq());
        inbound.start();
    }

    /**
     * The engine thread: recovery replay, then the agent. Every throwable from recovery is caught
     * here, so a failed replay never leaves a half-started venue: the agent never runs and
     * {@link #start()} closes everything. A recorder fail-stop during replay is reported by the
     * recorder's own thread and halts as usual.
     */
    private void runEngine() {
        try {
            handler.beginReplay(outboundFloor, recorder.lastRecordedAtOpen());
            long replayed = replay();
            if (applier.appliedSeq() != sequencer.lastSeq()) {
                throw new RecoveryException("Replay ended at inbound seq " + applier.appliedSeq()
                        + " but the journal's last seq is " + sequencer.lastSeq() + " (recording "
                        + inboundRecorded.recordingId() + ", positions " + replayFromPosition + " to "
                        + inboundRecorded.joinPosition() + "). The journal is not a gap-free sequence; do not delete it,"
                        + " keep " + config.dataDir() + " for investigation.");
            }
            long regenerated = handler.getLastSeq();
            if (regenerated < outboundFloor) {
                throw new RecoveryException("The outbound recording ends at seq " + outboundFloor
                        + " but replaying the inbound journal regenerates only up to seq " + regenerated
                        + ". The inbound journal lost commands (a machine crash lost its page-cache tail, or "
                        + config.archiveDir() + " was restored from an inconsistent copy). Clients may hold executions "
                        + (regenerated + 1) + " to " + outboundFloor + " that no longer exist. Do not delete the archive."
                        + " Restore " + config.archiveDir() + " from a consistent backup, or move " + config.dataDir()
                        + " aside to start a fresh trading history.");
            }
            handler.endReplay(applier.appliedSeq() > 0);
            replayedCommands = replayed;
            log.info("Recovered in {} ms: {}, replayed {} commands (seq {} to {}); inbound recording {} joined at"
                            + " position {}; outbound recording ends at seq {}, published {} events above it; outbound"
                            + " seq {}, next trade id {}, engine idle {}",
                    (System.nanoTime() - constructedNanos) / 1_000_000L,
                    recoveredSnapshotSeq < 0 ? "no snapshot" : "snapshot at seq " + recoveredSnapshotSeq,
                    replayed, replayFromSeq + 1, applier.appliedSeq(), inboundRecorded.recordingId(),
                    inboundRecorded.joinPosition(), outboundFloor, regenerated - outboundFloor, regenerated,
                    engine.getNextTradeId(), config.engineIdle().value());
            recovered = true;
        } catch (Throwable t) {
            recoveryFailure = t;
            return;
        }
        agentRunner.run();
    }

    /** Recovery failed: nothing was accepted yet; stop the consumers and close the journal. */
    private void abortStart() {
        closed = true;
        boolean engineStopped = false;
        try {
            engineStopped = closeAgent();
            outbound.halt();
            snapshots.halt();
        } finally {
            snapshotWriter.close();
            closeJournalUnless(!engineStopped);
        }
    }

    /**
     * Closes the journal, unless a thread that reads its mapped buffers may still run. The engine
     * thread reads the inbound image's term buffers; one abandoned by {@link #closeAgent()} is
     * parked inside an image poll, and closing the journal under it would leave it pointing at
     * unmapped memory if it ever resumed (an access violation, not an exception). The process is
     * exiting in that case anyway, so the journal is left open, which a crash leaves it too, and the
     * next start recovers from it.
     */
    private void closeJournalUnless(boolean engineAbandoned) {
        if (engineAbandoned) {
            log.error("Not closing the journal: the engine thread did not stop and may still read its mapped"
                    + " buffers. Exit the process; the next start recovers from the journal as after a crash.");
            return;
        }
        journal.close();
    }

    /** {@link #shutdown()}, ignoring whether every drain completed. */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * Graceful shutdown, drained by progress. Call after input has stopped. Each wait is bounded;
     * one that times out is logged and shutdown moves on, so the journal always closes. After a
     * fatal the drains are skipped: the failed thread will never catch up.
     *
     * @return true if every drain completed
     */
    public boolean shutdown() {
        if (closed) return true;
        closed = true;
        boolean clean = true;
        // Not started: there is no engine thread. Started: false until the agent is known to have stopped.
        boolean engineStopped = !started;
        try {
            if (started) {
                if (fatal) {
                    log.error("Shutting down after a fatal error: drains skipped at journaled seq {}, applied seq {},"
                            + " recorded outbound seq {}", sequencer.journaledSeq(), agent.appliedSeq(),
                            recorder.recordedSeq());
                    clean = false;
                } else {
                    clean &= await(inbound::isDrained, "the sequencer to journal every published command",
                            () -> "journaled seq " + sequencer.journaledSeq());
                }
                inbound.halt();

                if (!fatal) {
                    final long target = sequencer.journaledSeq();
                    clean &= await(() -> agent.appliedSeq() >= target, "the engine to apply seq " + target,
                            () -> "applied seq " + agent.appliedSeq());
                }
                engineStopped = closeAgent();
                clean &= engineStopped;

                if (!fatal) {
                    // Before the snapshot: the recorder has then offered every event the snapshot covers.
                    clean &= await(outbound::isDrained, "the outbound ring to drain",
                            () -> "recorded outbound seq " + recorder.recordedSeq());
                    clean &= await(snapshots::isDrained, "the snapshot ring to drain", () -> "not drained");
                }

                if (!fatal) {
                    // The agent's thread has stopped between two commands, so this is a consistent cut.
                    final long seq = agent.appliedSeq();
                    final long position = agent.appliedPosition();
                    boolean written;
                    try {
                        written = snapshotScheduler.writeFinal(seq, position, DRAIN_TIMEOUT_MS);
                    } catch (IOException | RuntimeException e) {
                        log.error("Shutdown snapshot at seq {} failed", seq, e);
                        written = false;
                    }
                    if (!written) {
                        log.error("Shutdown snapshot at seq {} was not written; the next start replays the journal"
                                + " from the previous snapshot", seq);
                    }
                    clean &= written;
                }

                outbound.halt();
                snapshots.halt();
                log.info("Pipeline drained: journaled seq {}, applied seq {}, recorded outbound seq {}",
                        sequencer.journaledSeq(), agent.appliedSeq(), recorder.recordedSeq());
            }
        } finally {
            snapshotWriter.close();
            closeJournalUnless(!engineStopped);
        }
        return clean && !fatal;
    }

    /**
     * Stops the agent. Normally {@link AgentRunner#close()}, which waits for the agent's thread.
     * After a fatal that wait could be endless: once the recorder (or another consumer) has died,
     * the engine thread can be parked in the outbound ring's {@code next()}, which ignores
     * interrupts, and Agrona's close retries for as long as the thread lives. So after a fatal the
     * close runs on a helper thread joined for {@link #DRAIN_TIMEOUT_MS}; if it has not finished, a
     * WARN is logged and the daemon engine thread is abandoned (production has halted already).
     * The engine thread itself is then joined, bounded, which also covers a recovery that stalled
     * before the agent ever ran (its runner closes at once while the thread is still replaying).
     *
     * @return true if the engine thread has stopped; false if it was abandoned, in which case the
     *         journal must not be closed ({@link #closeJournalUnless})
     */
    private boolean closeAgent() {
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        if (!fatal) {
            agentRunner.close();
        } else {
            Thread closer = new Thread(() -> agentRunner.close(AgentRunner.RETRY_CLOSE_TIMEOUT_MS, t -> { }),
                    "engine-agent-close");
            closer.setDaemon(true);
            closer.start();
            join(closer, deadline);
        }
        if (engineThread != null) {
            join(engineThread, deadline);
        }
        boolean stopped = engineThread == null || !engineThread.isAlive();
        if (!stopped) {
            log.warn("The engine thread did not stop within {} ms after the fatal error (it is likely blocked on a"
                    + " full outbound ring); abandoning it", DRAIN_TIMEOUT_MS);
        }
        return stopped;
    }

    private static void join(Thread thread, long deadlineMs) {
        long remaining = deadlineMs - System.currentTimeMillis();
        if (remaining <= 0) return;
        try {
            thread.join(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean await(BooleanSupplier done, String what, Supplier<String> progress) {
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                log.error("Shutdown gave up waiting for {} after {} ms ({})", what, DRAIN_TIMEOUT_MS, progress.get());
                return false;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        return true;
    }
}
