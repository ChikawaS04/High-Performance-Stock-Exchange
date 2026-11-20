package clearing;

/**
 * What database retention logs when a pass deletes nothing more on doubt (P16-8), in the pattern of
 * journal retention's skip log (P16-7, R7). Decides; the caller logs. Retention thread only.
 *
 * <ul>
 *   <li>A skip reason that appears, or replaces another, is one WARN.</li>
 *   <li>While the same reason persists it is repeated at most once every {@value #REPEAT_INTERVAL_MS} ms,
 *       with the count of skips not logged since.</li>
 *   <li>A pass that is not skipped after skips is one INFO saying the reason cleared.</li>
 * </ul>
 */
final class PurgeSkipLog {

    static final long REPEAT_INTERVAL_MS = 24L * 60L * 60_000L;

    /** One line to log; {@code warn} false means INFO. */
    record Line(boolean warn, String text) {
    }

    private String current;
    private int inARow;
    private long lastLoggedMs;
    private long suppressed;

    /** Records a skipped pass at {@code nowMs}; returns the line to log, or null. */
    Line onSkip(String reason, String detail, long nowMs) {
        String text = "Database retention skipped, nothing more deleted: " + reason + " (" + detail + ")";
        if (!reason.equals(current)) {
            current = reason;
            inARow = 1;
            suppressed = 0;
            lastLoggedMs = nowMs;
            return new Line(true, text);
        }
        inARow++;
        if (nowMs - lastLoggedMs >= REPEAT_INTERVAL_MS) {
            String line = text + "; still skipping after " + inARow + " passes, " + suppressed + " not logged";
            lastLoggedMs = nowMs;
            suppressed = 0;
            return new Line(true, line);
        }
        suppressed++;
        return null;
    }

    /** Records a pass that was not skipped; returns the INFO line after skips, or null. */
    Line onRun() {
        if (current == null) return null;
        Line line = new Line(false, "Database retention runs again; " + current + " cleared after " + inARow
                + " skipped pass(es)");
        current = null;
        inARow = 0;
        suppressed = 0;
        return line;
    }
}
