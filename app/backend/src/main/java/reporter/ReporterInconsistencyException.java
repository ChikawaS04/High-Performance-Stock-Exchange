package reporter;

/**
 * The journal and the database cannot both be right, or the journal breaks a rule the engine
 * guarantees. Retrying cannot fix this and skipping would leave a checkpoint claiming a record that
 * does not exist, so the Reporter stops loudly and the venue keeps trading (SRS 5.9).
 */
public final class ReporterInconsistencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReporterInconsistencyException(String message) {
        super(message);
    }

    public ReporterInconsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
