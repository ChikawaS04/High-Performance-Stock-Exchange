package journal;

/**
 * What journal retention logs when a run purges nothing on doubt (P16-7, R7). Decides; the caller
 * logs. Used by the retention thread only.
 *
 * <ul>
 *   <li>A skip reason that appears, or replaces another, is one WARN.</li>
 *   <li>While the same reason persists it is repeated at most once every {@value #REPEAT_INTERVAL_MS}
 *       ms, with the count of skips not logged since, not once a run forever.</li>
 *   <li>The Archive refusing a purge because a replay (a RESEND) is reading below the new start is
 *       routine on a public site: INFO, until it has happened {@value #REPLAY_WARN_AFTER} times in a
 *       row, then WARN.</li>
 *   <li>A run that is not skipped after skips is one INFO saying the reason cleared.</li>
 * </ul>
 */
final class RetentionSkipLog {

    static final long REPEAT_INTERVAL_MS = 24L * 60L * 60_000L;
    static final int REPLAY_WARN_AFTER = 3;

    enum Level { INFO, WARN }

    /** One line to log. */
    record Line(Level level, String text) {
    }

    private RetentionPlan.Skip current;
    private int inARow;
    private long lastLoggedMs;
    private long suppressed;
    private boolean warnedForCurrent;

    /** Records a skipped run at {@code nowMs}; returns the line to log, or null. */
    Line onSkip(RetentionPlan.Skip reason, String detail, long nowMs) {
        String text = "Journal retention skipped, nothing purged: " + reason + " (" + detail + ")";
        if (reason != current) {
            current = reason;
            inARow = 1;
            suppressed = 0;
            lastLoggedMs = nowMs;
            warnedForCurrent = reason != RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY;
            return new Line(warnedForCurrent ? Level.WARN : Level.INFO, text);
        }
        inARow++;
        if (reason == RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY && !warnedForCurrent) {
            if (inARow >= REPLAY_WARN_AFTER) {
                warnedForCurrent = true;
                lastLoggedMs = nowMs;
                suppressed = 0;
                return new Line(Level.WARN, text + "; " + inARow + " runs in a row");
            }
            lastLoggedMs = nowMs;
            return new Line(Level.INFO, text + "; " + inARow + " runs in a row");
        }
        if (nowMs - lastLoggedMs >= REPEAT_INTERVAL_MS) {
            String line = text + "; still skipping after " + inARow + " runs, " + suppressed + " not logged";
            lastLoggedMs = nowMs;
            suppressed = 0;
            return new Line(Level.WARN, line);
        }
        suppressed++;
        return null;
    }

    /** Records a run that was not skipped; returns the INFO line after skips, or null. */
    Line onRun() {
        if (current == null) return null;
        Line line = new Line(Level.INFO, "Journal retention runs again; " + current + " cleared after " + inARow
                + " skipped run(s)");
        current = null;
        inARow = 0;
        suppressed = 0;
        warnedForCurrent = false;
        return line;
    }
}
