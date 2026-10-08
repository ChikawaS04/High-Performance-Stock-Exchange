package benchmark;

import engine.MatchingEngine;
import model.Order;
import model.Side;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * SRS §6.3 — "book depth impact: matching latency as a function of resting order count."
 *
 * <p><b>What is measured.</b> A single non-crossing {@code addOrder} into a book already
 * holding {@code restingOrders} orders. That is the O(log N) {@code TreeMap} insert plus the
 * {@code openOrders} {@code HashMap} registration — the cost that should scale with depth.
 * The engine is driven <b>directly</b>: no Disruptor, no Netty, no gateway, nothing on the
 * measured path but the engine itself (§3.3 in isolation).
 *
 * <p><b>Why non-crossing, and what this deliberately does not measure.</b> {@code addOrder}
 * mutates the book, so a Matchingenginethroughputbenchmark that crosses would consume the very resting depth the
 * {@code @Param} is meant to hold constant — after a few thousand invocations the book would
 * no longer be at depth N and the number would be meaningless. Rebuilding per invocation
 * ({@code Level.Invocation}) fixes the depth but adds JMH's documented per-invocation timing
 * overhead, which at these timescales can dominate the operation being measured. So this
 * Matchingenginethroughputbenchmark inserts without crossing: the book grows by exactly one order per invocation,
 * negligible against N, and depth stays effectively constant across an iteration.
 * <b>Consequence: this measures resting-insert scaling, not fill-walk scaling.</b> The cost of
 * an aggressive order walking several price levels is a separate Matchingenginethroughputbenchmark with its own setup
 * strategy — it is not covered here, and this number must not be quoted as if it were.
 *
 * <p><b>Book construction.</b> Resting orders are BUY (bids) spread across
 * {@code restingOrders / ORDERS_PER_LEVEL} distinct price levels, so the {@code TreeMap} holds
 * many levels rather than one deep {@code ArrayDeque} — depth in the tree is what varies with
 * the parameter. The measured order is a BUY priced strictly below every resting bid and below
 * any ask (there are none), so it can never cross; it lands on its own level near the bottom of
 * the book, which is the honest worst case for a {@code TreeMap} insert.
 *
 * <p><b>Ids.</b> Order ids come from a Matchingenginethroughputbenchmark-local counter, never {@code util.IDGenerator}:
 * that class holds static {@code AtomicLong}s that are global to the JVM and already advanced
 * by whatever ran before, so setup here stays deterministic and independent of JVM history.
 * ({@code IDGenerator} still mints trade ids inside the engine on the crossing path, which this
 * Matchingenginethroughputbenchmark does not exercise.)
 *
 * <p><b>Listener.</b> None is attached, so the engine uses its {@code ExecutionListener.NO_OP}
 * default and no execution ever escapes toward a ring buffer.
 *
 * <p>Run with JDK 21 on {@code JAVA_HOME}: {@code mvn jmh:Matchingenginethroughputbenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class MatchingEngineDepthBenchmark {

    /** Resting orders stacked at each distinct price level. */
    private static final int ORDERS_PER_LEVEL = 4;

    /** Highest resting bid, in units of $0.0001 ($150.00). Levels descend from here. */
    private static final long TOP_BID_UNITS = 1_500_000L;

    /** Quantity on every order; matching cost is independent of it on the non-crossing path. */
    private static final int QTY = 10;

    /** The engine constructor rejects participant ids <= 0. */
    private static final long PARTICIPANT_ID = 1L;

    /** Depth of the resting book the measured insert lands into. */
    @Param({"1", "10", "100", "1000", "10000"})
    public int restingOrders;

    private MatchingEngine engine;

    /** Benchmark-local id source — deliberately not util.IDGenerator (see class javadoc). */
    private long nextOrderId;

    /**
     * Price for the measured insert: strictly below every resting bid, so it never crosses.
     * Computed once in setup rather than per invocation.
     */
    private long insertPriceUnits;

    /**
     * Rebuild the book once per iteration. Per-iteration (not per-invocation) is the point:
     * it re-establishes the exact target depth between timed runs without putting setup cost
     * inside the measurement, while the one-order-per-invocation growth within an iteration
     * stays negligible against N.
     */
    @Setup(Level.Iteration)
    public void buildBook() {
        engine = new MatchingEngine();   // no ExecutionListener -> NO_OP default
        nextOrderId = 1L;

        int levels = Math.max(1, restingOrders / ORDERS_PER_LEVEL);

        for (int i = 0; i < restingOrders; i++) {
            long priceUnits = TOP_BID_UNITS - (i % levels) * 100L;   // descending distinct one-cent levels
            engine.addOrder(newBid(priceUnits));
        }

        // Strictly below the lowest resting bid, so the measured order cannot cross and
        // instead rests on a fresh level of its own.
        insertPriceUnits = TOP_BID_UNITS - levels * 100L - 100L;
    }

    /**
     * The measured operation: one non-crossing resting insert into a book of depth N.
     *
     * <p>Returns the {@link Order} so JMH consumes the result and cannot dead-code-eliminate
     * the call. The engine mutation is a side effect on {@code engine} and is not eliminable
     * regardless, but returning the value keeps the intent explicit.
     */
    @Benchmark
    public Order insertRestingOrder() {
        Order order = newBid(insertPriceUnits);
        engine.addOrder(order);
        return order;
    }

    /** Order(orderID, timeStamp, side, quantity, price, participantID) — quantity before price. */
    private Order newBid(long priceUnits) {
        return new Order(
                nextOrderId++,
                nextOrderId,          // timestamp: monotonic filler; unused on this path
                Side.BUY,
                QTY,
                priceUnits,
                PARTICIPANT_ID
        );
    }
}