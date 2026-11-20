package clearing;

/**
 * Database retention's bounds (P16-8). Injectable, so tests run passes on request with short delays.
 *
 * @param firstRunDelayMs    first periodic pass after start
 * @param intervalMs         between periodic passes
 * @param batchPauseMs       pause after each committed batch, leaving I/O to the Reporter
 * @param statementTimeoutMs bound on each delete or summary statement (VACUUM runs without one)
 * @param lockTimeoutMs      bound on waiting for a row or table lock
 * @param stopTimeoutMs      how long {@link DatabaseRetention#stop()} waits before aborting the connection
 * @param abortJoinMs        how long it waits after aborting
 */
public record DatabaseRetentionTiming(long firstRunDelayMs, long intervalMs, long batchPauseMs,
                                      long statementTimeoutMs, long lockTimeoutMs, long stopTimeoutMs,
                                      long abortJoinMs) {

    public static final DatabaseRetentionTiming PRODUCTION = new DatabaseRetentionTiming(
            120_000L, 24L * 60L * 60_000L, 20L, 30_000L, 5_000L, 10_000L, 2_000L);

    /** Passes only when requested: no periodic pass within a test's lifetime, no pauses. */
    public static final DatabaseRetentionTiming ON_REQUEST = new DatabaseRetentionTiming(
            Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 0L, 30_000L, 5_000L, 10_000L, 2_000L);
}
