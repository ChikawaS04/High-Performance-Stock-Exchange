package util;

/**
 * What {@code GET /health} reports about the disk (P16-8): one immutable value, replaced as a whole by the
 * disk guard's thread, so the event loop reads it with one volatile read and never touches a file.
 *
 * <p>{@code freeBytes} and {@code totalBytes} are of the <b>filesystem that holds the data directory</b>
 * ({@code EXCHANGE_DATA_DIR}), as usable by the venue's user. On the server the data directory is a bind
 * mount on the root disk, which also holds Docker's own storage (PostgreSQL's volume, images, container
 * logs), so {@code freeBytes} falls when any of them grows. {@code dataBytes} is the data directory alone
 * (journal and snapshots); the container cannot see PostgreSQL's size.
 *
 * @param freeBytes        usable bytes left on that filesystem, or -1 if it could not be read yet
 * @param totalBytes       that filesystem's size, or -1
 * @param dataBytes        bytes under the data directory, or -1 before the first walk
 * @param warnBelowBytes   {@code DISK_WARN_FREE_BYTES}
 * @param stopBelowBytes   {@code DISK_STOP_FREE_BYTES} (0: the stop is off)
 * @param state            {@link #OK}, {@link #LOW} or {@link #STOP}
 * @param newOrdersRefused NEW orders refused by the hard stop since start
 * @param retentionHeld    what holds journal or database retention back, or null
 * @param asOfTs           when this value was computed, epoch nanoseconds
 */
public record DiskStatus(
        long freeBytes,
        long totalBytes,
        long dataBytes,
        long warnBelowBytes,
        long stopBelowBytes,
        String state,
        long newOrdersRefused,
        String retentionHeld,
        long asOfTs) {

    /** Free space at or above the warning threshold. */
    public static final String OK = "OK";
    /** Below the warning threshold; NEW is still accepted. */
    public static final String LOW = "LOW";
    /** The hard stop holds: NEW is refused, cancels are accepted. */
    public static final String STOP = "STOP";
}
