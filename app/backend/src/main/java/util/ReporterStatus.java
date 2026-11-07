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
}
