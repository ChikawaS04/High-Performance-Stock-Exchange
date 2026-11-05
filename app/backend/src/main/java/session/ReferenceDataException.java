package session;

/** The reference source answered with something unusable, and waiting does not fix it. */
public final class ReferenceDataException extends RuntimeException {

    public ReferenceDataException(String message) {
        super(message);
    }

    public ReferenceDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
