package benchmark;

import engine.ExecutionListener;
import engine.MatchingEngine;
import model.Order;
import model.Side;
import model.TimeInForce;
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
 * P6-3 — fill-walk (crossing-order) benchmark. SRS §6.3: matching cost when a single
 * aggressive order walks {@code N} price levels. This closes the gap P6-1 explicitly leaves
 * open — P6-1 varied resting depth but measured a <em>non-crossing insert</em>; this measures
 * the walk itself.
 *
 * <h2>Why the engine is method-local (setup-strategy decision)</h2>
 * A crossing order drains the levels it walks, so the measured operation consumes the very
 * book the {@code @Param} sets up. The two setup strategies the guide floated both fail here:
 * <ul>
 *   <li><b>{@code @Setup(Level.Invocation)}</b> rebuilds N levels between timed ops, but JMH's
 *       gc profiler brackets the whole measurement iteration, so that fixture's ~165 B/level
 *       (P6-1) allocation is counted into {@code gc.alloc.rate.norm} — poisoning the
 *       allocation pass, which is the trustworthy measurement in this project. Its timing is
 *       also overhead-limited: a 1–100-level walk is 0.1–10 µs, under the ~1 ms floor where
 *       per-invocation timestamping is negligible.</li>
 *   <li><b>{@code @OperationsPerInvocation} with a pre-built book</b> drains completely on the
 *       first invocation; JMH then loops the method into an empty book for the rest of the
 *       time budget, measuring nothing.</li>
 * </ul>
 * Instead, each {@code @Benchmark} allocates a <em>fresh local</em> {@link MatchingEngine},
 * builds the book, (walks it), and discards the engine. Because the engine never persists
 * across invocations there is no shared state to drain and no JMH fixture trap; the gc
 * profiler sees exactly the method's own allocation.
 *
 * <h2>Reading the numbers (subtraction methodology, per P6-2)</h2>
 * The build is inside the timed region, so no single method reports a pure walk. The finding
 * is the <b>difference</b> {@code fillWalkAcrossLevels − buildLevelsOnly} and its slope vs N:
 * that isolates the walk's own cost. Allocation differences are precise (P6-1/P6-2 error was
 * ±1–4 B/op); timing differences are honest but wider (differencing two comparable O(N)
 * magnitudes). Scaling is technically O(N log N) — the {@link java.util.TreeMap}
 * {@code firstEntry}/{@code pollFirstEntry} per level — reported as "roughly linear" unless
 * the data resolves the log factor.
 *
 * <h2>Book shape</h2>
 * {@code N} ask levels, one resting order (qty 1) each, at prices {@code BASE_PRICE .. BASE_PRICE+N-1}.
 * One aggressor BUY priced at the top ask (so {@code askPrice <= buyPrice} at every level) with
 * qty {@code N} — best-first it fills one order per level, evicts each, and is exactly consumed
 * at level N, so nothing rests. Ids come from a method-local counter, never {@code util.IDGenerator}
 * (its {@code AtomicLong}s are JVM-global). No {@code ExecutionListener} is attached — the engine
 * defaults to {@code NO_OP}, so no execution escapes.
 *
 * <h2>FOK availability probe (Phase 14 P14-11)</h2>
 * {@link #fokProbeAcrossLevels()} measures {@code canFillCompletely} (SRS §3.3, D6): the
 * pre-trade walk a FOK order makes over the opposite side, summing eligible resting quantity
 * under the shared {@code marketable} predicate. It is driven through the only public entry,
 * {@code addOrder}, with a FOK aggressor deliberately sized one unit beyond the book's total
 * resting quantity, so the probe walks all {@code restingLevels} levels, finds the liquidity
 * short, and the order expires with the book, the peg pools and {@code openOrders} left
 * untouched — the FOK-fail path is read-only: it reports {@code onExpired} and returns before
 * any match, rest or {@code expire()}. Because nothing is mutated, the book and the aggressor
 * are built once in {@code @Setup(Level.Trial)} and reused on every invocation, the read-only
 * steady-state pattern {@code MatchingEngineSnapshotBenchmark} uses, so no per-invocation
 * rebuild or per-op allocation enters the timed region and {@code gc.alloc.rate.norm} under
 * {@code -Djmh.prof=gc} is a direct read of the probe's own iterator allocation (the
 * {@code TreeMap} entrySet and {@code ArrayDeque} iterators the enhanced-for loops create): the
 * scalar-replacement question, answered directly rather than by subtraction. Dead-code
 * elimination is defended by a counting listener whose {@code onExpired} increments a counter
 * the benchmark returns, because {@code addOrder} is void and returning the reused order would
 * not protect the call. The aggressor BUY is priced at the top ask so it is marketable at every
 * level, and its reused {@code OPEN} status is never changed (the fail path does not call
 * {@code expire()}), so it is safely re-submittable. The probe book has an empty bid side, so
 * {@code mid()} is {@code Prices.NA} and the peg-pool branch is skipped: this is the pure lit
 * walk. The timing slope and the allocation across {@code restingLevels} are the findings; no
 * Phase 6 baseline exists for the probe, so it is a first measurement.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Benchmark)
public class MatchingEngineFillWalkBenchmark {

    /** Lowest resting ask price, in units of $0.0001 ($1000.00). Levels stack upward from here. */
    private static final long BASE_PRICE = 10_000_000L;

    /** One order per level; aggressor qty == level count, so each level is fully cleared. */
    private static final int RESTING_QTY = 1;

