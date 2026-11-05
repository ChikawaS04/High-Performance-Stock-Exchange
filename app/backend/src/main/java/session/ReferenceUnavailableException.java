package session;

/** The reference source cannot answer now, and waiting may fix it. No session opens until it can. */
public final class ReferenceUnavailableException extends RuntimeException {

    public ReferenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
