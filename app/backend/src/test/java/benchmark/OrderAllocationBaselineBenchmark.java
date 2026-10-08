package benchmark;

import model.Order;
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
 * Allocation baseline flagged in P6-1: constructs an {@link Order} and does nothing else.
 *
 * <p><b>Why this exists.</b> P6-1's book-depth benchmark constructs an {@code Order} <i>inside</i>
 * its measured method, so its ~165 B/op {@code gc.alloc.rate.norm} figure includes both the
 * engine's per-insert allocation (a {@code HashMap.Node}, a {@code TreeMap.Entry}, and the boxed
 * {@code Long} keys the book's collections force) <i>and</i> the cost of building the {@code Order}
 * itself. That conflation is benchmark scaffolding, not engine cost. This benchmark isolates the
 * scaffolding: run it under {@code -Djmh.prof=gc} and its {@code gc.alloc.rate.norm} is the size
 * of one {@code Order}. Subtract that from P6-1's 165 B/op to get the engine's true per-insert
 * allocation.
 *
 * <p><b>Mode is deliberately {@code AverageTime} + nanoseconds</b>, matching
 * {@code MatchingEngineDepthBenchmark} rather than the sibling throughput benchmark, so the
 * allocation numbers subtract apples-to-apples (per-op allocation is mode-independent, but
 * keeping the harness identical removes any doubt) and the construction <i>time</i> is directly
 * comparable to the depth benchmark's insert time.
 *
 * <p><b>Ids.</b> Benchmark-local counter, never {@code util.IDGenerator} (static JVM-global
 * {@code AtomicLong}s). No engine, no listener, no collections — this touches nothing but the
 * {@code Order} constructor and its fail-fast validation.
 *
 * <p>Run with JDK 21 on {@code JAVA_HOME}:
 * {@code mvn jmh:benchmark -Djmh.prof=gc -Djmh.benchmarks=OrderAllocationBaseline}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class OrderAllocationBaselineBenchmark {

    /** Same constants as the depth/throughput benchmarks, so the constructed Order is identical. */
    private static final long PRICE_UNITS = 1_500_000L;
    private static final int QTY = 10;
    private static final long PARTICIPANT_ID = 1L;

    /** Benchmark-local id source — deliberately not util.IDGenerator. */
    private long nextOrderId;

    @Setup(Level.Iteration)
    public void reset() {
        nextOrderId = 1L;
    }

    /**
     * The measured operation: construct one {@link Order}, nothing else. Returned so JMH consumes
     * it and cannot dead-code-eliminate the allocation.
     *
     * <p>Order(orderID, timeStamp, side, quantity, price, participantID) — quantity before price.
     */
    @Benchmark
    public Order constructOrder() {
        return new Order(
                nextOrderId++,
                nextOrderId,          // timestamp: monotonic filler
                Side.BUY,
                QTY,
                PRICE_UNITS,
                PARTICIPANT_ID
        );
    }
}