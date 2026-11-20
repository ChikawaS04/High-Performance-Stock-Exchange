package util;

/**
 * The disk guard's bounds (P16-8). Injectable, so tests sample on request and see the hourly lines at once.
 *
 * @param sampleMs        between free-space samples
 * @param dataBytesMs     between walks of the data directory
 * @param repeatMs        a LOW warning, or the STOP error, is repeated at most this often while it lasts
 * @param stopTimeoutMs   how long {@link DiskGuard#close()} waits for the thread
 */
public record DiskGuardTiming(long sampleMs, long dataBytesMs, long repeatMs, long stopTimeoutMs) {

    public static final DiskGuardTiming PRODUCTION = new DiskGuardTiming(5_000L, 60_000L, 60L * 60_000L, 2_000L);
}
