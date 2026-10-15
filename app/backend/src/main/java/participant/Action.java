package participant;

import model.OrdType;
import model.Side;
import model.TimeInForce;

/**
 * One order instruction a {@link Strategy} wants sent: either a NewOrderSingle or
 * an OrderCancelRequest. The strategy owns clOrdId assignment (within its attribution
 * range), so an Action is already addressed; {@link ParticipantClient} only encodes
 * and sends it. A record because it is a pure value with no behaviour.
 *
 * <p>For {@link Kind#NEW}: side, ordType, tif, priceUnits and qty are meaningful;
 * origClOrdId is -1. For {@link Kind#CANCEL}: clOrdId and origClOrdId are meaningful;
 * the rest are null/-1.
 */
public record Action(
        Kind kind,
        long clOrdId,
        Side side,
        OrdType ordType,
        TimeInForce tif,
        long priceUnits,
        long qty,
        long origClOrdId) {

    public enum Kind { NEW, CANCEL }

    /** A limit NewOrderSingle (the only ordType these bots post). */
    public static Action newLimit(long clOrdId, Side side, TimeInForce tif, long priceUnits, long qty) {
        return new Action(Kind.NEW, clOrdId, side, OrdType.LIMIT, tif, priceUnits, qty, -1L);
    }

    /** An OrderCancelRequest for a resting order this strategy previously posted. */
    public static Action cancel(long clOrdId, long origClOrdId) {
        return new Action(Kind.CANCEL, clOrdId, null, null, null, -1L, -1L, origClOrdId);
    }
}
