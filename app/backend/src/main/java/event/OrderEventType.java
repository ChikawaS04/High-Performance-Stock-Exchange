package event;

/**
 * Discriminates the inbound command types carried on the inbound ring buffer.
 * SESSION_OPEN and SESSION_CLOSE are the session commands of SRS §3.12 (Phase 15).
 * Set by FixParser from the FIX 35= field; read by the matching engine to decide
 * whether to place a new order or cancel a resting one.
 */
public enum OrderEventType {
    NEW_ORDER, CANCEL_ORDER, SESSION_OPEN, SESSION_CLOSE
}
