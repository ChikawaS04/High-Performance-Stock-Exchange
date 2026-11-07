package clearing;

/**
 * Clearing's bounds. Injectable, so tests run the failure paths with short delays.
 *
 * @param pollIntervalMs     longest wait between checks when nobody wakes the thread
 * @param backoffMinMs       first delay after a failure; doubles per failure in a row
 * @param backoffMaxMs       longest delay after a failure
 * @param errorLogIntervalMs at most one ERROR per this interval while failing
 * @param stopTimeoutMs      how long {@link Clearing#stop()} waits for the thread
 */
public record ClearingTiming(long pollIntervalMs, long backoffMinMs, long backoffMaxMs, long errorLogIntervalMs,
                             long stopTimeoutMs) {

    public static final ClearingTiming PRODUCTION = new ClearingTiming(5_000L, 250L, 30_000L, 60_000L, 5_000L);
}
