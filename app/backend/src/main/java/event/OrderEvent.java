package event;

import model.OrdType;
import model.Side;
import model.TimeInForce;

import java.util.Arrays;

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

    public OrderEventType eventType;       // NEW_ORDER, CANCEL_ORDER, SESSION_OPEN or SESSION_CLOSE
    public long           orderId;         // ClOrdID (tag 11), numeric
    public Side           side;            // BUY / SELL for new orders; null for cancels
    public OrdType        ordType;         // LIMIT / PEG_MID for new orders (tag 40); null for cancels (Phase 14)
    public TimeInForce    tif;             // GTC / IOC / FOK for new orders (tag 59); null for cancels
    public long           price;           // limit price in units of $0.0001; -1 for pegged orders and cancels
    public long           quantity;        // order qty; -1 for cancels
    public long           maxFloor;        // iceberg display size (tag 111); 0 when not an iceberg; 0 for cancels (Phase 14)
    public long           timestamp;       // gateway receipt time (epoch nanos), stamped at Step 7
    public long           originalOrderId; // OrigClOrdID (tag 41) for cancels; -1 for new orders

    // --- Session fields (Phase 15, SRS §3.12): written by SESSION_OPEN / SESSION_CLOSE only ---

    /** Fixed symbol width on the journal (SBE char[8]), ASCII and NUL-padded. */
    public static final int SYMBOL_LENGTH = 8;

    public int            businessDate;    // YYYYMMDD on session commands; -1 otherwise
    public final byte[]   symbol = new byte[SYMBOL_LENGTH]; // SESSION_OPEN only; all NUL otherwise
    public long           tickSize;        // SESSION_OPEN only, units of $0.0001; -1 otherwise
    public long           lotSize;         // SESSION_OPEN only; -1 otherwise
    public long           priceScale;      // SESSION_OPEN only (units per dollar); -1 otherwise

    /**
     * Resets every session field to its "does not apply" value. Called by every writer of a
     * message that carries no session data (the FIX parser's NEW and CANCEL branches, the
     * journal decoder), so a reused carrier never bleeds a previous session command's values.
     */
    public void clearSessionFields() {
        businessDate = -1;
        Arrays.fill(symbol, (byte) 0);
        tickSize     = -1L;
        lotSize      = -1L;
        priceScale   = -1L;
    }
}
