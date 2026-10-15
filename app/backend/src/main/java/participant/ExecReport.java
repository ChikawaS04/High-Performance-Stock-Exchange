package participant;

import event.ExecutionEventType;

/**
 * Decoded EXEC frame, handed to a {@link Strategy#onExec} so it can reconcile its own
 * resting orders and inventory. Fields mirror the wire contract
 * ({@code publisher.WebSocketPublisher.serializeExecution}); a strategy reads only what it
 * needs. {@code aggressorOrderId}/{@code passiveOrderId} are both clOrdIds (the gateway uses
 * tag-11 ClOrdID as the engine orderId), which is what makes per-bot attribution by id range
 * possible with no wire change.
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
        long timestamp) {
}
