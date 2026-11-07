package reporter;

import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import io.aeron.logbuffer.ControlledFragmentHandler;
import journal.Journal;
import org.agrona.CloseHelper;

/**
 * One followed replay of a recording on the Reporter's own replay stream: it opens only once the
 * recording holds something past {@code position}, so an idle venue has no replay, and it follows the
 * live recording from then on. Closing the subscription ends the Archive's replay session.
 *
 * Polling never blocks and a slow reader back-pressures only its replay, never the recording. Only the
 * Reporter thread uses this.
 */
final class RecordingFollower implements AutoCloseable {

    private final AeronArchive archive;
    private final long recordingId;
    private final int replayStreamId;
    private long position;
    private Subscription subscription;
    private Image image;

    RecordingFollower(AeronArchive archive, long recordingId, int replayStreamId, long position) {
        this.archive = archive;
        this.recordingId = recordingId;
        this.replayStreamId = replayStreamId;
        this.position = position;
    }

    /** Where the next read starts. */
    long position() {
        return (image != null) ? image.position() : position;
    }

    /**
     * Delivers what is ready.
     *
     * @param limit the recording's position now; nothing happens until it is past {@link #position()}
     * @return the fragments delivered
     * @throws ReporterInconsistencyException if the Archive refuses this position
     * @throws IllegalStateException if the replay ended under us; a reopen from the checkpoint recovers
     */
    int poll(ControlledFragmentHandler handler, int fragmentLimit, long limit) {
        if (image == null) {
            if (limit <= position) return 0;
            if (subscription == null) {
                open();
            }
            if (subscription.imageCount() == 0) return 0;
            image = subscription.imageAtIndex(0);
        }
        int fragments = image.controlledPoll(handler, fragmentLimit);
        if (fragments == 0 && image.isClosed()) {
            throw new IllegalStateException("The replay of recording " + recordingId + " on stream " + replayStreamId
                    + " ended at position " + image.position());
        }
        return fragments;
    }

    private void open() {
        try {
            subscription = archive.replay(recordingId, position, AeronArchive.REPLAY_ALL_AND_FOLLOW,
                    Journal.IPC_CHANNEL, replayStreamId);
        } catch (ArchiveException e) {
            throw new ReporterInconsistencyException("The Archive refuses a replay of recording " + recordingId
                    + " at position " + position + ": " + e.getMessage() + ". This database's checkpoint does not match"
                    + " this journal.", e);
        }
    }

    @Override
    public void close() {
        if (image != null) {
            position = image.position();
        }
        image = null;
        CloseHelper.quietClose(subscription);
        subscription = null;
    }
}
