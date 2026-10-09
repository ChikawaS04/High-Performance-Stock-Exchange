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
 * Phase 14-5 engine semantics for time in force (SRS §3.3/§3.4, D4-D6). Pure engine, no ring
 * buffer: a CapturingExecutionListener observes onFill/onAccepted/onExpired synchronously.
 *
 * The book is seeded while the engine still holds the default NO_OP listener, so the resting
 * orders' own acknowledgements are swallowed and the capturer records ONLY the aggressor under
 * test. Order ids are local literals (never IDGenerator), and trade-id assertions are property
 * checks (> 0), per the project's test discipline.
 */
class MatchingEngineTifTest {

    private static final long P100   = 1_000_000L;   // $100.00, on the one-cent tick
    private static final long P100_01 = 1_001_000L;  // $100.10
    private static final long P101   = 1_010_000L;   // $101.00

    private MatchingEngine engine;
    private CapturingExecutionListener cap;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        cap = new CapturingExecutionListener();
    }

    // --- order factory (decision J: one local helper absorbs later constructor widening) ---

    private static Order limit(long id, Side side, int qty, long price, TimeInForce tif) {
        return new Order(id, nextTs(), side, qty, price, 1L, tif);
    }

    private static long tsSeq = 1L;
    private static long nextTs() { return tsSeq++; }   // strictly positive, monotonic for FIFO clarity

    /** Rest an order under NO_OP so its ACCEPTED is not captured. */
    private void seed(Order o) {
        engine.addOrder(o);
    }

    /** Attach the capturer: everything after this is the aggressor under test. */
    private void capture() {
        engine.setExecutionListener(cap);
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

    // =================================================================== IOC

    @Test
    void ioc_fullFill_emitsFillsOnly_noAcceptedNoExpired() {
        seed(limit(1, Side.SELL, 50, P100, TimeInForce.GTC));
        capture();

        engine.addOrder(limit(2, Side.BUY, 50, P100, TimeInForce.IOC));

        assertEquals(1, cap.size());
        Observed fill = cap.get(0);
        assertEquals(Kind.FILL, fill.kind());
        assertEquals(0, fill.remainingQuantity());       // completed
        assertEquals(0, cap.count(Kind.ACCEPTED));
        assertEquals(0, cap.count(Kind.EXPIRED));
        assertEquals(-1L, engine.getBestAsk());          // resting sell fully consumed
        assertFalse(engine.cancelOrder(2));              // IOC never rested
    }

    @Test
    void ioc_partialFill_emitsFillsThenOneExpiredForRemainder_bookDoesNotHoldOrder() {
        seed(limit(1, Side.SELL, 50, P100, TimeInForce.GTC));
        capture();

        engine.addOrder(limit(2, Side.BUY, 80, P100, TimeInForce.IOC));

        assertEquals(2, cap.size());
        Observed fill = cap.get(0);
        assertEquals(Kind.FILL, fill.kind());
        assertEquals(50, fill.quantity());
        assertEquals(30, fill.remainingQuantity());
        Observed expired = cap.get(1);
        assertEquals(Kind.EXPIRED, expired.kind());
        assertEquals(2, expired.orderId());
        assertEquals(30, expired.remainingQuantity());   // the unexecuted 30
        assertEquals(0, cap.count(Kind.ACCEPTED));
        assertEquals(-1L, engine.getBestAsk());          // the 50 was consumed
        assertEquals(-1L, engine.getBestBid());          // the IOC remainder did NOT rest
        assertFalse(engine.cancelOrder(2));
    }

    @Test
    void ioc_noLiquidity_emitsOneExpiredForFullQuantity() {
        capture();   // empty book

        engine.addOrder(limit(1, Side.BUY, 100, P100, TimeInForce.IOC));

        assertEquals(1, cap.size());
        Observed expired = cap.get(0);
        assertEquals(Kind.EXPIRED, expired.kind());
        assertEquals(1, expired.orderId());
        assertEquals(100, expired.remainingQuantity());
        assertEquals(-1L, engine.getBestBid());
        assertFalse(engine.cancelOrder(1));
    }

    // =================================================================== FOK

    @Test
    void fok_exactLiquidityAcrossTwoLevels_fillsCompletely() {
        seed(limit(1, Side.SELL, 50, P100, TimeInForce.GTC));
        seed(limit(2, Side.SELL, 100, P101, TimeInForce.GTC));
        capture();

        engine.addOrder(limit(3, Side.BUY, 150, P101, TimeInForce.FOK));

        assertEquals(2, cap.count(Kind.FILL));
        assertEquals(0, cap.count(Kind.EXPIRED));
        assertEquals(0, cap.count(Kind.ACCEPTED));
        // best level first, then the next: remaining steps 150 -> 100 -> 0
        assertEquals(100, cap.get(0).remainingQuantity());
        assertEquals(0,   cap.get(1).remainingQuantity());
        assertEquals(-1L, engine.getBestAsk());          // both levels swept
        assertFalse(engine.cancelOrder(3));              // FOK never rests
    }

    @Test
    void fok_singleLevelExact_fillsCompletely_zeroRemainderNothingRests() {
        seed(limit(1, Side.SELL, 100, P100, TimeInForce.GTC));
        capture();

        engine.addOrder(limit(2, Side.BUY, 100, P100, TimeInForce.FOK));

        assertEquals(1, cap.size());
        assertEquals(Kind.FILL, cap.get(0).kind());
        assertEquals(0, cap.get(0).remainingQuantity());   // passing probe => zero remainder
        assertEquals(0, cap.count(Kind.ACCEPTED));
        assertEquals(0, cap.count(Kind.EXPIRED));
        assertEquals(-1L, engine.getBestAsk());
        assertFalse(engine.cancelOrder(2));
    }

    @Test
    void fok_oneUnitShort_expiresInFull_andBookIsUnchangedIncludingQueueOrder() {
        seed(limit(1, Side.SELL, 50, P100, TimeInForce.GTC));   // first in FIFO at $100.00
        seed(limit(2, Side.SELL, 50, P100, TimeInForce.GTC));   // second at $100.00
        seed(limit(3, Side.SELL, 100, P101, TimeInForce.GTC));  // beyond the FOK limit below

        BookSnapshotEvent before = snap();
        long tradesBefore = engine.getTrades().size();
        capture();

        // Limit $100.00: only the two $100.00 orders (100 total) are eligible; the $101.00
        // level is beyond the limit. 100 < 150 => probe fails.
        engine.addOrder(limit(4, Side.BUY, 150, P100, TimeInForce.FOK));

        assertEquals(1, cap.size());
        assertEquals(Kind.EXPIRED, cap.get(0).kind());
        assertEquals(4, cap.get(0).orderId());
        assertEquals(150, cap.get(0).remainingQuantity());
        assertEquals(0, cap.count(Kind.FILL));
        assertEquals(0, cap.count(Kind.ACCEPTED));

        assertSameBook(before, snap());                         // prices + quantities + tops
        assertEquals(tradesBefore, engine.getTrades().size());  // no trade recorded
        assertFalse(engine.cancelOrder(4));                     // the FOK never entered the book

        // Queue order survived: a genuine buy of 50 at $100.00 must hit order 1 first (FIFO).
        CapturingExecutionListener after = new CapturingExecutionListener();
        engine.setExecutionListener(after);
        engine.addOrder(limit(5, Side.BUY, 50, P100, TimeInForce.GTC));
        assertEquals(Kind.FILL, after.get(0).kind());
        assertEquals(1, after.get(0).passiveOrderId());
    }

    @Test
    void fok_liquidityBeyondLimitPrice_doesNotCount() {
        seed(limit(1, Side.SELL, 50, P100, TimeInForce.GTC));
        seed(limit(2, Side.SELL, 100, P101, TimeInForce.GTC));
        BookSnapshotEvent before = snap();
        capture();

        // Needs 100, but only the 50 at $100.00 is at/under the limit; the 100 at $101.00
        // is ineligible, so the probe fails and nothing trades.
        engine.addOrder(limit(3, Side.BUY, 100, P100, TimeInForce.FOK));

        assertEquals(1, cap.size());
        assertEquals(Kind.EXPIRED, cap.get(0).kind());
        assertEquals(100, cap.get(0).remainingQuantity());
        assertSameBook(before, snap());
        assertEquals(P100, engine.getBestAsk());   // both resting asks intact
    }

    // =================================================================== GTC regression

    @Test
    void gtc_restsAndAccepts_neverExpires() {
        capture();
        engine.addOrder(limit(1, Side.BUY, 50, P100_01, TimeInForce.GTC));

        assertEquals(1, cap.size());
        assertEquals(Kind.ACCEPTED, cap.get(0).kind());
        assertEquals(1, cap.get(0).orderId());
        assertEquals(50, cap.get(0).remainingQuantity());
        assertEquals(0, cap.count(Kind.EXPIRED));
        assertEquals(P100_01, engine.getBestBid());
        assertTrue(engine.cancelOrder(1));          // it rested, so it is cancellable
    }
}