    /** Order ctor rejects participantID <= 0; 1L is the sentinel used elsewhere. */
    private static final long PARTICIPANT_ID = 1L;

    /**
     * Aggressor id — distinct from the 1..N resting ids so nothing aliases in the book.
     * (The aggressor never enters {@code openOrders} anyway; it fully fills and rests nothing.)
     */
    private static final long AGGRESSOR_ID = Long.MAX_VALUE;

    /** Levels the aggressor walks. Mirrors P6-1's sweep so insert- and fill-walk-scaling line up. */
    @Param({"1", "10", "100", "1000", "10000"})
    public int restingLevels;

    /**
     * The probe's fixed book, built once per trial and probed read-only on every invocation
     * (see the FOK-probe section of the class javadoc). Separate from the fresh local engines
     * the walk benchmarks build, so the two measurements never share state.
     */
    private MatchingEngine probeEngine;

    /**
     * A FOK BUY sized one unit beyond the book's total resting quantity and priced at the top
     * ask so it is marketable at every level. Reused across invocations: the FOK-fail path is
     * read-only and never calls {@code expire()}, so its status stays OPEN and it stays
     * re-submittable.
     */
    private Order probeOrder;

    /** Counts {@code onExpired} callbacks; its running value is returned to defeat DCE. */
    private CountingExpiryListener probeListener;

    /**
     * Builds a fresh engine with {@code levels} single-order ask levels, non-crossing (empty
     * bid side, so each SELL rests). Ids are 1..levels, local to this call. Constant work per
     * level plus the O(log) TreeMap insert.
     */
    private MatchingEngine buildBook(int levels) {
        MatchingEngine engine = new MatchingEngine();
        long id = 1L;
        for (int i = 0; i < levels; i++) {
            long price = BASE_PRICE + i * 100L;
            // Order(orderID, timeStamp, side, quantity, price, participantID) — quantity before price.
            // timeStamp = id (a positive long) avoids a System.nanoTime() syscall inside the timed loop.
            engine.addOrder(new Order(id, id, Side.SELL, RESTING_QTY, price, PARTICIPANT_ID));
            id++;
        }
        return engine;
    }

    /**
     * Baseline: build N levels only. Timed region = build. Subtracting this from
     * {@link #fillWalkAcrossLevels()} at the same N isolates the walk's cost.
     */
    @Benchmark
    public MatchingEngine buildLevelsOnly() {
        return buildBook(restingLevels);
    }

    /**
     * Build N levels, then one aggressor BUY that walks and drains all N. Timed region =
     * build + walk. The returned (now-empty) engine still holds the N trades, so JMH can't
     * eliminate the fill work.
     */
    @Benchmark
    public MatchingEngine fillWalkAcrossLevels() {
        MatchingEngine engine = buildBook(restingLevels);
        long aggressorPrice = BASE_PRICE + (restingLevels - 1) * 100L;   // == top ask: askPrice <= buyPrice everywhere
        engine.addOrder(new Order(
                AGGRESSOR_ID, AGGRESSOR_ID, Side.BUY, restingLevels, aggressorPrice, PARTICIPANT_ID));
        return engine;
    }

    /**
     * Builds the probe's fixed book once per trial: {@code restingLevels} single-order ask
     * levels with an empty bid side (so {@code mid()} is {@code Prices.NA} and the probe is the
     * pure lit walk), a counting listener, and the reused FOK aggressor. {@code Level.Trial},
     * not per-invocation, is correct precisely because a failing FOK mutates nothing, so the
     * same book is measured on every invocation with no rebuild in the timed region.
     */
    @Setup(Level.Trial)
    public void buildProbeBook() {
        probeEngine = buildBook(restingLevels);
        probeListener = new CountingExpiryListener();
        probeEngine.setExecutionListener(probeListener);

        long topAsk = BASE_PRICE + (restingLevels - 1) * 100L;   // marketable at every level
        // Quantity one beyond the book's total resting (restingLevels * RESTING_QTY) forces the
        // probe to walk every level, come up one unit short, and expire the order untouched.
        probeOrder = new Order(
                AGGRESSOR_ID, AGGRESSOR_ID, Side.BUY, restingLevels + 1, topAsk, PARTICIPANT_ID,
                TimeInForce.FOK);
    }

    /**
     * The measured operation: one FOK {@code addOrder} whose pre-trade probe walks all
     * {@code restingLevels} levels and then expires the order, leaving the book, the peg pools
     * and {@code openOrders} unchanged. The reused engine is therefore identical on every
     * invocation. Returns the listener's running expiry count so JMH consumes a real data
     * dependency and cannot eliminate the void call.
     */
    @Benchmark
    public long fokProbeAcrossLevels() {
        probeEngine.addOrder(probeOrder);
        return probeListener.expiries;
    }

    /**
     * Minimal {@link ExecutionListener} for the probe benchmark: counts {@code onExpired} and
     * does nothing else. Allocation-free per call, and {@code onFill}/{@code onAccepted} never
     * fire on the FOK-fail path, so it adds one trivial increment per op and nothing to the gc
     * profile.
     */
    private static final class CountingExpiryListener implements ExecutionListener {
        long expiries;
        @Override public void onFill(long aggressorOrderId, long passiveOrderId, long tradeId,
                                     long price, long filledQuantity, long aggressorRemainingQuantity) { }
        @Override public void onAccepted(long orderId, long price, long remainingQuantity) { }
        @Override public void onExpired(long orderId, long expiredQuantity) { expiries++; }
    }
}
