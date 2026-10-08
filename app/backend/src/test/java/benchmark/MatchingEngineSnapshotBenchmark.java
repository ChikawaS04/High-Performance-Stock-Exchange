package benchmark;

import engine.MatchingEngine;
import event.BookSnapshotEvent;
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
 * P6-5 — {@code snapshotInto} allocation validation.
 *
 * <p>Settles the question P4-1 deferred to Phase 6: does
 * {@link MatchingEngine#snapshotInto(BookSnapshotEvent, int)} allocate on the hot path, or does C2
 * escape analysis scalar-replace the map/deque iterators created by its enhanced-for loops once the
 * benchmark is warm? The headline is {@code gc.alloc.rate.norm} (B/op) under {@code -Djmh.prof=gc};
 * timing is reported but is noise on an unpinned laptop.
 *
 * <p>P6-1's ~165 B/op figure does not answer this — it covers {@code addOrder}, which never calls
 * {@code snapshotInto}. This is an engine-in-isolation benchmark: {@code MatchingEngine} constructed
 * directly, no Disruptor, no Netty, no {@code ExecutionListener} (the engine defaults to
 * {@code NO_OP}).
 *
 * <p><b>Setup strategy — build once, {@code Level.Trial}.</b> Unlike P6-1/P6-3, there is no
 * book-state drift to correct: {@code snapshotInto} is read-only against the book (it reads the two
 * {@code TreeMap}s and the guarded best-bid/ask getters, writes only the target carrier, and
 * self-stamps {@code target.timestamp = System.nanoTime()} — it never mutates book structure, the
 * trade list, or {@code IDGenerator}). So the book is populated once and read on every invocation.
 *
 * <p><b>Target reuse — the production pattern, and why it must be {@code @Setup}.</b> The
 * {@link BookSnapshotEvent} target is pre-allocated once and its arrays are reused every call,
 * exactly as the ring carrier is reused in production. Allocating the target per-invocation would
 * charge four {@code long[MAX_DEPTH_LEVELS]} arrays (~640 B) to the measurement and mask the ~0 B/op
 * signal entirely. Steady-state is the only correct choice.
 *
 * <p><b>Param axis.</b> {@code bookLevels} sweeps distinct price levels <em>per side</em> — the same
 * values as P6-1 so README rows align, but note the axis differs (P6-1's param was total resting
 * orders). Levels is {@code snapshotInto}'s natural cost axis: {@code fillSide} allocates one deque
 * iterator per level walked, so if C2 fails to scalar-replace, allocation should scale with levels.
 * Both sides are populated (bids strictly below asks, non-crossing) so both {@code fillSide} calls
 * are measured. {@code maxLevels} is fixed at the production cap
 * {@link BookSnapshotEvent#MAX_DEPTH_LEVELS}; because {@code snapshotInto} clamps to
 * {@code min(maxLevels, MAX_DEPTH_LEVELS)}, the walk caps at that many levels regardless of book
 * depth. The single sweep therefore crosses all three regimes as it grows — fewer levels than the
 * cap ({@code bookLevels < cap}), equal, and more levels exist than the cap — and allocation is
 * expected to flatten once {@code bookLevels} reaches the cap.
 *
 * <p><b>DCE.</b> The benchmark returns the target carrier, whose fields {@code snapshotInto}
 * overwrites each call, so JMH consumes a real data dependency and cannot dead-code the call.
 *
 * <p>Run on JDK 21 ({@code JAVA_HOME}), {@code mvn jmh:benchmark} (plain, then
 * {@code -Djmh.prof=gc}). Ids come from a benchmark-local counter, never {@code util.IDGenerator}
 * (locked decision 4).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class MatchingEngineSnapshotBenchmark {

    /** Distinct price levels populated per side. Same values as P6-1; axis is levels, not orders. */
    @Param({"1", "10", "100", "1000", "10000"})
    public int bookLevels;

    /** Orders resting at each distinct price level; mirrors P6-1's book shape (4 per level). */
    private static final int ORDERS_PER_LEVEL = 4;

    /** Positive participant id — {@code Order} rejects {@code participantID <= 0}. */
    private static final long PARTICIPANT_ID = 1L;

    /** Positive placeholder timestamp — {@code Order} rejects {@code timeStamp <= 0}; the value is
     *  never read by {@code snapshotInto}, which aggregates quantities and reads prices only. */
    private static final long ORDER_TS = 1L;

    /**
     * Non-crossing price bases (units of $0.0001, one-cent level spacing): every bid price stays
     * strictly below every ask price for the whole sweep. At {@code bookLevels = 10000} the top bid
     * is {@code BID_BASE + 9999 * 100 = 10_999_900}, well below {@code ASK_BASE = 20_000_000}, so no
     * order ever crosses and no trade or id is minted.
     */
    private static final long BID_BASE = 10_000_000L;
    private static final long ASK_BASE = 20_000_000L;

    /** Resting quantity per order (int, per the {@code Order} contract). Value is immaterial here. */
    private static final int ORDER_QTY = 10;

    private MatchingEngine engine;
    private BookSnapshotEvent target;
    private int maxLevels;

    @Setup(Level.Trial)
    public void setup() {
        engine = new MatchingEngine();

        // Benchmark-local id counter — never util.IDGenerator, whose AtomicLong is JVM-global.
        long nextId = 1L;

        // BUY side: bookLevels distinct price levels, each a deque of ORDERS_PER_LEVEL orders.
        // All bid prices are strictly below every ask price, so these rest without crossing.
        for (int level = 0; level < bookLevels; level++) {
            long price = BID_BASE + level * 100L;
            for (int k = 0; k < ORDERS_PER_LEVEL; k++) {
                engine.addOrder(new Order(nextId++, ORDER_TS, Side.BUY, ORDER_QTY, price, PARTICIPANT_ID));
            }
        }

        // SELL side: added after the bids; each sell price (>= ASK_BASE) is above the best bid,
        // so nothing crosses and the book holds bookLevels levels on each side.
        for (int level = 0; level < bookLevels; level++) {
            long price = ASK_BASE + level * 100L;
            for (int k = 0; k < ORDERS_PER_LEVEL; k++) {
                engine.addOrder(new Order(nextId++, ORDER_TS, Side.SELL, ORDER_QTY, price, PARTICIPANT_ID));
            }
        }

        // Pre-allocate the reused carrier ONCE — production reuse pattern. Per-invocation
        // construction would allocate its four long[MAX_DEPTH_LEVELS] arrays into the measurement.
        target = new BookSnapshotEvent();

        // Production cap: the real caller always snapshots the top MAX_DEPTH_LEVELS.
        maxLevels = BookSnapshotEvent.MAX_DEPTH_LEVELS;
    }

    /**
     * Reads the (static) book into the reused target. The engine is not mutated, so the same book is
     * measured on every invocation. Returns the target so JMH cannot eliminate the call.
     */
    @Benchmark
    public BookSnapshotEvent snapshotTopOfBook() {
        engine.snapshotInto(target, maxLevels);
        return target;
    }
}