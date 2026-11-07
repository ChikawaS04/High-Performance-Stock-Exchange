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

    /**
     * {@link #onCommand(OrderEvent)} with the command's inbound seq, which is not an
     * {@link OrderEvent} field (SRS 3.2). {@link CommandApplier} always calls this form. A wrapper
     * around another handler must forward it, or the events lose their causing seq (P15-10).
     */
    default void onCommand(OrderEvent command, long seq) {
        onCommand(command);
    }
}
