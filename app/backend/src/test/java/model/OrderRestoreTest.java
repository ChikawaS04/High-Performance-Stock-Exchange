package model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Order#restore} (P15-5): rebuilds a resting order exactly, including the states the
 * canonical constructor cannot produce, and rejects anything that cannot be a resting order.
 */
class OrderRestoreTest {

    private static final long P = 1_500_000L;   // $150.00

    private static Order restore(int quantity, long price, Status status, TimeInForce tif, int peak, int visible,
                                 OrdType type) {
        return Order.restore(7L, 99L, Side.SELL, quantity, price, status, 3L, tif, peak, visible, type);
    }

    @Test
    void anIcebergBelowItsPeakAndMidSliceComesBackExactly() {
        Order o = restore(20, P, Status.PARTIALLY_FILLED, TimeInForce.GTC, 100, 15, OrdType.LIMIT);
        assertEquals(7L, o.getOrderID());
        assertEquals(99L, o.getTimeStamp());
        assertEquals(Side.SELL, o.getSide());
        assertEquals(20, o.getQuantity());
        assertEquals(P, o.getPrice());
        assertEquals(Status.PARTIALLY_FILLED, o.getStatus());
        assertEquals(3L, o.getParticipantID());
        assertEquals(TimeInForce.GTC, o.getTimeInForce());
        assertEquals(100, o.getPeak(), "a peak above the remaining quantity is kept, not rejected");
        assertEquals(15, o.getVisibleQty(), "the visible slice is kept, not recomputed");
        assertEquals(OrdType.LIMIT, o.getOrdType());
    }

    @Test
    void anIcebergWhosePeakEqualsItsRemainingQuantityIsNotNormalised() {
        assertEquals(50, restore(50, P, Status.PARTIALLY_FILLED, TimeInForce.GTC, 50, 50, OrdType.LIMIT).getPeak());
    }

    @Test
    void aRestoredIcebergReloadsLikeALiveOne() {
        Order o = restore(30, P, Status.PARTIALLY_FILLED, TimeInForce.GTC, 100, 5, OrdType.LIMIT);
        o.fill(5);
        assertEquals(0, o.getVisibleQty());
        o.refreshDisplay();
        assertEquals(25, o.getVisibleQty());
    }

    @Test
    void aPegAndAPlainOrderRestore() {
        Order peg = restore(40, Prices.NA, Status.OPEN, TimeInForce.GTC, 0, 40, OrdType.PEG_MID);
        assertEquals(Prices.NA, peg.getPrice());
        Order plain = restore(40, P, Status.OPEN, TimeInForce.GTC, 0, 40, OrdType.LIMIT);
        assertEquals(40, plain.getVisibleQty());
    }

    @Test
    void anythingThatCannotRestIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Order.restore(0L, 99L, Side.SELL, 10, P, Status.OPEN, 3L,
                TimeInForce.GTC, 0, 10, OrdType.LIMIT), "order id");
        assertThrows(IllegalArgumentException.class, () -> Order.restore(7L, 0L, Side.SELL, 10, P, Status.OPEN, 3L,
                TimeInForce.GTC, 0, 10, OrdType.LIMIT), "timestamp");
        assertThrows(IllegalArgumentException.class, () -> Order.restore(7L, 99L, Side.SELL, 10, P, Status.OPEN, 0L,
                TimeInForce.GTC, 0, 10, OrdType.LIMIT), "participant");
        assertThrows(IllegalArgumentException.class, () -> Order.restore(7L, 99L, null, 10, P, Status.OPEN, 3L,
                TimeInForce.GTC, 0, 10, OrdType.LIMIT), "side");
        assertThrows(IllegalArgumentException.class,
                () -> restore(0, P, Status.OPEN, TimeInForce.GTC, 0, 0, OrdType.LIMIT), "zero quantity");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, P, Status.FILLED, TimeInForce.GTC, 0, 10, OrdType.LIMIT), "terminal status");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, P, null, TimeInForce.GTC, 0, 10, OrdType.LIMIT), "null status");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, P, Status.OPEN, TimeInForce.GTC, 0, 9, OrdType.LIMIT), "plain order hiding quantity");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, P, Status.OPEN, TimeInForce.GTC, 4, 5, OrdType.LIMIT), "slice above peak");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, P, Status.OPEN, TimeInForce.GTC, 4, 0, OrdType.LIMIT), "empty slice");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, P, Status.OPEN, TimeInForce.IOC, 4, 4, OrdType.LIMIT), "non-GTC iceberg");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, P, Status.OPEN, TimeInForce.GTC, -1, 10, OrdType.LIMIT), "negative peak");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, P + 1, Status.OPEN, TimeInForce.GTC, 0, 10, OrdType.LIMIT), "off tick");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, P, Status.OPEN, TimeInForce.GTC, 0, 10, OrdType.PEG_MID), "priced peg");
        assertThrows(IllegalArgumentException.class,
                () -> restore(10, Prices.NA, Status.OPEN, TimeInForce.GTC, 5, 5, OrdType.PEG_MID), "iceberg peg");
    }
}
