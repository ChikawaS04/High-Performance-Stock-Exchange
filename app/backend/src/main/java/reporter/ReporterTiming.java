package reporter;

/**
 * The Reporter's bounds. Injectable, so tests run the failure paths with short delays.
 *
 * @param maxBatchEvents     commit once a batch holds this many outbound events
 * @param maxBatchAgeMs      commit once a batch's first event is this old
 * @param backoffMinMs       first delay after a failure; doubles per failure in a row
 * @param backoffMaxMs       longest delay after a failure
 * @param errorLogIntervalMs at most one ERROR per this interval while failing
 * @param stallTimeoutMs     a replay with work to deliver and no progress for this long is reopened
 * @param stopTimeoutMs      how long {@link Reporter#stop()} waits for the thread
 */
public record ReporterTiming(int maxBatchEvents, long maxBatchAgeMs, long backoffMinMs, long backoffMaxMs,
                             long errorLogIntervalMs, long stallTimeoutMs, long stopTimeoutMs) {

    public static final ReporterTiming PRODUCTION =
            new ReporterTiming(1_000, 200L, 250L, 30_000L, 60_000L, 10_000L, 5_000L);
}
