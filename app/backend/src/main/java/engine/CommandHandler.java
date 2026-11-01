package engine;

import event.OrderEvent;

/**
 * Processes one sequenced inbound command on the engine thread. Implemented by
 * {@link MatchingEngineHandler}; the engine agent drives it with each decoded journal command.
 */
@FunctionalInterface
public interface CommandHandler {

    /** Processes {@code command}. The carrier is reused: copy anything kept past the call. */
    void onCommand(OrderEvent command);
}
