package recovery;

/**
 * Startup recovery cannot continue: the snapshot and the journal disagree, or the journal itself
 * cannot be replayed (P15-5, SRS §3.10). The message states what was found and what the operator
 * does about it. Main refuses to start (exit 2); nothing is repaired automatically, because the
 * journal is the authority and every remedy loses or discards something an operator must choose.
 */
public final class RecoveryException extends IllegalStateException {

    public RecoveryException(String message) {
        super(message);
    }

    public RecoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
