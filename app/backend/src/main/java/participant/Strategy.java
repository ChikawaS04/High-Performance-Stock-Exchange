package participant;

import java.util.List;

/**
 * A trading algorithm. Pure-ish and single-thread-confined: {@link ParticipantClient}
 * serializes every {@link #onTick} and {@link #onExec} call onto one executor thread, so an
 * implementation needs no synchronization on its own state. {@code onTick} returns the orders
 * to send this tick (possibly empty); {@code onExec} lets a resting-order strategy react to
 * its own fills/cancels. The strategy assigns its own clOrdIds inside its attribution range.
 */
public interface Strategy {

    /** Human label for logs (e.g. "MAKER"). */
    String name();

    /** Inclusive lower bound of this strategy's clOrdId range; used for attribution/debug. */
    long rangeBase();

    /** Decide what to send given the current venue snapshot and wall-clock millis. */
    List<Action> onTick(MarketView view, long nowMillis);

    /** React to an execution report (default: ignore). Only a resting strategy needs this. */
    default void onExec(ExecReport exec) { }
}
