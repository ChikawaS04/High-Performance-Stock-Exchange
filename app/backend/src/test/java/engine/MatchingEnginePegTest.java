package engine;

import engine.CapturingExecutionListener.Kind;
import engine.CapturingExecutionListener.Observed;
import event.BookSnapshotEvent;
import model.OrdType;
import model.Order;
import model.Prices;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 14-9 engine semantics for midpoint peg orders (SRS 3.3, D9 / D10). Pure engine, no ring
 * buffer: a CapturingExecutionListener observes onFill / onAccepted / onExpired synchronously.
 *
 * The book and any resting pegs are seeded while the engine still holds the default NO_OP
 * listener, so their own acknowledgements are swallowed and the capturer records ONLY the
 * aggressor under test. Order ids are local literals (never IDGenerator); trade-id assertions are
 * property checks (&gt; 0), per the project's test discipline.
 */
class MatchingEnginePegTest {

    private static final long P99    =   990_000L;  // $99.00
    private static final long P100   = 1_000_000L;  // $100.00, on the one-cent tick
    private static final long P100_01 = 1_000_100L; // $100.01
    private static final long P100_02 = 1_000_200L; // $100.02
    private static final long P100_03 = 1_000_300L; // $100.03
    private static final long P101   = 1_010_000L;  // $101.00

    private static final long MID_100_005 = 1_000_050L;  // exact mid of P100 / P100_01 ($100.005)

