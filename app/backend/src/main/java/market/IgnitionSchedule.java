package market;

import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * When the ignition fetch thread next calls Alpaca (P16-3, D9 as amended). Pure: every input is passed in.
 *
 * <ul>
 *   <li>Never attempted: now.</li>
 *   <li>The last attempt failed: {@value #MIN_GAP_MS} ms after it.</li>
 *   <li>The cached bar is dated today in New York (or later): the start of the next New York date. The
 *       open is fixed for the day, so the venue calls Alpaca about once a day.</li>
 *   <li>The cached bar is older (before the open, a weekend, a holiday): {@value #STALE_REFETCH_MS} ms
 *       after the last attempt, until a bar dated today is seen.</li>
 *   <li>In every case at least {@value #MIN_GAP_MS} ms after the last attempt: at most one upstream call a
 *       minute, however many browsers ask.</li>
 * </ul>
 */
final class IgnitionSchedule {

    /** Minimum gap between two upstream calls, and the retry delay after a failure. */
    static final long MIN_GAP_MS = 60_000L;
    /** Refetch interval while the cached bar is not today's. */
    static final long STALE_REFETCH_MS = 15L * 60_000L;

    /** No attempt has been made. */
    static final long NEVER = Long.MIN_VALUE;
    /** No bar is cached. */
    static final int NO_DATE = -1;

    private IgnitionSchedule() { }

    /**
     * @param nowMs          wall clock, epoch milliseconds
     * @param lastAttemptMs  start of the last upstream call, epoch milliseconds, or {@link #NEVER}
     * @param lastFailed     whether that call failed
     * @param cachedDate     the cached bar's New York date (YYYYMMDD), or {@link #NO_DATE}
     * @return the epoch millisecond at or after which the next call may start
     */
    static long nextAttemptAt(long nowMs, long lastAttemptMs, boolean lastFailed, int cachedDate) {
        if (lastAttemptMs == NEVER) return nowMs;
        long floor = lastAttemptMs + MIN_GAP_MS;
        long due;
        if (lastFailed || cachedDate == NO_DATE) {
            due = floor;
        } else {
            ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(IgnitionParser.NEW_YORK);
            int today = IgnitionParser.yyyymmdd(now);
            if (cachedDate >= today) {
                due = now.toLocalDate().plusDays(1).atStartOfDay(IgnitionParser.NEW_YORK).toInstant().toEpochMilli();
            } else {
                due = lastAttemptMs + STALE_REFETCH_MS;
            }
        }
        return Math.max(due, floor);
    }
}
