package journal;

import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

/**
 * Reads recorded journal streams back through Archive replays. Used at startup (the
 * sequencer's tail read, recovery's replay) and by tests; never on the hot path, so
 * allocation here is acceptable.
 *
 * A replay is delivered on {@link Journal#REPLAY_STREAM_ID} over IPC, which no recording
 * subscribes to, so reading the journal never writes to it. Not thread-safe: the Archive
 * client is used from one thread at a time.
 */
public final class JournalReader {

    private static final int FRAGMENT_LIMIT = 256;

    private final AeronArchive archive;

    public JournalReader(AeronArchive archive) {
        this.archive = archive;
    }

    /**
     * Replays the bytes of {@code recordingId} in {@code [from, to)} to {@code handler}, one
     * call per message, in journal order. {@code from} must be a frame boundary (a term start
     * or a position a previous replay ended at). Returns once the replay reaches {@code to};
     * throws if it does not within {@link Journal#AWAIT_TIMEOUT_MS} or if the replay ends short.
     *
     * @return the number of messages delivered
     */
    public long replay(long recordingId, long from, long to, FragmentHandler handler) {
        if (to <= from) return 0;
        long[] count = {0};
        FragmentHandler counting = (buffer, offset, length, header) -> {
            count[0]++;
            handler.onFragment(buffer, offset, length, header);
        };
        IdleStrategy idle = new SleepingMillisIdleStrategy(1);
        long deadline = System.currentTimeMillis() + Journal.AWAIT_TIMEOUT_MS;
        try (Subscription subscription = archive.replay(
                recordingId, from, to - from, Journal.IPC_CHANNEL, Journal.REPLAY_STREAM_ID)) {
            Image image = null;
            while (image == null || image.position() < to) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("Replay of recording " + recordingId + " [" + from + ", " + to
                            + ") timed out at position " + (image == null ? from : image.position()));
                }
                if (image == null) {
                    if (subscription.imageCount() > 0) image = subscription.imageAtIndex(0);
                    else idle.idle();
                    continue;
                }
                int fragments = image.poll(counting, FRAGMENT_LIMIT);
                if (fragments == 0) {
                    if (image.isClosed() && image.position() < to) {
                        throw new IllegalStateException("Replay of recording " + recordingId
                                + " ended at " + image.position() + ", short of " + to);
                    }
                    idle.idle(fragments);
                }
            }
        }
        return count[0];
    }

    /**
     * Applies each message of {@code recordingId} in {@code [from, to)} to {@code applier}, in
     * journal order, and stops at the first failure (P15-5 recovery). Aeron's image poll catches
     * a handler's exception and moves past the fragment, so the handler here never lets one
     * escape: it records it, aborts (the image stays on the failing message), and this method
     * rethrows it with the position. Nothing is ever skipped. Same bounds and frame-boundary rule
     * as {@link #replay}, except the deadline restarts whenever a message is applied, so a long
     * replay is bounded by stalls, not by its length.
     *
     * @return the number of messages applied
     * @throws IllegalStateException wrapping the applier's failure, or on a stall or short replay
     */
    public long replayFailFast(long recordingId, long from, long to, PositionedHandler applier) {
        if (to <= from) return 0;
        long[] count = {0};
        Throwable[] failure = {null};
        ControlledFragmentHandler guarded = (buffer, offset, length, header) -> {
            try {
                applier.onMessage(buffer, offset, header.position());
                count[0]++;
                return ControlledFragmentHandler.Action.CONTINUE;
            } catch (Throwable t) {
                failure[0] = t;
                return ControlledFragmentHandler.Action.ABORT;
            }
        };
        IdleStrategy idle = new SleepingMillisIdleStrategy(1);
        long deadline = System.currentTimeMillis() + Journal.AWAIT_TIMEOUT_MS;
        try (Subscription subscription = archive.replay(
                recordingId, from, to - from, Journal.IPC_CHANNEL, Journal.REPLAY_STREAM_ID)) {
            Image image = null;
            while (image == null || image.position() < to) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("Replay of recording " + recordingId + " [" + from + ", " + to
                            + ") stalled at position " + (image == null ? from : image.position()));
                }
                if (image == null) {
                    if (subscription.imageCount() > 0) image = subscription.imageAtIndex(0);
                    else idle.idle();
                    continue;
                }
                int fragments = image.controlledPoll(guarded, FRAGMENT_LIMIT);
                if (failure[0] != null) {
                    throw new IllegalStateException("Replay of recording " + recordingId + " failed at position "
                            + image.position() + " after " + count[0] + " messages: " + failure[0].getMessage(),
                            failure[0]);
                }
                if (fragments == 0) {
                    if (image.isClosed() && image.position() < to) {
                        throw new IllegalStateException("Replay of recording " + recordingId
                                + " ended at " + image.position() + ", short of " + to);
                    }
                    idle.idle(fragments);
                } else {
                    deadline = System.currentTimeMillis() + Journal.AWAIT_TIMEOUT_MS;
                }
            }
        }
        return count[0];
    }

    /** One recorded message and the recording position just after it. */
    @FunctionalInterface
    public interface PositionedHandler {
        void onMessage(DirectBuffer buffer, int offset, long endPosition);
    }

    /** Replays the whole of {@code recordingId}, start to its current end. */
    public long replayAll(long recordingId, FragmentHandler handler) {
        return replay(recordingId, archive.getStartPosition(recordingId),
                archive.getMaxRecordedPosition(recordingId), handler);
    }

    /**
     * Where to start a replay that must include the last message before {@code end}: the start
     * of the term holding position {@code end - 1}, but never before the recording's start.
     * Term starts are always frame boundaries (padding fills the end of a term), and an
     * extension keeps the recording's initial term id and term length, so term starts sit at
     * multiples of {@code termLength} for the whole recording. A tail read therefore costs at
     * most one term, however long the journal is.
     */
    public static long tailStart(long recordingStart, long end, int termLength) {
        if (end <= recordingStart) return recordingStart;
        long termStart = ((end - 1) / termLength) * (long) termLength;
        return Math.max(recordingStart, termStart);
    }
}
