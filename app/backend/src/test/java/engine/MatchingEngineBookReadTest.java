package engine;

import model.Order;
import model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P4-0 empty-book read guard. getBestBid()/getBestAsk() must return the -1L
 * sentinel on an empty (or one-sided) book instead of throwing from firstKey().
 */
class MatchingEngineBookReadTest {

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
    }

    /** Order helper: as-built ctor arg order is (orderID, timeStamp, side, quantity, price, participantID). */
    private static Order order(long id, Side side, int qty, long priceUnits) {
        return new Order(id, System.nanoTime(), side, qty, priceUnits, 1L);
    }

    @Test
    void emptyBook_bothSidesReturnSentinel() {
        assertEquals(-1L, engine.getBestBid());
        assertEquals(-1L, engine.getBestAsk());
    }

    @Test
    void afterBidInsert_bestBidSet_askStillSentinel() {
        engine.addOrder(order(1L, Side.BUY, 50, 1_000_000L));   // $100.00

        assertEquals(1_000_000L, engine.getBestBid());
        assertEquals(-1L, engine.getBestAsk());             // ask side still empty — no throw
    }

    @Test
    void afterAskInsert_bestAskSet_bidStillSentinel() {
        engine.addOrder(order(1L, Side.SELL, 50, 1_010_000L));  // $101.00

        assertEquals(1_010_000L, engine.getBestAsk());
        assertEquals(-1L, engine.getBestBid());
    }

    @Test
    void multipleBids_bestBidIsHighest() {
        engine.addOrder(order(1L, Side.BUY, 10, 990_000L));
        engine.addOrder(order(2L, Side.BUY, 10, 1_000_000L));
        engine.addOrder(order(3L, Side.BUY, 10, 980_000L));

        assertEquals(1_000_000L, engine.getBestBid());
    }

    @Test
    void multipleAsks_bestAskIsLowest() {
        engine.addOrder(order(1L, Side.SELL, 10, 1_010_000L));
        engine.addOrder(order(2L, Side.SELL, 10, 1_020_000L));
        engine.addOrder(order(3L, Side.SELL, 10, 1_005_000L));

        assertEquals(1_005_000L, engine.getBestAsk());
    }

    @Test
    void twoSidedBook_reportsBothTops() {
        engine.addOrder(order(1L, Side.BUY, 10, 1_000_000L));   // best bid $100.00
        engine.addOrder(order(2L, Side.SELL, 10, 1_010_000L));  // best ask $101.00 — no cross

        assertEquals(1_000_000L, engine.getBestBid());
        assertEquals(1_010_000L, engine.getBestAsk());
    }
}