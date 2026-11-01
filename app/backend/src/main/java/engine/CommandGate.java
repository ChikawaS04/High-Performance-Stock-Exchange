package engine;

/**
 * How far the engine agent may read the inbound stream (D11, SRS §5.7): the position up to
 * which the Archive has recorded it. The agent never processes a command that ends beyond
 * {@link #limit()}, so it never matches an unjournaled command.
 *
 * Implemented by the journal over the Archive's recording position counter; tests hold it
 * back to prove the gate. Both methods are called on the agent thread and must not allocate.
 */
public interface CommandGate {

    /** The stream position up to which commands are journaled. */
    long limit();

    /**
     * False once the gate can no longer advance (the recording stopped or its counter was
     * reclaimed). Checked by the agent when a poll finds nothing, so a dead recording halts
     * the venue instead of stalling it silently.
     */
    boolean isLive();
}
