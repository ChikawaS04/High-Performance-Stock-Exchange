package session;

/**
 * Where the session controller gets reference data and the trading calendar (SRS §3.12): the
 * database when {@code DB_URL} is set, configuration otherwise. Whichever it is, the values pass
 * through {@code engine.SessionRules} before anything is journaled, and travel inside SESSION_OPEN,
 * so the engine and replay need neither source.
 *
 * Called on the startup thread and then the session scheduler's thread, never on a Netty event
 * loop or the engine thread: a load may block on the database.
 */
public interface ReferenceSource {

    /**
     * Reads what a session on {@code businessDate} would use.
     *
     * @throws ReferenceUnavailableException if the source cannot answer now; waiting may fix it
     * @throws ReferenceDataException        if the source answers with something unusable; waiting does not fix it
     */
    SessionDay load(int businessDate);

    /**
     * Checks at startup whatever needs no I/O, so an unusable configuration is refused even on a day
     * that needs no open. Nothing by default.
     *
     * @throws ReferenceDataException if the source can already tell it is unusable
     */
    default void checkAtStartup() {
    }

    /** What an operator should change when {@code engine.SessionRules} refuses this source's values. */
    String remedy();
}
