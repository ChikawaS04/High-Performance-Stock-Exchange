package recovery;

import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import model.Order;

import java.util.List;

/**
 * A snapshot decoded and fully validated, not yet applied (P15-5). Recovery builds the whole of
 * this before touching the engine, so an invalid snapshot never half-populates it.
 *
 * @param inboundSeq           the last inbound seq applied when the snapshot was taken
 * @param inboundPosition      the inbound recording position just after that command
 * @param inboundRecordingId   the inbound recording that position belongs to
 * @param outboundLastSeq      the handler's last outbound seq
 * @param nextTradeId          the engine's next trade id
 * @param lastCommandTimestamp the timestamp of the last applied command
 * @param orders               every resting order, in restore order
 */
public record RestoredSnapshot(
        long inboundSeq,
        long inboundPosition,
        long inboundRecordingId,
        long outboundLastSeq,
        long nextTradeId,
        long lastCommandTimestamp,
        List<RestoredOrder> orders) {

    /**
     * Restores this state into a pristine engine and its handler: every resting order in restore
     * order (one object graph: the queues, pools and {@code openOrders} share the instances), the
     * trade counter, the outbound seq and the last command's timestamp.
     *
     * @throws IllegalStateException if the engine has ever held an order or made a trade
     */
    public void applyTo(MatchingEngine engine, MatchingEngineHandler handler) {
        if (!engine.isPristine()) {
            throw new IllegalStateException("Recovery restores only into a pristine engine");
        }
        for (RestoredOrder entry : orders) {
            engine.restoreResting(entry.order(), entry.pegged());
        }
        engine.restoreNextTradeId(nextTradeId);
        handler.restoreLastSeq(outboundLastSeq);
        handler.restoreCommandTimestamp(lastCommandTimestamp);
    }

    /** One resting order and whether it lives in a peg pool. */
    public record RestoredOrder(Order order, boolean pegged) {
    }
}
