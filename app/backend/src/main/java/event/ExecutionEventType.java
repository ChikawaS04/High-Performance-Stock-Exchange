package event;

/**
 * Outbound execution report types (SRS §3.4). Distinct from OrderEventType,
 * which is the inbound request type (NEW_ORDER / CANCEL_ORDER).
 */
public enum ExecutionEventType {
    ORDER_ACCEPTED,
    ORDER_FILLED,
    ORDER_PARTIALLY_FILLED,
    ORDER_CANCELLED,
    ORDER_REJECTED,
    ORDER_EXPIRED
}
