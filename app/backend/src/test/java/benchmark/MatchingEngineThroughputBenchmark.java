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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * SRS §6.3 #1 — "matching engine throughput: orders per second in isolation."
 *
 * <p><b>What is measured.</b> Sustained {@code addOrder} throughput against a book held in
 * steady state by a two-phase workload: one passive order that rests, then one aggressive
 * order that exactly crosses and clears it. Each {@code @Benchmark} invocation submits exactly
 * one order, so the reported ops/sec <i>is</i> orders/sec — half passive, half aggressive. The
 * engine is driven <b>directly</b>: no Disruptor, no Netty, no gateway, nothing on the measured
 * path but the engine itself (§3.3 in isolation).
 *
 * <p><b>Order-mix decision: steady-state rest/cross, not pure inserts.</b> {@code addOrder}
 * mutates the book, so under {@code Mode.Throughput} a workload runs for the full iteration
 * without reset and any book growth compounds. Two candidates were weighed:
 * <ul>
 *   <li><b>Pure non-crossing inserts</b> grow the book to millions of orders within a single
 *       iteration. Because insert is O(log N), per-op cost <i>drifts upward</i> as the book
 *       grows (P6-1 measured 110&rarr;147 ns across depth 1&rarr;10<sup>4</sup>), so the
 *       throughput figure degrades into an average over an arbitrary depth range set by machine
 *       speed — the very book-state-drift the depth benchmark stayed non-crossing to avoid.</li>
 *   <li><b>Steady-state rest/cross</b> (chosen) keeps the book bounded at depth 0&ndash;1, so
 *       per-op <i>work is stable</i> for the whole iteration — no depth drift — and it exercises
 *       the actual matching path, which is what §6.3 #1 literally asks.</li>
 * </ul>
 * Pure-insert throughput is not measured separately: it is approximable as the reciprocal of
 * P6-1's insert latency, and measuring it directly would only reintroduce the drift artifact.
 *
 * <p><b>Caveat — the engine accumulates trades, so memory still grows within an iteration.</b>
 * The book stays bounded here, but every cross appends a {@code Trade} to the engine's
 * {@code List<Trade>} (read by {@code getTrades()}, never cleared). Over a one-second iteration
 * that list grows by one entry per aggressive order. This is a <i>constant</i> per-op cost
 * (one {@code Trade} per cross), not a drifting one, and it is bounded <i>across</i> iterations
 * by the fresh {@code MatchingEngine} installed in {@code @Setup(Level.Iteration)}. It also
 * corroborates the P6-1 allocation finding: §5.1's zero-allocation guarantee covers the
 * ring-buffer transport, not the engine's book/trade structures.
 *
 * <p><b>Cross mechanics.</b> A resting BUY at {@code PRICE_UNITS} for {@code QTY} lands in an
 * empty book (no asks). The following SELL at the same price and quantity satisfies
 * {@code matchSell}'s crossing condition (best bid &ge; sell price), fills exactly {@code QTY}
 * against {@code QTY} at the passive resting price, evicts the resting BUY from its queue,
 * {@code openOrders}, and price level, and — being fully filled — never rests itself. The book
 * returns to empty, ready for the next pair.
 *
 * <p><b>Ids.</b> Order ids come from a benchmark-local counter, never {@code util.IDGenerator}:
 * that class holds static {@code AtomicLong}s global to the JVM and already advanced by whatever
 * ran before, so seeding locally keeps setup deterministic and independent of JVM history.
 * ({@code IDGenerator} still mints the trade id inside the engine on each cross; that is engine
 * behaviour under measurement, not benchmark setup.)
 *
 * <p><b>Listener.</b> None is attached, so the engine uses its {@code ExecutionListener.NO_OP}
 * default and no execution escapes toward a ring buffer.
 *
 * <p>Run with JDK 21 on {@code JAVA_HOME}:
 * {@code mvn jmh:benchmark -Djmh.benchmarks=MatchingEngineThroughput}
 * (add {@code -Djmh.f=3} to firm up the mean with cross-JVM variance).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class MatchingEngineThroughputBenchmark {

    /** Single price both sides trade at, in units of $0.0001 ($150.00). The passive resting price on a fill. */
    private static final long PRICE_UNITS = 1_500_000L;

    /** Quantity on every order; the exact-cross pair uses equal sizes so both fully fill. */
    private static final int QTY = 10;

    /** The Order constructor rejects participant ids <= 0. */
    private static final long PARTICIPANT_ID = 1L;

    private MatchingEngine engine;

    /** Benchmark-local id source — deliberately not util.IDGenerator (see class javadoc). */
    private long nextOrderId;

    /** true => submit the resting BUY next; false => submit the crossing SELL next. */
    private boolean restNext;

    /**
     * Fresh engine per iteration. This is what bounds growth across iterations: it drops the
     * previous iteration's accumulated trade list and any residual book state, and re-seeds the
     * id counter and phase toggle so every timed iteration starts from an identical empty book.
     */
    @Setup(Level.Iteration)
    public void reset() {
        engine = new MatchingEngine();   // no ExecutionListener -> NO_OP default
        nextOrderId = 1L;
        restNext = true;
    }

    /**
     * The measured operation: one order submission, alternating passive rest and aggressive
     * exact-cross so the book oscillates between empty and a single resting order.
     *
     * <p>Returns the {@link Order} so JMH consumes the result and cannot dead-code-eliminate the
     * call. The engine mutation (and the trade append on a cross) is a side effect on
     * {@code engine} and is not eliminable regardless; returning the value keeps the intent
     * explicit.
     */
    @Benchmark
    public Order submitOrder() {
        // BUY rests into an empty book; SELL exact-crosses the resting BUY and clears it.
        Order order = newOrder(restNext ? Side.BUY : Side.SELL);
        engine.addOrder(order);
        restNext = !restNext;
        return order;
    }

    /** Order(orderID, timeStamp, side, quantity, price, participantID) — quantity before price. */
    private Order newOrder(Side side) {
        return new Order(
                nextOrderId++,
                nextOrderId,          // timestamp: monotonic filler; unused on this path
                side,
                QTY,
                PRICE_UNITS,
                PARTICIPANT_ID
        );
    }
}