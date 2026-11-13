package participant;

import model.OrdType;
import model.Side;
import model.TimeInForce;

/**
 * One order instruction a {@link Strategy} wants sent: either a NewOrderSingle or
 * an OrderCancelRequest. The strategy owns clOrdId assignment (within its id block),
 * so an Action is already addressed; {@link ParticipantClient} only encodes and sends it.
 * A record because it is a pure value with no behaviour.
 *
 * <p>For {@link Kind#NEW}: side, ordType, tif, priceUnits, qty and maxFloor are meaningful;
 * origClOrdId is -1. maxFloor is the iceberg display size, 0 for a plain limit. For
 * {@link Kind#CANCEL}: clOrdId and origClOrdId are meaningful; the rest are null/-1/0.
 */
public record Action(
        Kind kind,
        long clOrdId,
        Side side,
        OrdType ordType,
        TimeInForce tif,
        long priceUnits,
        long qty,
        long origClOrdId,
        long maxFloor) {

    public enum Kind { NEW, CANCEL }

    /** A limit NewOrderSingle. */
    public static Action newLimit(long clOrdId, Side side, TimeInForce tif, long priceUnits, long qty) {
        return new Action(Kind.NEW, clOrdId, side, OrdType.LIMIT, tif, priceUnits, qty, -1L, 0L);
    }

    /** A GTC iceberg limit showing {@code maxFloor} at a time. */
    public static Action newIceberg(long clOrdId, Side side, long priceUnits, long qty, long maxFloor) {
        return new Action(Kind.NEW, clOrdId, side, OrdType.LIMIT, TimeInForce.GTC, priceUnits, qty, -1L, maxFloor);
    }

    /** An OrderCancelRequest for a resting order this strategy previously posted. */
    public static Action cancel(long clOrdId, long origClOrdId) {
        return new Action(Kind.CANCEL, clOrdId, null, null, null, -1L, -1L, origClOrdId, 0L);
    }
}
