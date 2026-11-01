package recovery;

import engine.MatchingEngine;
import model.Order;

import java.util.ArrayList;
import java.util.List;

/**
 * An independent description of an engine's complete state for equality checks (P15-5): every
 * resting order with every field, in restore order (so queue and pool order are compared), plus
 * the trade counter. Built from {@link MatchingEngine#visitResting} and the Order getters only,
 * never through the snapshot codec, so a codec bug cannot hide itself.
 */
public final class EngineStates {

    private EngineStates() { }

    public static List<String> describe(MatchingEngine engine) {
        List<String> out = new ArrayList<>();
        engine.visitResting((o, pegged) -> out.add(describe(o, pegged)));
        out.add("nextTradeId=" + engine.getNextTradeId());
        return out;
    }

    static String describe(Order o, boolean pegged) {
        return (pegged ? "PEG " : "LIT ") + o.getSide()
                + " id=" + o.getOrderID()
                + " ts=" + o.getTimeStamp()
                + " px=" + o.getPrice()
                + " qty=" + o.getQuantity()
                + " vis=" + o.getVisibleQty()
                + " peak=" + o.getPeak()
                + " status=" + o.getStatus()
                + " tif=" + o.getTimeInForce()
                + " type=" + o.getOrdType()
                + " participant=" + o.getParticipantID();
    }
}
