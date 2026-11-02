package journal;

import codec.ExecutionEventCodec;
import event.ExecutionEvent;
import event.ExecutionHistory;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

import java.util.function.LongSupplier;

/**
 * The outbound recording read back for RESEND (D16): a linear Archive replay from the recording's
 * start, skipping to {@code fromSeq}. Replays are delivered on {@link Journal#RESEND_STREAM_ID},
 * which nothing records and no startup replay uses.
 *
 * The replay follows the live recording ({@link AeronArchive#REPLAY_ALL_AND_FOLLOW}) and stops
 * once it has passed both the position recorded when the call began and {@code throughSeq}, so a
 * caller can ask for every event already broadcast, including one the recorder has not written
 * yet. Closing the replay subscription ends the Archive's replay session.
 *
 * Not on the hot path: one caller thread at a time (the resend thread), and allocation per call
 * is acceptable. The Archive client's control calls are locked, so sharing it with startup and
 * shutdown is safe; the resend thread is stopped before the journal closes.
 */
public final class OutboundHistory implements ExecutionHistory {

    /** Bound on a replay making no progress. */
    static final long STALL_TIMEOUT_MS = 10_000L;
    private static final int FRAGMENT_LIMIT = 64;

    private final AeronArchive archive;
    private final long recordingId;
    private final long startPosition;
    private final RecordingPositionGate recordedPosition;
    private final LongSupplier publishedSeq;
    private final ExecutionEventCodec codec = new ExecutionEventCodec();
    private final ExecutionEvent scratch = new ExecutionEvent();

    /**
     * @param archive          the journal's Archive client
     * @param recorded         the outbound recording
     * @param recordedPosition the Archive's recorded position for it
     * @param publishedSeq     the highest seq published on the outbound ring
     */
    public OutboundHistory(AeronArchive archive, RecordedPublication recorded,
                           RecordingPositionGate recordedPosition, LongSupplier publishedSeq) {
        this.archive = archive;
        this.recordingId = recorded.recordingId();
        this.startPosition = recorded.startPosition();
        this.recordedPosition = recordedPosition;
        this.publishedSeq = publishedSeq;
    }

    @Override
    public long publishedSeq() {
        return publishedSeq.getAsLong();
    }

    @Override
    public Result replay(long fromSeq, long throughSeq, Visitor visitor) {
        final long endPosition = recordedPosition.limit();
        if (endPosition <= startPosition && throughSeq <= 0) {
            return new Result(0, 0, false);
        }
        final long[] state = {0L, 0L};          // last seq read, count sent
        final boolean[] stopped = {false};
        final Throwable[] failure = {null};
        ControlledFragmentHandler handler = (buffer, offset, length, header) -> {
            try {
                codec.decode(buffer, offset, scratch);
                state[0] = scratch.seq;
                if (scratch.seq >= fromSeq) {
                    if (!visitor.onEvent(scratch)) {
                        stopped[0] = true;
                        return ControlledFragmentHandler.Action.BREAK;
                    }
                    state[1]++;
                }
                return done(header.position(), state[0], endPosition, throughSeq)
                        ? ControlledFragmentHandler.Action.BREAK
                        : ControlledFragmentHandler.Action.CONTINUE;
            } catch (Throwable t) {
                // The image poll would report and skip a fragment whose handler throws; never let one escape.
                failure[0] = t;
                return ControlledFragmentHandler.Action.ABORT;
            }
        };

        IdleStrategy idle = new SleepingMillisIdleStrategy(1);
        long deadline = System.currentTimeMillis() + STALL_TIMEOUT_MS;
        try (Subscription subscription = archive.replay(recordingId, startPosition,
                AeronArchive.REPLAY_ALL_AND_FOLLOW, Journal.IPC_CHANNEL, Journal.RESEND_STREAM_ID)) {
            Image image = null;
            while (true) {
                if (image == null) {
                    if (subscription.imageCount() > 0) {
                        image = subscription.imageAtIndex(0);
                        continue;
                    }
                } else {
                    int fragments = image.controlledPoll(handler, FRAGMENT_LIMIT);
                    if (failure[0] != null) {
                        throw new IllegalStateException("Resend replay of outbound recording " + recordingId
                                + " failed after seq " + state[0] + ": " + failure[0], failure[0]);
                    }
                    if (stopped[0]) {
                        return new Result(state[0], state[1], true);
                    }
                    if (done(image.position(), state[0], endPosition, throughSeq)) {
                        return new Result(state[0], state[1], false);
                    }
                    if (fragments > 0) {
                        deadline = System.currentTimeMillis() + STALL_TIMEOUT_MS;
                        continue;
                    }
                    if (image.isClosed()) {
                        throw new IllegalStateException("Resend replay of outbound recording " + recordingId
                                + " ended at seq " + state[0] + ", short of seq " + throughSeq
                                + " and position " + endPosition);
                    }
                }
                if (!visitor.keepWaiting()) {
                    return new Result(state[0], state[1], true);
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("Resend replay of outbound recording " + recordingId
                            + " stalled at seq " + state[0] + " waiting for seq " + throughSeq
                            + " and position " + endPosition);
                }
                idle.idle();
            }
        }
    }

    private static boolean done(long position, long lastSeq, long endPosition, long throughSeq) {
        return position >= endPosition && lastSeq >= throughSeq;
    }
}
