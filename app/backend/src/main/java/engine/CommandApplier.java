package engine;

import codec.OrderEventCodec;
import event.OrderEvent;
import org.agrona.DirectBuffer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The one path from a journaled command's bytes to the engine (P15-5, SRS §5.8): decode into a
 * reused {@link OrderEvent}, check the seq is exactly {@code appliedSeq + 1}, hand it to the
 * {@link CommandHandler}, record progress, tell the {@link AppliedListener}.
 *
 * The live {@link EngineAgent} and startup recovery's replay both drive the same instance, one
 * after the other: recovery replays the journal tail through it on the startup thread, then the
 * agent's thread continues from wherever replay left it (Thread.start orders the two). Recovery
 * therefore cannot decode, check or dispatch differently from live matching, and the agent can
 * never be started at the wrong seq.
 *
 * A gap, a repeat, an undecodable command or a handler failure is thrown to the caller, which
 * decides how to stop (the agent fail-stops; recovery refuses startup). Nothing is skipped.
 *
 * Zero allocation per command (SRS §5.1): a flyweight codec, one carrier, primitive state.
 * Single-threaded at any one time.
 */
public final class CommandApplier {

    private final CommandHandler handler;
    private final AppliedListener listener;
    private final OrderEventCodec codec = new OrderEventCodec();
    private final OrderEvent command = new OrderEvent();
    private final AtomicLong appliedSeq;
    private final AtomicLong appliedPosition;

    /** Applying thread only: mirrors {@link #appliedSeq}. */
    private long lastSeq;

    /**
     * @param handler         processes each decoded command
     * @param listener        told after each applied command
     * @param appliedSeq      the seq already applied to the engine's state (0 for an empty book)
     * @param appliedPosition the recording position just after that command (the recording's
     *                        start for an empty book)
     */
    public CommandApplier(CommandHandler handler, AppliedListener listener, long appliedSeq, long appliedPosition) {
        this.handler = handler;
        this.listener = (listener == null) ? AppliedListener.NO_OP : listener;
        this.lastSeq = appliedSeq;
        this.appliedSeq = new AtomicLong(appliedSeq);
        this.appliedPosition = new AtomicLong(appliedPosition);
    }

    /**
     * Applies the command encoded at {@code offset}.
     *
     * @param endPosition the recording position just after this command's frame
     * @throws IllegalStateException if the seq is not {@code appliedSeq + 1}; any decode or
     *                               handler exception propagates unchanged
     */
    public void apply(DirectBuffer buffer, int offset, long endPosition) {
        final long seq = codec.decode(buffer, offset, command);
        if (seq != lastSeq + 1) {
            throw new IllegalStateException("Inbound seq " + seq + " after applied seq " + lastSeq
                    + " at position " + endPosition);
        }
        handler.onCommand(command, seq);
        lastSeq = seq;
        appliedPosition.lazySet(endPosition);
        appliedSeq.lazySet(seq);
        listener.onApplied(seq, endPosition);
    }

    /** The seq of the last command fully applied. Readable from any thread. */
    public long appliedSeq() {
        return appliedSeq.get();
    }

    /**
     * The recording position just after the last applied command. Readable from any thread; read
     * {@link #appliedSeq()} first and this second for a pair that belongs together once applying
     * has stopped.
     */
    public long appliedPosition() {
        return appliedPosition.get();
    }

    /** Applying thread only: the last applied seq without a volatile read. */
    long lastSeq() {
        return lastSeq;
    }
}
