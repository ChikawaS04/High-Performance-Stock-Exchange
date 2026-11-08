package http;

/**
 * Where a business date stands, as of the Reporter's checkpoint (the {@code asOfOutboundSeq} of the
 * same response). A day the engine has opened but the Reporter has not yet reached reads NONE.
 */
enum DayStatus {
    /** The checkpoint's open session is this date: figures can still grow. */
    OPEN,
    /** Closed; its clearing run is PENDING, so obligations and closing values are not yet known. */
    CLOSED,
    /** Its clearing run is COMPLETE. */
    CLEARED,
    /** Nothing is recorded for this date. */
    NONE;

    static DayStatus of(String runStatus, boolean sessionOpenOnDate) {
        if ("COMPLETE".equals(runStatus)) return CLEARED;
        if ("PENDING".equals(runStatus)) return CLOSED;
        return sessionOpenOnDate ? OPEN : NONE;
    }
}
