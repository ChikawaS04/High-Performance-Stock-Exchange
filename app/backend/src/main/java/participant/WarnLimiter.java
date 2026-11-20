package participant;

import java.util.function.LongSupplier;

/**
 * At most one warning per interval, counting what it held back (P16-5). The first call always
 * logs. Single-thread confined; the clock is injected so tests need no waiting.
 */
final class WarnLimiter {

    private final long intervalMillis;
    private final LongSupplier clockMillis;
    private long lastLogged = Long.MIN_VALUE;
    private long suppressed;

    WarnLimiter(long intervalMillis, LongSupplier clockMillis) {
        this.intervalMillis = intervalMillis;
        this.clockMillis = clockMillis;
    }

    /**
     * Record one occurrence.
     *
     * @return -1 if this one is held back; otherwise how many were held back since the last one
     *         logged (0 for the first), and the caller logs now
     */
    long occurrence() {
        long now = clockMillis.getAsLong();
        if (lastLogged != Long.MIN_VALUE && now - lastLogged < intervalMillis) {
            suppressed++;
            return -1;
        }
        long held = suppressed;
        suppressed = 0;
        lastLogged = now;
        return held;
    }

    /** Back to the start: the next occurrence logs. */
    void reset() {
        lastLogged = Long.MIN_VALUE;
        suppressed = 0;
    }
}
