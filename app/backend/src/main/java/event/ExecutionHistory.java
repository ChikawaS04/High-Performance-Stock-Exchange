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
     * @param lastSeq  the last seq read (sent or skipped), 0 if the history was empty
     * @param count    events handed to the visitor
     * @param stopped  true if the visitor stopped the replay before the end
     * @param firstSeq the first seq the history still holds, as read by this call (P16-7: journal
     *                 retention removes the oldest events), or 0 if nothing was read
     * @param scanned  events read from the history, sent or skipped (P16-12: the linear read reads
     *                 from the start of the history whatever {@code fromSeq} is)
     * @param bytesRead bytes of the history read, from {@code startPosition}
     * @param startPosition where the read began, -1 if not reported
     */
    record Result(long lastSeq, long count, boolean stopped, long firstSeq, long scanned, long bytesRead,
                  long startPosition) {

        /** A result that does not report the first seq. */
        public Result(long lastSeq, long count, boolean stopped) {
            this(lastSeq, count, stopped, 0L);
        }

        /** A result that does not report how much was read. */
        public Result(long lastSeq, long count, boolean stopped, long firstSeq) {
            this(lastSeq, count, stopped, firstSeq, 0L, 0L, -1L);
        }
    }
}
