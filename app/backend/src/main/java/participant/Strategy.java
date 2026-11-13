package participant;

import java.util.List;

/**
 * A trading algorithm. Single-thread-confined: {@link ParticipantClient} serializes every
 * {@link #onTick}, {@link #onExec} and {@link #onSessionClose} call onto one executor thread, so
 * an implementation needs no synchronization on its own state. {@code onTick} returns the orders
 * to send this tick (possibly empty); {@code onExec} sees every EXEC on the venue, the bot's own
 * and everyone else's, so a strategy can follow its orders and the market's trades. The strategy
 * assigns its own clOrdIds inside its id block.
 *
 * <p>The client calls {@code onTick} only while the session is open.
 */
public interface Strategy {

    /** Human label for logs (e.g. "MAKER"). */
    String name();

    /** Which id block this strategy draws from. */
    Bots.Owner owner();

    /** Decide what to send given the current venue snapshot and wall-clock millis. */
    List<Action> onTick(MarketView view, long nowMillis);

    /** React to an execution report (default: ignore). */
    default void onExec(ExecReport exec) { }

    /**
     * The session closed, or the bot is stopping: return cancels for everything this strategy
     * still has resting, re-sending any cancel already in flight. Called again while
     * {@link #hasResting} stays true, so it must not assume its earlier cancels landed. Default:
     * nothing, for a strategy that never rests.
     */
    default List<Action> onSessionClose() {
        return List.of();
    }

    /** True while the venue has not confirmed that every order this strategy rested is gone. */
    default boolean hasResting() {
        return false;
    }
}
