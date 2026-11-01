package recovery;

/**
 * A snapshot file that must not be used (D14): bad checksum, truncated, unknown version, or
 * contents that cannot be a resting book. Recovery logs the reason and tries the next older
 * snapshot.
 */
public final class InvalidSnapshotException extends Exception {

    public InvalidSnapshotException(String reason) {
        super(reason);
    }

    public InvalidSnapshotException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
