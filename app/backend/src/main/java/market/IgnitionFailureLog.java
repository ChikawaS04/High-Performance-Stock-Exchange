package market;

/**
 * Rate limit for the ignition fetch's log lines, like the Reporter's and clearing's (P16-3). Decides what
 * to log; the caller logs it. Used by the fetch thread only.
 *
 * One WARN on the first failure of a run; after that at most one WARN per {@value #WARN_INTERVAL_MS} ms,
 * carrying how many failures were not logged since the last one; one INFO when a fetch succeeds again.
 * The reason is only an HTTP status or an exception's class name, never an upstream body or a key.
 */
final class IgnitionFailureLog {

    static final long WARN_INTERVAL_MS = 10L * 60_000L;

    private long failuresInARow;
    private long suppressed;
    private long lastWarnMs;

    /** Records a failure at {@code nowMs}; returns the WARN line to log, or null to stay quiet. */
    String onFailure(long nowMs, String reason) {
        failuresInARow++;
        if (failuresInARow == 1 || nowMs - lastWarnMs >= WARN_INTERVAL_MS) {
            String line = "Ignition price fetch failed (" + reason + "); " + failuresInARow + " failure(s) in a row, "
                    + suppressed + " not logged; retrying";
            lastWarnMs = nowMs;
            suppressed = 0;
            return line;
        }
        suppressed++;
        return null;
    }

    /** Records a success; returns the INFO line to log after a run of failures, or null. */
    String onSuccess() {
        if (failuresInARow == 0) return null;
        String line = "Ignition price fetch recovered after " + failuresInARow + " failure(s)";
        failuresInARow = 0;
        suppressed = 0;
        return line;
    }
}
