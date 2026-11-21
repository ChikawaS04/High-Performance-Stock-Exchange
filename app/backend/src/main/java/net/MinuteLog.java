package net;

/**
 * At most one log line a minute for a recurring condition (P16-9), never one per event. The first occurrence
 * is reported at once; later ones within the minute are counted and the count is carried by the next line.
 * Times are nanoseconds from the caller's clock. Not thread safe: each instance is confined to one thread
 * (here the single Netty worker or the resend thread).
 */
final class MinuteLog {

    static final long INTERVAL_NANOS = 60_000_000_000L;

    private long lastNanos;
    private boolean logged;
    private long suppressed;

    /**
     * Records one occurrence at {@code now}.
     *
     * @return -1 when nothing should be logged now; otherwise the number of occurrences suppressed since the
     *         last line (0 for the first), and the caller logs one line
     */
    long occurrence(long now) {
        if (logged && now - lastNanos < INTERVAL_NANOS) {
            suppressed++;
            return -1;
        }
        long held = suppressed;
        suppressed = 0;
        logged = true;
        lastNanos = now;
        return held;
    }
}
