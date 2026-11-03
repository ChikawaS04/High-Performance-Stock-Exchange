package session;

/**
 * The session controller refused to start the venue (P15-8): the configuration names reference
 * data the engine would refuse, the recovered session's date is after today, or the engine did
 * not apply a session command in time. The message says what to do; Main exits with status 2.
 */
public final class SessionStartupException extends RuntimeException {

    public SessionStartupException(String message) {
        super(message);
    }

    public SessionStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
