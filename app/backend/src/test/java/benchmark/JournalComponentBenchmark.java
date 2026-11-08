package benchmark;

import codec.OrderEventCodec;
import engine.AppliedListener;
import engine.CommandApplier;
import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import journal.Journal;
import journal.OutboundRecorder;
import journal.RecordedPublication;
import model.Side;
import org.agrona.BitUtil;
import org.agrona.BufferUtil;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

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
 * P15-13 question A5, journal components on one thread, so {@code -prof gc} attributes their
 * allocation exactly.
 *
 * <ul>
 *   <li>{@link #encodeThenApply}: the engine agent's decode path, the production
 *       {@link CommandApplier} (decode into the reused carrier, seq check, dispatch, progress
 *       stores) with a no-op handler. Each op first encodes the next seq into the buffer, because the
 *       applier refuses a repeated seq; {@code CodecBenchmark.orderEncode} measures that part alone.</li>
 *   <li>{@link #recordEvent}: the production {@link OutboundRecorder#onEvent} (gap check, SBE encode,
 *       offer, back-pressure retry) against the recorded outbound stream of a live journal, the
 *       Archive its only subscriber. Sustained, so back-pressure from the Archive is included.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 5, jvmArgsAppend = {BenchSupport.ADD_EXPORTS, BenchSupport.MISC})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class JournalComponentBenchmark {

    @State(Scope.Benchmark)
    public static class Applier {
        final OrderEventCodec codec = new OrderEventCodec();
        final UnsafeBuffer buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(
                BitUtil.align(OrderEventCodec.MAX_ENCODED_LENGTH, BitUtil.CACHE_LINE_LENGTH), BitUtil.CACHE_LINE_LENGTH));
        OrderEvent source;
        CommandApplier applier;
        long seq;
        long sink;

        @Setup(Level.Trial)
        public void setUp() {
            source = BenchSupport.newOrder(1L, Side.BUY, 1L);
            applier = new CommandApplier(command -> sink += command.orderId, AppliedListener.NO_OP, 0L, 0L);
        }
    }

    @State(Scope.Benchmark)
    public static class Recorder {
        Path root;
        Journal journal;
        OutboundRecorder recorder;
        ExecutionEvent event;

        @Setup(Level.Trial)
        public void setUp() {
            root = BenchSupport.newRoot("recorder");
            journal = Journal.launch(BenchSupport.productionConfig(root, "busy-spin"));
            RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
            recorder = OutboundRecorder.open(journal, recorded, new BackoffIdleStrategy(), null);
            event = new ExecutionEvent();
            event.seq = 0L;
            event.eventType = ExecutionEventType.ORDER_FILLED;
            event.orderId = 2L;
            event.tradeId = 1L;
            event.price = BenchSupport.PRICE_UNITS;
            event.filledQuantity = BenchSupport.QTY;
            event.remainingQuantity = 0L;
            event.aggressorOrderId = 2L;
            event.passiveOrderId = 1L;
            event.aggressorSide = Side.SELL;
            event.aggressorParticipantId = 1L;
            event.passiveParticipantId = 1L;
            event.businessDate = -1;
            event.timestamp = 1L;
            event.commandSeq = 2L;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            try {
                BenchSupport.report("recorder recorded=" + recorder.recordedSeq() + " archive: "
                        + BenchSupport.archiveStats(journal.config().archiveDir()));
                journal.close();
            } finally {
                BenchSupport.deleteTree(root);
            }
        }
    }

    @Benchmark
    public long encodeThenApply(Applier s) {
        final long seq = ++s.seq;
        s.source.orderId = seq;
        s.codec.encode(s.source, seq, s.buffer, 0);
        s.applier.apply(s.buffer, 0, seq);
        return s.sink;
    }

    @Benchmark
    public long recordEvent(Recorder s) {
        final ExecutionEvent e = s.event;
        e.seq++;
        e.commandSeq++;
        e.timestamp++;
        s.recorder.onEvent(e, e.seq, true);
        return s.recorder.recordedSeq();
    }
}
