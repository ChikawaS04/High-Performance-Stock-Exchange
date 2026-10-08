package event;

/**
 * Mutable carrier for the outbound ring buffer (matching engine -> subscribers).
 * Pre-allocated once per Disruptor slot and reused, exactly like OrderEvent.
 * Framework-free (no com.lmax, no io.netty) — only its factory touches com.lmax.
 *
 * Slot-reuse discipline: every field is written on every publish (see
 * MatchingEngineHandler.publish), so no stale value bleeds across reuses.
 * Fields that don't apply to a given event type carry the -1L sentinel.
 */
public final class ExecutionEvent {

    public ExecutionEventType eventType;
    public long orderId;            // the order this report is about (aggressor for fills)
    public long tradeId;            // fills only; -1 otherwise
    public long price;              // fill price (fills) or limit price (accepted), units of $0.0001; -1 otherwise
    public long filledQuantity;     // fills only; -1 otherwise
    public long remainingQuantity;  // aggressor remaining (fills) / resting qty (accepted); -1 otherwise
    public long aggressorOrderId;   // fills only; -1 otherwise
    public long passiveOrderId;     // fills only; -1 otherwise
    public long timestamp;          // execution publish time (epoch nanos)
}