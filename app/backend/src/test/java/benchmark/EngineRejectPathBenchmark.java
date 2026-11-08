package benchmark;

import com.lmax.disruptor.RingBuffer;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import engine.Sessions;
import event.BookSnapshotEvent;
import event.BookSnapshotEventFactory;
import event.ExecutionEvent;
import event.ExecutionEventFactory;
import event.OrderEvent;
import model.Side;

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
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * P15-13 question A5, the engine side of a journaled command, on one thread so {@code -prof gc}
 * attributes allocation to exactly these calls. Carries the P15-4 finding: a cancel of an unknown
 * order through the real handler allocated about 88 bytes per command.
 *
 * The handler publishes onto rings that have no consumer and no gating sequence, so a claim never
 * waits and no consumer thread allocates; the BOOK snapshot after every command is included, as in
 * production. The session is open (restore path, nothing published).
 *
 * <ul>
 *   <li>{@link #unknownCancelThroughHandler}: the whole P15-4 path.</li>
 *   <li>{@link #cancelOrderUnknown}: only {@code MatchingEngine.cancelOrder} on an id that is not
 *       resting (outside the {@code Long} cache, as a real ClOrdID is).</li>
 *   <li>{@link #snapshotIntoEmpty}: only {@code snapshotInto} on the empty book.</li>
 *   <li>{@link #restCrossThroughHandler}: the Phase 14 workload through the handler, for
 *       comparison: the NEW path builds an {@code Order} and book structures by design.</li>
 * </ul>
 * The handler's publish path is the remainder of the first line after the next two.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 5, jvmArgsAppend = {BenchSupport.ADD_EXPORTS, BenchSupport.MISC})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class EngineRejectPathBenchmark {

    /** Not resting, and outside the boxed {@code Long} cache. */
    private static final long UNKNOWN_ID = 999_999_999L;

    private MatchingEngine engine;
    private MatchingEngineHandler handler;
    private OrderEvent cancel;
    private OrderEvent order;
    private BookSnapshotEvent snapshot;
    private long seq;
    private long nextOrderId;
    private boolean restNext;

    @Setup(Level.Iteration)
    public void setUp() {
        engine = new MatchingEngine();
        RingBuffer<ExecutionEvent> outbound = RingBuffer.createSingleProducer(new ExecutionEventFactory(), 1024);
        RingBuffer<BookSnapshotEvent> snapshots = RingBuffer.createSingleProducer(new BookSnapshotEventFactory(), 1024);
        handler = new MatchingEngineHandler(engine, outbound, snapshots);
        engine.setExecutionListener(handler);
        Sessions.open(handler);
        cancel = BenchSupport.cancel(1L, UNKNOWN_ID, 1L);
        order = BenchSupport.newOrder(1L, Side.BUY, 1L);
        snapshot = new BookSnapshotEvent();
        seq = 0;
        nextOrderId = 1;
        restNext = true;
    }

    @Benchmark
    public long unknownCancelThroughHandler() {
        handler.onCommand(cancel, ++seq);
        return handler.getLastSeq();
    }

    @Benchmark
    public boolean cancelOrderUnknown() {
        return engine.cancelOrder(UNKNOWN_ID);
    }

    @Benchmark
    public long snapshotIntoEmpty() {
        engine.snapshotInto(snapshot, BookSnapshotEvent.MAX_DEPTH_LEVELS);
        return snapshot.bestBid;
    }

    @Benchmark
    public long restCrossThroughHandler() {
        BenchSupport.writeNewOrder(order, nextOrderId++, restNext ? Side.BUY : Side.SELL, seq + 1);
        restNext = !restNext;
        handler.onCommand(order, ++seq);
        return handler.getLastSeq();
    }
}
