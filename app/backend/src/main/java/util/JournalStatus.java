package util;

/**
 * What {@code GET /health} reports about the journal and its retention (P16-7, R8): one immutable
 * value, replaced as a whole by the retention thread, so the event loop reads it with one volatile
 * read and never computes anything (disk sizes included).
 *
 * @param inbound    the inbound recording
 * @param outbound   the outbound recording
 * @param enabled    whether retention purges anything ({@code JOURNAL_RETENTION_ENABLED})
 * @param days       {@code JOURNAL_RETENTION_DAYS}
 * @param closesSeen distinct close dates among the valid close snapshots; the window starts once
 *                   there are {@code days + 1}
 * @param lastRunTs  when the last retention run ended, epoch nanoseconds, or -1 if none has run
 * @param outcome    {@link #NOT_RUN}, {@link #PURGED}, {@link #NOTHING_TO_PURGE}, {@link #SKIPPED} or
 *                   {@link #DISABLED}
 * @param skipReason why the last run purged nothing on doubt, or null
 * @param asOfTs     when this value was computed, epoch nanoseconds
 */
public record JournalStatus(
        Recording inbound,
        Recording outbound,
        boolean enabled,
        int days,
        int closesSeen,
        long lastRunTs,
        String outcome,
        String skipReason,
        long asOfTs) {

    public static final String NOT_RUN = "NOT_RUN";
    public static final String PURGED = "PURGED";
    public static final String NOTHING_TO_PURGE = "NOTHING_TO_PURGE";
    public static final String SKIPPED = "SKIPPED";
    public static final String DISABLED = "DISABLED";

    /**
     * One recording as last seen by the retention thread.
     *
     * @param recordingId      the Archive's recording id
     * @param startPosition    where the recording now starts (moves forward with each purge)
     * @param recordedPosition how far the Archive has recorded
     * @param bytesOnDisk      the total size of its segment files
     */
    public record Recording(long recordingId, long startPosition, long recordedPosition, long bytesOnDisk) {
    }
}
