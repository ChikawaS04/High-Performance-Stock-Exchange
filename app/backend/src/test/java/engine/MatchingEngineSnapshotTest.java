package engine;

import event.BookSnapshotEvent;
import model.Order;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P4-1 depth snapshot: MatchingEngine.snapshotInto fills a bounded top-N carrier,
 * best-first, aggregating quantity per level, with -1L sentinels on empty sides.
 */
class MatchingEngineSnapshotTest {

    private MatchingEngine engine;
    private BookSnapshotEvent snap;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        snap = new BookSnapshotEvent();
    }

    /** As-built ctor arg order: (orderID, timeStamp, side, quantity, price, participantID). */
    private static Order order(long id, Side side, int qty, long priceUnits) {
        return new Order(id, System.nanoTime(), side, qty, priceUnits, 1L);
    }

    /** Iceberg via the canonical 8-arg ctor: GTC with a display size (Phase 14-7). */
    private static Order iceberg(long id, Side side, int qty, long priceUnits, int peak) {
        return new Order(id, System.nanoTime(), side, qty, priceUnits, 1L, TimeInForce.GTC, peak);
    }

    @Test
    void emptyBook_zeroCounts_sentinelTops() {
        engine.snapshotInto(snap, BookSnapshotEvent.MAX_DEPTH_LEVELS);

        assertEquals(0, snap.bidLevelCount);
        assertEquals(0, snap.askLevelCount);
        assertEquals(-1L, snap.bestBid);
        assertEquals(-1L, snap.bestAsk);
    }

    @Test
    void aggregatesQuantityAcrossOrdersAtOneLevel() {
        engine.addOrder(order(1L, Side.BUY, 30, 1_000_000L));
        engine.addOrder(order(2L, Side.BUY, 20, 1_000_000L));   // same price level, FIFO

        engine.snapshotInto(snap, BookSnapshotEvent.MAX_DEPTH_LEVELS);

        assertEquals(1, snap.bidLevelCount);
        assertEquals(1_000_000L, snap.bidPrices[0]);
        assertEquals(50L, snap.bidQtys[0]);                 // 30 + 20 aggregated into one level
        assertEquals(1_000_000L, snap.bestBid);
        assertEquals(0, snap.askLevelCount);
        assertEquals(-1L, snap.bestAsk);
    }

    @Test
    void oneSidedBook_asksEmpty() {
        engine.addOrder(order(1L, Side.BUY, 10, 990_000L));
        engine.addOrder(order(2L, Side.BUY, 10, 1_000_000L));

        engine.snapshotInto(snap, BookSnapshotEvent.MAX_DEPTH_LEVELS);

        assertEquals(2, snap.bidLevelCount);
        assertEquals(1_000_000L, snap.bidPrices[0]);            // best (highest) first
        assertEquals(990_000L, snap.bidPrices[1]);
        assertEquals(0, snap.askLevelCount);
        assertEquals(-1L, snap.bestAsk);
    }

    @Test
    void bidsHighestFirst_asksLowestFirst() {
        engine.addOrder(order(1L, Side.BUY, 10, 990_000L));
        engine.addOrder(order(2L, Side.BUY, 10, 1_000_000L));
        engine.addOrder(order(3L, Side.SELL, 10, 1_020_000L));
        engine.addOrder(order(4L, Side.SELL, 10, 1_010_000L));  // 1_010_000 > 1_000_000 best bid — no cross

        engine.snapshotInto(snap, BookSnapshotEvent.MAX_DEPTH_LEVELS);

        assertEquals(2, snap.bidLevelCount);
        assertEquals(1_000_000L, snap.bidPrices[0]);
        assertEquals(990_000L, snap.bidPrices[1]);
        assertEquals(1_000_000L, snap.bestBid);

        assertEquals(2, snap.askLevelCount);
        assertEquals(1_010_000L, snap.askPrices[0]);            // best (lowest) first
        assertEquals(1_020_000L, snap.askPrices[1]);
        assertEquals(1_010_000L, snap.bestAsk);
    }

    @Test
    void moreThanMaxLevels_truncatesToTopN() {
        // MAX_DEPTH_LEVELS + 2 distinct bid levels 1_000_000..1_002_100 (one cent apart), all buys
        // (no asks, no cross), so the walk must truncate to the top MAX_DEPTH_LEVELS and drop the two lowest.
        for (int i = 0; i < BookSnapshotEvent.MAX_DEPTH_LEVELS + 2; i++) {
            engine.addOrder(order(i + 1, Side.BUY, 5, 1_000_000L + i * 100L));
        }

        engine.snapshotInto(snap, BookSnapshotEvent.MAX_DEPTH_LEVELS);   // capacity 20

        assertEquals(BookSnapshotEvent.MAX_DEPTH_LEVELS, snap.bidLevelCount);
        assertEquals(1_002_100L, snap.bidPrices[0]);            // highest kept
        assertEquals(1_000_200L, snap.bidPrices[BookSnapshotEvent.MAX_DEPTH_LEVELS - 1]);
        // 20th-highest; 1_000_100 & 1_000_000 dropped
        assertEquals(1_002_100L, snap.bestBid);                 // top of book unaffected by truncation
    }

    @Test
    void maxLevelsArg_clampedToCapacity() {
        for (int i = 0; i < 5; i++) {
            engine.addOrder(order(i + 1, Side.BUY, 5, 1_000_000L + i * 100L));
        }

        engine.snapshotInto(snap, 100);                     // caller over-asks; only 5 levels exist

        assertEquals(5, snap.bidLevelCount);
    }

    @Test
    void maxLevelsArg_truncatesBelowCapacity() {
        for (int i = 0; i < 5; i++) {
            engine.addOrder(order(i + 1, Side.BUY, 5, 1_000_000L + i * 100L));
        }

        engine.snapshotInto(snap, 3);                       // caller wants only top 3

        assertEquals(3, snap.bidLevelCount);
        assertEquals(1_000_400L, snap.bidPrices[0]);
        assertEquals(1_000_200L, snap.bidPrices[2]);
    }

    @Test
    void slotReuse_countsAuthoritative_noStaleBleed() {
        engine.addOrder(order(1L, Side.BUY, 5, 1_000_000L));
        engine.addOrder(order(2L, Side.BUY, 5, 990_000L));
        engine.addOrder(order(3L, Side.BUY, 5, 980_000L));
        engine.snapshotInto(snap, BookSnapshotEvent.MAX_DEPTH_LEVELS);
        assertEquals(3, snap.bidLevelCount);

        // Reuse the same carrier against a fresh, smaller book.
        MatchingEngine engine2 = new MatchingEngine();
        engine2.addOrder(order(10L, Side.BUY, 7, 2_000_000L));
        engine2.snapshotInto(snap, BookSnapshotEvent.MAX_DEPTH_LEVELS);

        assertEquals(1, snap.bidLevelCount);                // count shrank — authoritative
        assertEquals(2_000_000L, snap.bidPrices[0]);            // valid prefix overwritten
        assertEquals(7L, snap.bidQtys[0]);
        assertEquals(2_000_000L, snap.bestBid);
        // bidPrices[1] may still hold 990_000 from the prior snapshot; that's expected.
        // Consumers read only [0, bidLevelCount) — we assert the contract, not a zeroed tail.
    }

    @Test
    void bestTopsMatchLevelZero() {
        engine.addOrder(order(1L, Side.BUY, 5, 1_000_000L));
        engine.addOrder(order(2L, Side.SELL, 5, 1_010_000L));

        engine.snapshotInto(snap, BookSnapshotEvent.MAX_DEPTH_LEVELS);

        assertEquals(snap.bidPrices[0], snap.bestBid);
        assertEquals(snap.askPrices[0], snap.bestAsk);
    }

    @Test
    void icebergLevelReportsOnlyItsVisibleTip() {
        engine.addOrder(iceberg(1L, Side.SELL, 1000, 1_000_000L, 100));

        engine.snapshotInto(snap, BookSnapshotEvent.MAX_DEPTH_LEVELS);

        assertEquals(1, snap.askLevelCount);
        assertEquals(1_000_000L, snap.askPrices[0]);
        assertEquals(100L, snap.askQtys[0]);   // tip only; the 900 reserve is hidden
        assertEquals(1_000_000L, snap.bestAsk);
    }
}