package event;

import model.Side;

/**
 * Mutable carrier for the inbound ring buffer (gateway -> matching engine).
 *
 * Pre-allocated once per Disruptor slot and reused: the gateway writes the
 * fields, the engine reads them. Deliberately framework-free (no com.lmax,
 * no io.netty) so both the pure FixParser and the Disruptor can depend on it
 * without either side leaking into the other.
 *
 * Public mutable fields are intentional — this is a value carrier on the hot
 * path, not an encapsulated domain object. Every field a message uses is
 * written before the slot is published, so there is no half-initialised read.
 */
public final class OrderEvent {

    public OrderEventType eventType;       // NEW_ORDER or CANCEL_ORDER
    public long           orderId;         // ClOrdID (tag 11), numeric
    public Side           side;            // BUY / SELL for new orders; null for cancels
    public long           price;           // limit price in units of $0.0001; -1 for cancels
    public long           quantity;        // order qty; -1 for cancels
    public long           timestamp;       // gateway receipt time (epoch nanos), stamped at Step 7
    public long           originalOrderId; // OrigClOrdID (tag 41) for cancels; -1 for new orders
}
