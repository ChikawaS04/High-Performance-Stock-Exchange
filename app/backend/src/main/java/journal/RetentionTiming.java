package journal;

/**
 * Journal retention's schedule (P16-7, R6), a record so tests can run it on demand.
 *
 * @param firstRunDelayMs       first periodic run after the venue starts
 * @param intervalMs            between completed periodic runs (at most one an hour in production)
 * @param refreshMs             between refreshes of the {@code /health} journal object
 * @param retryAfterRefusalMs   the next attempt after the Archive refused a purge because a replay was
 *                              reading below the new start
 * @param settleTimeoutMs       bound on waiting for the outbound recording to catch up with the recorder
 * @param stopTimeoutMs         bound on joining the thread at shutdown
 */
public record RetentionTiming(long firstRunDelayMs, long intervalMs, long refreshMs, long retryAfterRefusalMs,
                              long settleTimeoutMs, long stopTimeoutMs) {

    public static final RetentionTiming PRODUCTION =
            new RetentionTiming(60_000L, 3_600_000L, 60_000L, 300_000L, 1_000L, 10_000L);

    /** Runs only when asked ({@link JournalRetention#requestRun()}, a close snapshot); for tests. */
    public static final RetentionTiming ON_REQUEST =
            new RetentionTiming(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 200L, Long.MAX_VALUE / 4, 1_000L, 10_000L);
}
