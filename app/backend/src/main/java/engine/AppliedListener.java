package engine;

/**
 * Told after every inbound command the engine has fully applied (P15-5): the periodic and
 * requested snapshots hang off this. Called on the thread applying commands (the engine agent,
 * or the startup thread during recovery replay), between two commands, so the engine's state is
 * exactly the state after {@code seq}.
 */
@FunctionalInterface
public interface AppliedListener {

    AppliedListener NO_OP = (seq, position) -> { };

    /**
     * @param seq      the inbound seq just applied
     * @param position the inbound recording position just after that command: where a replay
     *                 that resumes after it starts
     */
    void onApplied(long seq, long position);
}
