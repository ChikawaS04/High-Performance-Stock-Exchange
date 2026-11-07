package util;

/**
 * What {@code GET /health} reports about end-of-day clearing (P15-11). Readable from any thread without
 * a lock; each value is a volatile the clearing thread publishes.
 */
public interface ClearingStatus {

    /** STARTING, IDLE, WAITING, HELD, BACKING_OFF, FAILED or STOPPED. */
    String state();

    /** The latest business date with a COMPLETE run, or -1 if there is none yet. */
    int lastClearedDate();

    /** The earliest closed business date not yet cleared, or -1 if none is known. */
    int pendingDate();

    /** What holds clearing back, such as {@code "reporter FAILED"}, or null. */
    String heldBy();
}
