package event;

import model.Side;

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

    public long seq;                // outbound sequence number (SRS §3.4); assigned from P15-2, -1 until then
    public ExecutionEventType eventType;
    public long orderId;            // the order this report is about (aggressor for fills)
    public long tradeId;            // fills only; -1 otherwise
    public long price;              // fill price (fills) or limit price (accepted), units of $0.0001; -1 otherwise
    public long filledQuantity;     // fills only; -1 otherwise
    public long remainingQuantity;  // aggressor remaining (fills) / resting qty (accepted); -1 otherwise
    public long aggressorOrderId;   // fills only; -1 otherwise
    public long passiveOrderId;     // fills only; -1 otherwise
    public Side aggressorSide;      // side of orderId's order: the aggressor's (fills) or the resting order's (accepted); null otherwise (D4)
    public long aggressorParticipantId; // fills only; -1 otherwise (D4)
    public long passiveParticipantId;   // fills only; -1 otherwise (D4)
    public int  businessDate;       // SESSION_OPENED / SESSION_CLOSED only (YYYYMMDD); -1 otherwise
    public long timestamp;          // execution publish time (epoch nanos)
    public long commandSeq = -1L;   // inbound seq of the command that caused this event (P15-10); -1 if unknown:
                                    // recorded before schema version 1, or produced with no journal behind it
}