    private MatchingEngine engine;
    private CapturingExecutionListener cap;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        cap = new CapturingExecutionListener();
    }

    // --- order factories (decision J: one local helper per file absorbs constructor widening) ---

    private static Order limit(long id, Side side, int qty, long price, TimeInForce tif) {
        return new Order(id, nextTs(), side, qty, price, 1L, tif);
    }

    private static Order peg(long id, Side side, int qty, TimeInForce tif) {
        return new Order(id, nextTs(), side, qty, Prices.NA, 1L, tif, 0, OrdType.PEG_MID);
    }

    private static long tsSeq = 1L;
    private static long nextTs() { return tsSeq++; }   // strictly positive, monotonic for FIFO / later-arrival clarity

    private void seed(Order o) { engine.addOrder(o); }
    private void capture() { engine.setExecutionListener(cap); }

    /** Seed a two-sided lit book so a mid exists: bid P100, ask P100_01 => mid 1_000_050. */
    private void seedTwoSidedBook(int bidQty, int askQty) {
        seed(limit(901, Side.BUY, bidQty, P100, TimeInForce.GTC));
        seed(limit(902, Side.SELL, askQty, P100_01, TimeInForce.GTC));
    }

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

    // =============================================================== mid and price

    @Test
    void pegVsPeg_tradesAtExactMidpoint_onTradeAndExec() {
        seedTwoSidedBook(10, 10);                       // mid = 1_000_050
        seed(peg(1, Side.SELL, 100, TimeInForce.GTC));  // rests in the ask peg pool
        capture();

        engine.addOrder(peg(2, Side.BUY, 100, TimeInForce.GTC));

        assertEquals(1, cap.size());
        Observed fill = cap.get(0);
        assertEquals(Kind.FILL, fill.kind());
        assertEquals(MID_100_005, fill.price());        // exact sub-penny mid on the EXEC
        assertEquals(100, fill.quantity());
        assertEquals(0, fill.remainingQuantity());
        assertEquals(1, fill.passiveOrderId());         // resting peg sell
        assertTrue(fill.tradeId() > 0);

        // the Trade recorded for this fill also carries the exact mid
        assertEquals(MID_100_005, engine.getTrades().get(engine.getTrades().size() - 1).price());

        // pegs never touch the lit book
        assertEquals(P100, engine.getBestBid());
        assertEquals(P100_01, engine.getBestAsk());
    }

    // =============================================================== priority

    @Test
    void litBuyCrossingMid_fillsPegPoolAtMidFirst_thenContinuesIntoLit() {
        seed(limit(901, Side.BUY, 10, P100, TimeInForce.GTC));    // establishes the mid
        seed(limit(902, Side.SELL, 60, P100_01, TimeInForce.GTC)); // lit ask, consumed by the lit leg
        seed(peg(1, Side.SELL, 40, TimeInForce.GTC));             // resting peg sell at the mid
        capture();

        // Buy priced at $101.00 crosses the mid; 40 from the peg pool at the mid, then 60 lit.
        engine.addOrder(limit(2, Side.BUY, 100, P101, TimeInForce.FOK));

        assertEquals(2, cap.count(Kind.FILL));
        assertEquals(0, cap.count(Kind.EXPIRED));
        assertEquals(0, cap.count(Kind.ACCEPTED));

        Observed pegFill = cap.get(0);                  // peg pool is taken FIRST
        assertEquals(MID_100_005, pegFill.price());
        assertEquals(40, pegFill.quantity());
        assertEquals(60, pegFill.remainingQuantity());
        assertEquals(1, pegFill.passiveOrderId());

        Observed litFill = cap.get(1);
        assertEquals(P100_01, litFill.price());         // lit price, not the mid
        assertEquals(60, litFill.quantity());
        assertEquals(0, litFill.remainingQuantity());
        assertEquals(902, litFill.passiveOrderId());

        assertEquals(-1L, engine.getBestAsk());         // the single lit ask was swept
    }

    @Test
    void litSellBetweenBestBidAndMid_hitsRestingPegBuy_notLitBids() {
        seed(limit(901, Side.BUY, 50, P100, TimeInForce.GTC));    // best bid $100.00
        seed(limit(902, Side.SELL, 50, P100_03, TimeInForce.GTC)); // ask $100.03 => mid $100.015 = 1_000_150
        seed(peg(1, Side.BUY, 30, TimeInForce.GTC));              // resting peg buy at the mid
        capture();

        // Sell at $100.01: below the mid (so it crosses the peg pool) but above the best bid (so
        // it is NOT marketable against the lit bid). It must hit the peg buy and leave the lit bid.
        engine.addOrder(limit(2, Side.SELL, 20, P100_01, TimeInForce.GTC));

        assertEquals(1, cap.count(Kind.FILL));
        Observed fill = cap.get(0);
        assertEquals(1_000_150L, fill.price());         // the exact mid, not the sell's own limit
        assertEquals(20, fill.quantity());
        assertEquals(1, fill.passiveOrderId());

        assertEquals(0, cap.count(Kind.ACCEPTED));      // the sell fully filled, nothing rested
        assertEquals(P100, engine.getBestBid());        // the lit bid is untouched
        assertTrue(engine.cancelOrder(1));              // the peg buy still rests (10 left)
    }

    @Test
    void incomingPeg_neverTakesLitLiquidity_evenWithLitSize() {
        seedTwoSidedBook(50, 50);                       // lit size on both sides, mid exists
        capture();

        // A peg buy has no price of its own and only matches the opposite peg pool, which is empty,
        // so it must rest rather than lift the lit ask.
        engine.addOrder(peg(1, Side.BUY, 100, TimeInForce.GTC));

        assertEquals(1, cap.size());
        assertEquals(Kind.ACCEPTED, cap.get(0).kind());
        assertEquals(Prices.NA, cap.get(0).price());    // a resting peg acknowledges at price NA
        assertEquals(0, cap.count(Kind.FILL));
        assertEquals(P100_01, engine.getBestAsk());     // lit ask untouched
        assertEquals(P100, engine.getBestBid());
    }

    // =============================================================== no-reference guard

    @Test
    void noMid_gtcPegRestsInactiveAtNa_andDoesNotTrade() {
        capture();   // empty book: no mid

        engine.addOrder(peg(1, Side.BUY, 100, TimeInForce.GTC));

        assertEquals(1, cap.size());
        assertEquals(Kind.ACCEPTED, cap.get(0).kind());
        assertEquals(1, cap.get(0).orderId());
        assertEquals(Prices.NA, cap.get(0).price());
        assertEquals(0, cap.count(Kind.FILL));
        assertTrue(engine.cancelOrder(1));              // it rested inactive, so it is cancellable
    }

    @Test
    void noMid_iocPegExpiresImmediately() {
        capture();
        engine.addOrder(peg(1, Side.SELL, 100, TimeInForce.IOC));

        assertEquals(1, cap.size());
        assertEquals(Kind.EXPIRED, cap.get(0).kind());
        assertEquals(100, cap.get(0).remainingQuantity());
        assertFalse(engine.cancelOrder(1));
    }

    @Test
    void noMid_fokPegExpiresImmediately() {
        capture();
        engine.addOrder(peg(1, Side.BUY, 100, TimeInForce.FOK));

        assertEquals(1, cap.size());
        assertEquals(Kind.EXPIRED, cap.get(0).kind());
        assertEquals(100, cap.get(0).remainingQuantity());
        assertFalse(engine.cancelOrder(1));
    }

    // =============================================================== pool cross

    @Test
    void poolCross_whenLitRestCreatesMid_laterArrivalIsAggressor() {
        seed(peg(10, Side.BUY, 50, TimeInForce.GTC));   // earlier arrival, rests inactive (no mid)
        seed(peg(11, Side.SELL, 50, TimeInForce.GTC));  // later arrival, rests inactive (no mid)
        seed(limit(901, Side.BUY, 10, P100, TimeInForce.GTC));  // one-sided: still no mid, no cross
        capture();

        // A lit ask now rests onto the empty side, creating the mid; the pools then cross.
        engine.addOrder(limit(902, Side.SELL, 10, P100_01, TimeInForce.GTC));

        assertEquals(1, cap.count(Kind.FILL));
        Observed cross = cap.all().stream().filter(o -> o.kind() == Kind.FILL).findFirst().orElseThrow();
        assertEquals(MID_100_005, cross.price());
        assertEquals(50, cross.quantity());
        assertEquals(0, cross.remainingQuantity());
        assertEquals(11, cross.orderId());              // later arrival is the aggressor (decision F)
        assertEquals(10, cross.passiveOrderId());

        assertFalse(engine.cancelOrder(10));            // both pegs fully crossed and left their pools
        assertFalse(engine.cancelOrder(11));
    }

    @Test
    void poolsDoNotCrossWithoutMid() {
        seed(peg(10, Side.BUY, 50, TimeInForce.GTC));
        seed(peg(11, Side.SELL, 50, TimeInForce.GTC));
        capture();

        // A lit bid alone leaves the ask side empty, so there is still no mid: no cross.
        engine.addOrder(limit(901, Side.BUY, 10, P100, TimeInForce.GTC));

        assertEquals(0, cap.count(Kind.FILL));
        assertTrue(engine.cancelOrder(10));             // both pegs still rest
        assertTrue(engine.cancelOrder(11));
    }

    // =============================================================== non-displayed

    @Test
    void pegsAreNonDisplayed_absentFromSnapshot_andIgnoredByBestBidAsk() {
        seed(limit(901, Side.BUY, 50, P100, TimeInForce.GTC));
        seed(limit(902, Side.SELL, 50, P100_01, TimeInForce.GTC));
        seed(peg(1, Side.BUY, 1000, TimeInForce.GTC));  // large peg buy rests (opposite pool empty)

        BookSnapshotEvent s = snap();
        assertEquals(1, s.bidLevelCount);               // only the lit bid level
        assertEquals(P100, s.bidPrices[0]);
        assertEquals(50, s.bidQtys[0]);                 // the peg's 1000 does not appear
        assertEquals(1, s.askLevelCount);
        assertEquals(P100_01, s.askPrices[0]);
        assertEquals(P100, engine.getBestBid());        // best bid ignores the peg
        assertEquals(P100_01, engine.getBestAsk());
    }

    // =============================================================== cancel

    @Test
    void cancelRestingPeg_returnsTrue_removesFromPool() {
        seedTwoSidedBook(10, 10);
        seed(peg(1, Side.BUY, 100, TimeInForce.GTC));   // rests in the bid peg pool

        assertTrue(engine.cancelOrder(1));              // found and removed
        assertFalse(engine.cancelOrder(1));             // gone from the pool

        // An opposite peg now finds nothing to hit and simply rests.
        capture();
        engine.addOrder(peg(2, Side.SELL, 100, TimeInForce.GTC));
        assertEquals(0, cap.count(Kind.FILL));
        assertEquals(1, cap.count(Kind.ACCEPTED));
    }

    // =============================================================== FOK

    @Test
    void fokPeg_insufficientPool_expiresInFull_poolUnchanged() {
        seedTwoSidedBook(10, 10);
        seed(peg(1, Side.SELL, 40, TimeInForce.GTC));   // only 40 available in the opposite pool
        capture();

        engine.addOrder(peg(2, Side.BUY, 100, TimeInForce.FOK));

        assertEquals(1, cap.size());
        assertEquals(Kind.EXPIRED, cap.get(0).kind());
        assertEquals(100, cap.get(0).remainingQuantity());
        assertEquals(0, cap.count(Kind.FILL));
        assertTrue(engine.cancelOrder(1));              // the resting peg sell is untouched
    }

    @Test
    void fokPeg_sufficientPool_fillsCompletelyAtMid() {
        seedTwoSidedBook(10, 10);
        seed(peg(1, Side.SELL, 100, TimeInForce.GTC));
        capture();

        engine.addOrder(peg(2, Side.BUY, 100, TimeInForce.FOK));

        assertEquals(1, cap.size());
        assertEquals(Kind.FILL, cap.get(0).kind());
        assertEquals(MID_100_005, cap.get(0).price());
        assertEquals(0, cap.get(0).remainingQuantity());
    }

    @Test
    void litFok_crossingMid_needsPoolAndLitLevels_fillsCompletely() {
        seed(limit(901, Side.BUY, 10, P100, TimeInForce.GTC));
        seed(limit(902, Side.SELL, 50, P100_01, TimeInForce.GTC));  // 50 lit
        seed(peg(1, Side.SELL, 60, TimeInForce.GTC));               // 60 in the peg pool
        capture();

        // 110 needed; probe counts 60 (pool) + 50 (lit at/under limit) = 110 => fills completely.
        engine.addOrder(limit(2, Side.BUY, 110, P100_01, TimeInForce.FOK));

        assertEquals(2, cap.count(Kind.FILL));
        assertEquals(0, cap.count(Kind.EXPIRED));
        assertEquals(MID_100_005, cap.get(0).price());   // pool first, at the mid
        assertEquals(60, cap.get(0).quantity());
        assertEquals(P100_01, cap.get(1).price());        // then lit
        assertEquals(0, cap.get(1).remainingQuantity());
    }

    @Test
    void litFok_crossingMid_shortEvenCountingPool_expiresInFull_bookUnchanged() {
        seed(limit(901, Side.BUY, 10, P100, TimeInForce.GTC));
        seed(limit(902, Side.SELL, 50, P100_01, TimeInForce.GTC));
        seed(peg(1, Side.SELL, 60, TimeInForce.GTC));
        BookSnapshotEvent before = snap();
        long tradesBefore = engine.getTrades().size();
        capture();

        // 111 needed; pool 60 + lit 50 = 110 < 111 => probe fails, nothing trades.
        engine.addOrder(limit(2, Side.BUY, 111, P100_01, TimeInForce.FOK));

        assertEquals(1, cap.size());
        assertEquals(Kind.EXPIRED, cap.get(0).kind());
        assertEquals(111, cap.get(0).remainingQuantity());
        assertEquals(0, cap.count(Kind.FILL));
        assertSameBook(before, snap());                   // lit book intact
        assertEquals(tradesBefore, engine.getTrades().size());
        assertTrue(engine.cancelOrder(1));                // the peg pool is intact too
    }
}
