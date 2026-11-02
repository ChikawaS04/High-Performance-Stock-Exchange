package event;

/**
 * The venue's recorded execution history, as RESEND reads it (SRS §3.6, D16). Implemented over
 * the outbound recording by {@code journal.OutboundHistory}; the network layer depends only on
 * this interface, so it never touches Aeron.
 */
public interface ExecutionHistory {

    /**
     * The highest outbound seq published so far, readable from any thread. Every event up to it
     * has been or is about to be broadcast, and will be recorded.
     */
    long publishedSeq();

    /**
     * Reads the history in seq order and hands every event with {@code seq >= fromSeq} to the
     * visitor, until the history has been read at least to {@code throughSeq} and to the end of
     * what was recorded when the call began. Runs on the caller's thread and blocks while waiting
     * for events still to be recorded, bounded by a stall timeout.
     *
     * @throws IllegalStateException if the history cannot be read, stalls, or the visitor throws
     */
    Result replay(long fromSeq, long throughSeq, Visitor visitor);

    /** Receives each event. The event is a reused carrier: use it inside the call only. */
    interface Visitor {
        /** @return false to stop the replay now */
        boolean onEvent(ExecutionEvent event);

        /** Asked while waiting for more events; false stops the replay. */
        default boolean keepWaiting() {
            return true;
        }
    }

    /**
     * @param lastSeq the last seq read (sent or skipped), 0 if the history was empty
     * @param count   events handed to the visitor
     * @param stopped true if the visitor stopped the replay before the end
     */
    record Result(long lastSeq, long count, boolean stopped) {
    }
}
