package clearing;

/**
 * A rule clearing relies on is broken, so retrying cannot help: clearing stops, nothing for the date is
 * written, no later date is cleared, and the venue keeps trading (P15-11).
 */
public final class ClearingInvariantException extends RuntimeException {

    public ClearingInvariantException(String message) {
        super(message);
    }

    public ClearingInvariantException(String message, Throwable cause) {
        super(message, cause);
    }
}
