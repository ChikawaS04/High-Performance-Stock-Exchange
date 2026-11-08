package benchmark;

import engine.CommandHandler;
import engine.EngineAgent;
import event.OrderEvent;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import journal.Journal;
import journal.RecordedPublication;
import journal.RecordingPositionGate;
import journal.Sequencer;
import model.Side;
import org.agrona.concurrent.BackoffIdleStrategy;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * P15-13 question A2: what sequencing and journaling one command costs, with the embedded media
 * driver and Archive in process on production journal defaults. The benchmark thread calls the
 * production {@link Sequencer#onEvent} directly (seq, D1 clamp, SBE encode, offer, back-pressure
 * retry), so nothing but the journal is on the measured path.
 *
 * <ul>
 *   <li>{@link #sequenceAndAwaitRecorded}: one command in flight. Sequence it, then spin until the
 *       Archive's recording position passes the publication position after it (the D11 gate
 *       condition: the earliest instant the engine may process it).</li>
 *   <li>{@link #sequenceRecordAndApply}: as above, then the production {@link EngineAgent} polls it
 *       through the gate and decodes it (no-op handler), on this thread.</li>
 *   <li>{@link #sequenceSustained}: offers without waiting. The Archive is the only subscriber, so
 *       once the publication window (half a 256 KiB term) is full the rate is the Archive's write
 *       rate, through back-pressure.</li>
 * </ul>
 * Each fork starts from an empty archive; the recording grows across its iterations (segment
 * files are preallocated at 16 MiB, so a roll-over cost lands inside some ops).
 */
@Fork(value = 5, jvmArgsAppend = {BenchSupport.ADD_EXPORTS, BenchSupport.MISC})
@Warmup(iterations = 20, time = 1)
@Measurement(iterations = 10, time = 1)
public class JournalCommandBenchmark {

    /** A journal with the sequencer's recorded publication; the Archive is its only subscriber. */
    @State(Scope.Benchmark)
    public static class RecordOnly {
        Path root;
        Journal journal;
        Sequencer sequencer;
        ExclusivePublication publication;
        RecordingPositionGate gate;
        OrderEvent slot;
        long nextId = 1;
        long nextTimestamp = 1;

        @Setup(Level.Trial)
        public void setUp() {
            root = BenchSupport.newRoot("journal");
            journal = Journal.launch(BenchSupport.productionConfig(root, "busy-spin"));
            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            sequencer = Sequencer.open(journal, recorded, new BackoffIdleStrategy());
            publication = recorded.publication();
            gate = journal.recordingPositionGate(recorded);
            slot = BenchSupport.newOrder(0L, Side.BUY, 0L);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            try {
                BenchSupport.report("journal sequenced=" + sequencer.lastSeq() + " archive: "
                        + BenchSupport.archiveStats(journal.config().archiveDir()));
                journal.close();
            } finally {
                BenchSupport.deleteTree(root);
            }
        }
    }

    /** As {@link RecordOnly}, plus the engine agent's image and a no-op handler. */
    @State(Scope.Benchmark)
    public static class RecordAndApply {
        Path root;
        Journal journal;
        Sequencer sequencer;
        EngineAgent agent;
        OrderEvent slot;
        long nextId = 1;
        long nextTimestamp = 1;

        @Setup(Level.Trial)
        public void setUp() {
            root = BenchSupport.newRoot("journal-apply");
            journal = Journal.launch(BenchSupport.productionConfig(root, "busy-spin"));
            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            sequencer = Sequencer.open(journal, recorded, new BackoffIdleStrategy());
            Image image = journal.subscribe(recorded);
            CommandHandler noOp = command -> { };
            agent = new EngineAgent(image, journal.recordingPositionGate(recorded), noOp, recorded.joinPosition(), 0L);
            agent.onStart();
            slot = BenchSupport.newOrder(0L, Side.BUY, 0L);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            try {
                BenchSupport.report("journal-apply sequenced=" + sequencer.lastSeq() + " applied=" + agent.appliedSeq()
                        + " archive: " + BenchSupport.archiveStats(journal.config().archiveDir()));
                journal.close();
            } finally {
                BenchSupport.deleteTree(root);
            }
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Measurement(iterations = 10, time = 2)
    public long sequenceSustained(RecordOnly s) {
        s.slot.orderId = s.nextId++;
        s.slot.timestamp = s.nextTimestamp++;
        s.sequencer.onEvent(s.slot, 0L, true);
        return s.sequencer.lastSeq();
    }

    @Benchmark
    @BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long sequenceAndAwaitRecorded(RecordOnly s) {
        s.slot.orderId = s.nextId++;
        s.slot.timestamp = s.nextTimestamp++;
        s.sequencer.onEvent(s.slot, 0L, true);
        final long target = s.publication.position();
        final RecordingPositionGate gate = s.gate;
        long limit;
        int spins = 0;
        long deadline = 0;
        while ((limit = gate.limit()) < target) {
            Thread.onSpinWait();
            if (++spins == (1 << 20)) {   // checked rarely, so the clock read stays off the common path
                spins = 0;
                long now = System.nanoTime();
                if (deadline == 0) {
                    deadline = now + BenchSupport.TIMEOUT_MS * 1_000_000L;
                } else if (now > deadline) {
                    throw new IllegalStateException("recording stuck at " + limit + ", expected " + target);
                }
            }
        }
        return limit;
    }

    @Benchmark
    @BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long sequenceRecordAndApply(RecordAndApply s) {
        s.slot.orderId = s.nextId++;
        s.slot.timestamp = s.nextTimestamp++;
        s.sequencer.onEvent(s.slot, 0L, true);
        final long seq = s.sequencer.lastSeq();
        final EngineAgent agent = s.agent;
        int polls = 0;
        long deadline = 0;
        while (agent.appliedSeq() < seq) {
            agent.doWork();
            if (++polls == (1 << 20)) {
                polls = 0;
                long now = System.nanoTime();
                if (deadline == 0) {
                    deadline = now + BenchSupport.TIMEOUT_MS * 1_000_000L;
                } else if (now > deadline) {
                    throw new IllegalStateException("agent stuck at " + agent.appliedSeq() + ", expected " + seq);
                }
            }
        }
        return seq;
    }
}
