package journal;

import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import config.ExchangeConfig;
import engine.CommandApplier;
import engine.CommandHandler;
import engine.EngineAgent;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import event.InboundPipeline;
import event.OrderEvent;
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

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The venue's journaled core (SRS §1.2): inbound ring, sequencer, recorded inbound stream,
 * journal-gated engine agent, outbound and snapshot rings. Main and the integration tests both
 * build the venue through this class, so the tests run Main's startup and shutdown order.
 *
 * <pre>
 *   gateway --> [inbound ring] --> Sequencer --> recorded IPC stream 1001 --> EngineAgent
 *       --> MatchingEngineHandler --> [outbound ring] / [snapshot ring] --> subscribers
 * </pre>
 *
 * Startup (constructor, then {@link #start()}), with recovery (P15-5, SRS §3.10):
 * <ol>
 *   <li>launch the journal, retrying once if a crashed run's heartbeat is still fresh;</li>
 *   <li>load the newest valid snapshot, if any, and check it names this journal's inbound
 *       recording;</li>
 *   <li>open the recorded inbound publication (extending the recording) and the sequencer on it,
 *       which reads the journal's last seq; check the snapshot is not beyond the journal;</li>
 *   <li>subscribe the engine to the stream and wait for its image, which joins at the
 *       publication's position; resolve the D11 gate on the recording's position counter;</li>
 *   <li>build the engine handler, the three rings, the snapshot writer and scheduler, and the
 *       {@link CommandApplier} shared by replay and the agent;</li>
 *   <li>restore the snapshot into the engine and handler, then replay the inbound recording
 *       from the snapshot's position to the join position through the applier, with every
 *       replayed execution event suppressed (publish floor {@link #REPLAY_PUBLISH_FLOOR}); check
 *       the applied seq now equals the journal's last seq;</li>
 *   <li>build the agent on the applier. The caller then builds the gateway on
 *       {@link #inboundRing()} and registers outbound and snapshot subscribers;</li>
 *   <li>{@link #start()}: outbound and snapshot consumers; then the agent, which first publishes
 *       one BOOK of the recovered book on its own thread (the rings keep one producer thread),
 *       waited for until the depth subscribers have it (snapshot-on-connect); then the
 *       sequencer.</li>
 * </ol>
 * Nothing reaches the sequencer before the caller starts accepting input after {@link #start()},
 * and the engine's image exists from step 4 and does not move during replay, so the engine sees
 * every command exactly once, from replay or live. Any mismatch between snapshot and journal is a
 * {@link RecoveryException} whose message tells the operator what to do; Main exits with 2.
 *
 * Snapshots: every {@code SNAPSHOT_INTERVAL} applied commands and on request
 * ({@link SnapshotScheduler}, encoded on the engine thread, written by the snapshot writer
 * thread), and on graceful shutdown.
 *
 * Fail-stop. A fatal sequencer offer and any unexpected engine-agent exception go to the
 * {@link FailStop}; neither thread ever advances past the failing command.
 *
 * Graceful shutdown ({@link #shutdown()}) drains by progress, every wait bounded: the inbound
 * ring until the sequencer has journaled everything the gateway published, then the agent until
 * it has applied the last journaled seq; then the snapshot of that state is written; then the
 * outbound and snapshot rings drain and the journal closes. The caller stops input (the server)
 * first.
 */
public final class JournaledPipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JournaledPipeline.class);

    /** Bound on each shutdown drain. */
    static final long DRAIN_TIMEOUT_MS = 10_000L;

    /**
     * Publish floor while replaying (D13): every replayed execution event is suppressed. Nothing
     * records the outbound stream before P15-6, so republishing would only repeat TradeLogger
     * lines and reach no client (none is connected during recovery). P15-6 replaces this with the
     * last seq in the outbound recording.
     */
    static final long REPLAY_PUBLISH_FLOOR = Long.MAX_VALUE;

    private final Journal journal;
    private final Sequencer sequencer;
    private final InboundPipeline inbound;
    private final OutboundPipeline outbound;
    private final SnapshotPipeline snapshots;
    private final MatchingEngineHandler handler;
    private final EngineAgent agent;
    private final AgentRunner agentRunner;
    private final FailStop failStop;
    private final SnapshotWriter snapshotWriter;
    private final SnapshotScheduler snapshotScheduler;
    private final long recoveredSnapshotSeq;
    private final long replayedCommands;

    private volatile boolean fatal;
    /** Set on the agent thread once the recovered book is on the snapshot ring. */
    private volatile boolean recoveredBookPublished;
    private boolean started;
    private boolean closed;

    /** The production pipeline. */
    public JournaledPipeline(ExchangeConfig config, MatchingEngine engine, FailStop failStop) {
        this(config, engine, failStop, null);
    }

    /**
     * @param tap test seam: when non-null, called on the applying thread with every decoded
     *            command (replayed or live) before the engine handler. Null in production.
     */
    public JournaledPipeline(ExchangeConfig config, MatchingEngine engine, FailStop failStop, CommandHandler tap) {
        this(config, engine, failStop, tap, null);
    }

    /**
     * @param snapshotSink test seam: when non-null, periodic and requested snapshots go here
     *                     instead of the snapshot directory. The shutdown snapshot and recovery
     *                     always use the directory. Null in production.
     */
    public JournaledPipeline(ExchangeConfig config, MatchingEngine engine, FailStop failStop, CommandHandler tap,
                             SnapshotSink snapshotSink) {
        this.failStop = (component, cause) -> {
            fatal = true;
            failStop.fatal(component, cause);
        };
        long startNanos = System.nanoTime();
        Journal launched = Journal.launchRetryingOnce(config, Journal.CRASH_RETRY_DELAY_MS);
        SnapshotWriter writer = null;
        try {
            SnapshotStore store = new SnapshotStore(config.snapshotDir());
            RestoredSnapshot snapshot = loadSnapshot(store);
            checkRecording(snapshot, launched, config);

            RecordedPublication recorded = launched.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            this.sequencer = Sequencer.open(launched, recorded, new BackoffIdleStrategy());
            checkNotBeyondJournal(snapshot, recorded, sequencer.lastSeq(), config);
            Image image = launched.subscribe(recorded);
            RecordingPositionGate gate = launched.recordingPositionGate(recorded);

            this.outbound = new OutboundPipeline();
            this.snapshots = new SnapshotPipeline();
            this.handler = new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
            // Without this the engine reports nothing: fills surface through the listener seam.
            engine.setExecutionListener(handler);

            CommandHandler sink = (tap == null) ? handler : command -> {
                tap.onCommand(command);
                handler.onCommand(command);
            };
            writer = new SnapshotWriter(snapshotSink == null ? store : snapshotSink);
            this.snapshotWriter = writer;
            this.snapshotScheduler = new SnapshotScheduler(engine, handler, writer, store,
                    config.snapshotInterval(), recorded.recordingId());

            long fromSeq = 0;
            long fromPosition = recorded.startPosition();
            if (snapshot != null) {
                snapshot.applyTo(engine, handler);
                fromSeq = snapshot.inboundSeq();
                fromPosition = snapshot.inboundPosition();
            }
            CommandApplier applier = new CommandApplier(sink, snapshotScheduler, fromSeq, fromPosition);
            handler.beginReplay(REPLAY_PUBLISH_FLOOR);
            this.replayedCommands = replay(launched, recorded, fromPosition, applier);
            if (applier.appliedSeq() != sequencer.lastSeq()) {
                throw new RecoveryException("Replay ended at inbound seq " + applier.appliedSeq()
                        + " but the journal's last seq is " + sequencer.lastSeq() + " (recording "
                        + recorded.recordingId() + ", positions " + fromPosition + " to " + recorded.joinPosition()
                        + "). The journal is not a gap-free sequence; do not delete it, keep " + config.dataDir()
                        + " for investigation.");
            }
            this.recoveredSnapshotSeq = (snapshot == null) ? -1L : snapshot.inboundSeq();

            // The recovered BOOK is published on the agent's thread, the rings' only producer thread.
            final boolean publishBook = applier.appliedSeq() > 0;
            this.agent = new EngineAgent(image, gate, applier, recorded.joinPosition(), () -> {
                handler.endReplay(publishBook);
                recoveredBookPublished = true;
            });
            this.agentRunner = new AgentRunner(idleStrategy(config.engineIdle()),
                    t -> this.failStop.fatal("engine-agent", unwrap(t)), null, agent);
            this.inbound = new InboundPipeline(sequencer, new SequencerFailStop(this.failStop));
            this.journal = launched;
            log.info("Recovered in {} ms: {}, replayed {} commands (seq {} to {}); inbound recording {} joined at"
                            + " position {}, outbound seq {}, next trade id {}, engine idle {}",
                    (System.nanoTime() - startNanos) / 1_000_000L,
                    snapshot == null ? "no snapshot" : "snapshot at seq " + snapshot.inboundSeq(),
                    replayedCommands, fromSeq + 1, applier.appliedSeq(), recorded.recordingId(),
                    recorded.joinPosition(), handler.getLastSeq(), engine.getNextTradeId(), config.engineIdle().value());
        } catch (RuntimeException e) {
            if (writer != null) writer.close();
            launched.close();
            throw e;
        }
    }

    private static RestoredSnapshot loadSnapshot(SnapshotStore store) {
        try {
            return store.loadNewestValid();
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

    /** Replays the journal tail through the applier, failing on the first command that does not apply. */
    private static long replay(Journal journal, RecordedPublication recorded, long from, CommandApplier applier) {
        try {
            return new JournalReader(journal.archive()).replayFailFast(
                    recorded.recordingId(), from, recorded.joinPosition(), applier::apply);
        } catch (IllegalStateException e) {
            throw new RecoveryException("Recovery replay of inbound recording " + recorded.recordingId() + " from position "
                    + from + " to " + recorded.joinPosition() + " failed: " + e.getMessage() + ". The journal or the"
                    + " snapshot does not match this build's engine; keep " + journal.config().dataDir()
                    + " for investigation.", e);
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

    /** The last inbound seq the sequencer journaled. Any thread. */
    public long journaledSeq() {
        return sequencer.journaledSeq();
    }

    /** The last inbound seq the engine applied. Any thread. */
    public long appliedSeq() {
        return agent.appliedSeq();
    }

    /** The inbound seq of the snapshot recovery started from, or -1 if it started from an empty book. */
    public long recoveredSnapshotSeq() {
        return recoveredSnapshotSeq;
    }

    /** Commands recovery replayed from the journal after the snapshot. */
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
     * Starts consumers inward-out: outbound and snapshot, then the engine agent, which first
     * publishes the recovered book once (on its own thread); that is waited for (bounded) until
     * the depth subscribers have it, so snapshot-on-connect shows it before any new command; then
     * the sequencer.
     */
    public void start() {
        if (started) throw new IllegalStateException("already started");
        started = true;
        outbound.start();
        snapshots.start();
        AgentRunner.startOnThread(agentRunner, r -> {
            Thread t = new Thread(r, "engine-agent");
            t.setDaemon(true);   // a forgotten close must not wedge the JVM
            return t;
        });
        await(() -> fatal || (recoveredBookPublished && snapshots.isDrained()),
                "the depth subscribers to take the recovered book", () -> "not drained");
        inbound.start();
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
        try {
            if (started) {
                if (fatal) {
                    log.error("Shutting down after a fatal error: drains skipped at journaled seq {}, applied seq {}",
                            sequencer.journaledSeq(), agent.appliedSeq());
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
                agentRunner.close();

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

                if (!fatal) {
                    clean &= await(outbound::isDrained, "the outbound ring to drain", () -> "not drained");
                    clean &= await(snapshots::isDrained, "the snapshot ring to drain", () -> "not drained");
                }
                outbound.halt();
                snapshots.halt();
                log.info("Pipeline drained: journaled seq {}, applied seq {}",
                        sequencer.journaledSeq(), agent.appliedSeq());
            }
        } finally {
            snapshotWriter.close();
            journal.close();
        }
        return clean && !fatal;
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

    /**
     * The sequencer's exception policy: report to fail-stop, then rethrow so the Disruptor stops
     * the consumer with the slot unconsumed, even when the FailStop returns (tests). Returning
     * normally from handleEventException would make the processor skip the slot.
     */
    private static final class SequencerFailStop implements ExceptionHandler<OrderEvent> {
        private final FailStop failStop;

        SequencerFailStop(FailStop failStop) {
            this.failStop = failStop;
        }

        @Override
        public void handleEventException(Throwable ex, long sequence, OrderEvent event) {
            failStop.fatal("sequencer", ex);
            if (ex instanceof RuntimeException runtime) throw runtime;
            if (ex instanceof Error error) throw error;
            throw new IllegalStateException(ex);
        }

        @Override
        public void handleOnStartException(Throwable ex) {
            failStop.fatal("sequencer", ex);
        }

        @Override
        public void handleOnShutdownException(Throwable ex) {
            log.error("Sequencer failed on shutdown", ex);
        }
    }
}
