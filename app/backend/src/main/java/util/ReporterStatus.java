package util;

/**
 * What {@code GET /health} reports about the Reporter (P15-10). Readable from any thread without a
 * lock. The seqs are the checkpoint as last committed.
 */
public interface ReporterStatus {

    /** STARTING, RUNNING, BACKING_OFF, FAILED or STOPPED. */
    String state();

    /** The last inbound seq covered by the checkpoint. */
    long inboundSeq();

    /** The last outbound seq covered by the checkpoint. */
    long outboundSeq();

    /**
     * The inbound recording position of the committed checkpoint, or -1 while nothing is known to be
     * committed (P16-7: journal retention's Reporter floor; -1 holds retention).
     */
    default long checkpointInboundPosition() {
        return -1L;
    }

    /** The outbound recording position of the committed checkpoint, or -1; as {@link #checkpointInboundPosition()}. */
    default long checkpointOutboundPosition() {
        return -1L;
    }
}
