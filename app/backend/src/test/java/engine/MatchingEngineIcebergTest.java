package engine;

import engine.CapturingExecutionListener.Kind;
import engine.CapturingExecutionListener.Observed;
import event.BookSnapshotEvent;
import model.Order;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 14-7 engine semantics for iceberg orders (SRS 3.3, D7 / D8). Pure engine, no ring
 * buffer: a CapturingExecutionListener observes onFill / onAccepted / onExpired synchronously.
 *
 * The book is seeded while the engine still holds the default NO_OP listener, so the resting
 * orders' own acknowledgements are swallowed and the capturer records ONLY the aggressor under
 * test. Order ids are local literals (never IDGenerator); trade-id assertions are property
 * checks (&gt; 0), per the project's test discipline.
 */
class MatchingEngineIcebergTest {

    private static final long P100 = 1_000_000L;   // $100.00, on the one-cent tick
    private static final long P101 = 1_010_000L;   // $101.00

    private MatchingEngine engine;
    private CapturingExecutionListener cap;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        cap = new CapturingExecutionListener();
    }

    // --- order factories (decision J: one local helper per file absorbs constructor widening) ---

    private static Order iceberg(long id, Side side, int qty, long price, int peak) {
        return new Order(id, nextTs(), side, qty, price, 1L, TimeInForce.GTC, peak);
    }

    private static Order limit(long id, Side side, int qty, long price, TimeInForce tif) {
        return new Order(id, nextTs(), side, qty, price, 1L, tif);
    }

    private static long tsSeq = 1L;
    private static long nextTs() { return tsSeq++; }   // strictly positive, monotonic for FIFO clarity

    private void seed(Order o) { engine.addOrder(o); }
    private void capture() { engine.setExecutionListener(cap); }

    private BookSnapshotEvent snap() {
        BookSnapshotEvent s = new BookSnapshotEvent();
        engine.snapshotInto(s, BookSnapshotEvent.MAX_DEPTH_LEVELS);
        return s;
    }

    private static void assertSameBook(BookSnapshotEvent a, BookSnapshotEvent b) {
        assertEquals(a.bidLevelCount, b.bidLevelCount, "bid level count changed");
        assertEquals(a.askLevelCount, b.askLevelCount, "ask level count changed");
        assertEquals(a.bestBid, b.bestBid, "best bid changed");
        assertEquals(a.bestAsk, b.bestAsk, "best ask changed");
        for (int i = 0; i < a.bidLevelCount; i++) {
            assertEquals(a.bidPrices[i], b.bidPrices[i], "bid price @" + i);
            assertEquals(a.bidQtys[i],   b.bidQtys[i],   "bid qty @" + i);
        }
        for (int i = 0; i < a.askLevelCount; i++) {
            assertEquals(a.askPrices[i], b.askPrices[i], "ask price @" + i);
            assertEquals(a.askQtys[i],   b.askQtys[i],   "ask qty @" + i);
        }
    }

    // =================================================================== display / reload

    @Test
    void restingIceberg_snapshotShowsOnlyTheTip() {
        seed(iceberg(1, Side.SELL, 1000, P100, 100));

        BookSnapshotEvent s = snap();
        assertEquals(1, s.askLevelCount);
        assertEquals(P100, s.askPrices[0]);
        assertEquals(100L, s.askQtys[0]);   // tip only; the 900 reserve is hidden
    }

    @Test
    void tipHit_reloadsToAFreshTip_reserveSurvives() {
        seed(iceberg(1, Side.SELL, 1000, P100, 100));
        capture();

        // Aggressor takes exactly the tip. A plain 100-qty order would now be gone; the iceberg
        // reloads, so the level still shows 100.
        engine.addOrder(limit(2, Side.BUY, 100, P100, TimeInForce.GTC));

        assertEquals(1, cap.count(Kind.FILL));
        assertEquals(0, cap.get(0).remainingQuantity());   // aggressor completed
        BookSnapshotEvent after = snap();
        assertEquals(1, after.askLevelCount);
        assertEquals(100L, after.askQtys[0]);              // reloaded tip, not 0
        assertEquals(P100, engine.getBestAsk());           // still resting

        // Drain the whole reserve: 900 more must come out, proving the hidden total was 900.
        CapturingExecutionListener drain = new CapturingExecutionListener();
        engine.setExecutionListener(drain);
        engine.addOrder(limit(3, Side.BUY, 900, P100, TimeInForce.GTC));
        assertEquals(9, drain.count(Kind.FILL));           // 900 / 100-tip = nine slices
        assertEquals(-1L, engine.getBestAsk());            // iceberg fully consumed
    }

    @Test
    void reloadGoesBehindAnOrderAlreadyAtTheLevel() {
        seed(iceberg(1, Side.SELL, 1000, P100, 100));           // first in FIFO at the level
        seed(limit(2, Side.SELL, 200, P100, TimeInForce.GTC));  // second at the same level
        capture();

        // Hit the iceberg tip; it reloads to the BACK, behind order 2.
        engine.addOrder(limit(3, Side.BUY, 100, P100, TimeInForce.GTC));
        assertEquals(1, cap.get(0).passiveOrderId());      // order 1 was first

        // The next aggressor must now meet order 2 first, not the reloaded iceberg.
        CapturingExecutionListener next = new CapturingExecutionListener();
        engine.setExecutionListener(next);
        engine.addOrder(limit(4, Side.BUY, 100, P100, TimeInForce.GTC));
        assertEquals(2, next.get(0).passiveOrderId());
    }

    @Test
    void aggressorOf350AgainstLoneIceberg_fourTradesThreeReloads() {
        seed(iceberg(1, Side.SELL, 1000, P100, 100));
        long tradesBefore = engine.getTrades().size();
        capture();

        engine.addOrder(limit(2, Side.BUY, 350, P100, TimeInForce.GTC));

        assertEquals(4, cap.count(Kind.FILL));             // 100, 100, 100, 50 across three reloads
        assertEquals(100, cap.get(0).quantity());
        assertEquals(100, cap.get(1).quantity());
        assertEquals(100, cap.get(2).quantity());
        assertEquals(50,  cap.get(3).quantity());
        assertEquals(0,   cap.get(3).remainingQuantity()); // aggressor completed
        for (int i = 0; i < 4; i++) assertEquals(P100, cap.get(i).price());   // all at the level price
        assertEquals(tradesBefore + 4, engine.getTrades().size());
        assertEquals(P100, engine.getBestAsk());           // the 650 reserve still rests
    }

    @Test
    void icebergAsAggressor_usesFullQuantityThenRestsARefreshedTip() {
        seed(limit(1, Side.SELL, 300, P100, TimeInForce.GTC));   // only 300 resting
        capture();

        // Iceberg BUY 500 / peak 100 crosses: it matches with its FULL 500, not its 100 tip, so it
        // sweeps all 300, then rests the 200 remainder showing a fresh 100 tip.
        engine.addOrder(iceberg(2, Side.BUY, 500, P100, 100));

        assertEquals(1, cap.count(Kind.FILL));
        assertEquals(300, cap.get(0).quantity());          // full-quantity aggression, not 100
        Observed accepted = cap.get(1);
        assertEquals(Kind.ACCEPTED, accepted.kind());
        assertEquals(200, accepted.remainingQuantity());   // 200 rests
        BookSnapshotEvent s = snap();
        assertEquals(P100, s.bestBid);
        assertEquals(100L, s.bidQtys[0]);                  // rested tip = min(peak 100, 200)
    }

    @Test
    void cancelRemovesTheWholeIcebergIncludingReserve() {
        seed(iceberg(1, Side.SELL, 1000, P100, 100));

        assertTrue(engine.cancelOrder(1));
        assertEquals(-1L, engine.getBestAsk());            // tip and reserve both gone
        assertEquals(0, snap().askLevelCount);
    }

    // =================================================================== FOK counts the reserve

    @Test
    void fokNeedingHiddenReserve_fillsCompletely() {
        seed(iceberg(1, Side.SELL, 1000, P100, 100));      // 100 shown, 900 hidden
        capture();

        // The probe sums TOTAL resting quantity (1000), so a FOK for the full 1000 is admitted and
        // sweeps the iceberg across ten slices.
        engine.addOrder(limit(2, Side.BUY, 1000, P100, TimeInForce.FOK));

        assertEquals(10, cap.size());
        assertEquals(10, cap.count(Kind.FILL));
        assertEquals(0, cap.count(Kind.EXPIRED));
        assertEquals(0, cap.get(9).remainingQuantity());   // final slice completes the aggressor
        assertEquals(-1L, engine.getBestAsk());
    }

    @Test
    void fokShortEvenCountingTheReserve_expiresAndLeavesBookUnchanged() {
        seed(iceberg(1, Side.SELL, 1000, P100, 100));
        BookSnapshotEvent before = snap();
        long tradesBefore = engine.getTrades().size();
        capture();

        engine.addOrder(limit(2, Side.BUY, 1001, P100, TimeInForce.FOK));   // 1001 > 1000 total

        assertEquals(1, cap.size());
        assertEquals(Kind.EXPIRED, cap.get(0).kind());
        assertEquals(1001, cap.get(0).remainingQuantity());
        assertEquals(0, cap.count(Kind.FILL));
        assertSameBook(before, snap());
        assertEquals(tradesBefore, engine.getTrades().size());
    }

    // =================================================================== domain rules (constructor)

    @Test
    void icebergWithIoc_rejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, nextTs(), Side.SELL, 1000, P100, 1L, TimeInForce.IOC, 100));
    }

    @Test
    void icebergWithFok_rejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, nextTs(), Side.SELL, 1000, P100, 1L, TimeInForce.FOK, 100));
    }

    @Test
    void peakGreaterThanQuantity_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, nextTs(), Side.SELL, 100, P100, 1L, TimeInForce.GTC, 150));
    }

    @Test
    void negativePeak_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, nextTs(), Side.SELL, 100, P100, 1L, TimeInForce.GTC, -1));
    }

    @Test
    void peakEqualToQuantity_normalisedToPlainOrder() {
        Order o = new Order(1, nextTs(), Side.SELL, 100, P100, 1L, TimeInForce.GTC, 100);
        assertEquals(0, o.getPeak());            // hides nothing -> plain
        assertEquals(100, o.getVisibleQty());    // full quantity visible

        // A peak==quantity order with IOC is therefore a plain IOC order, not a reject.
        Order plainIoc = new Order(2, nextTs(), Side.BUY, 100, P100, 1L, TimeInForce.IOC, 100);
        assertEquals(0, plainIoc.getPeak());
    }
}
