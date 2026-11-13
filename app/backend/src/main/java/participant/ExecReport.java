package participant;

import event.ExecutionEventType;
import model.Side;

/**
 * Decoded EXEC frame, handed to a {@link Strategy#onExec} so it can reconcile its own
 * resting orders and position, and follow the market's trades. Fields mirror the wire contract
 * ({@code publisher.ExecutionFrames}); a strategy reads only what it needs.
 * {@code aggressorOrderId}/{@code passiveOrderId} are both clOrdIds (the gateway uses tag-11
 * ClOrdID as the engine orderId), which is what makes per-bot attribution by id block possible.
 *
 * <p>{@code side} is the side of {@code orderId}'s order where the wire states it: the aggressor's
 * side on a fill ({@code aggressorSide}) and the resting order's side on an ORDER_ACCEPTED
 * ({@code side}). Null on every other report.
 */
public record ExecReport(
        ExecutionEventType execType,
        long orderId,
        long tradeId,
        long priceUnits,
        long filledQuantity,
        long remainingQuantity,
        long aggressorOrderId,
        long passiveOrderId,
        long timestamp,
        Side side) {
}
