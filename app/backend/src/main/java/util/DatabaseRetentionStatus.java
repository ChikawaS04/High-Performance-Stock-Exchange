package util;

/**
 * What {@code GET /health} reports about database retention (P16-8): one immutable value, replaced as a
 * whole by the retention thread, so the event loop reads it with one volatile read.
 *
 * @param enabled       {@code DB_RETENTION_ENABLED}
 * @param days          {@code DB_RETENTION_DAYS}
 * @param lastRunTs     when the last pass ended, epoch nanoseconds, or -1 if none has run
 * @param outcome       {@link #NOT_RUN}, {@link #PURGED}, {@link #NOTHING_TO_PURGE}, {@link #SKIPPED},
 *                      {@link #INTERRUPTED} or {@link #DISABLED}
 * @param skipReason    why the last pass stopped on doubt, or null
 * @param purgedThrough the newest business date whose purge completed, or -1
 * @param lastPurgeTs   when a pass last completed a date, epoch nanoseconds, or -1; kept across later passes
 *                      that purge nothing
 * @param rowsDeleted   rows the last pass deleted
 * @param asOfTs        when this value was computed, epoch nanoseconds
 */
public record DatabaseRetentionStatus(
        boolean enabled,
        int days,
        long lastRunTs,
        String outcome,
        String skipReason,
        int purgedThrough,
        long lastPurgeTs,
        long rowsDeleted,
        long asOfTs) {

    public static final String NOT_RUN = "NOT_RUN";
    public static final String PURGED = "PURGED";
    public static final String NOTHING_TO_PURGE = "NOTHING_TO_PURGE";
    public static final String SKIPPED = "SKIPPED";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String DISABLED = "DISABLED";
}
