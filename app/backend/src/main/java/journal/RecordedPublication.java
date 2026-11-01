package journal;

import io.aeron.ExclusivePublication;

/**
 * An IPC publication whose stream is being recorded by the Archive. Only
 * {@link Journal#openRecordedPublication(int)} creates one, and only after the Archive's
 * recording counter for this exact publication session is live, so anything offered to
 * {@code publication} is recorded.
 *
 * @param recordingId             the stream's one continuous recording (D10)
 * @param publication             the stream's single writer's publication
 * @param recordingSubscriptionId the Archive's recording subscription, for stopping it
 * @param startPosition           where the recording starts
 * @param joinPosition            where this run's publication joins the recording: the stop
 *                                position extended from, or {@code startPosition} for a new
 *                                recording. Everything before it was journaled by earlier runs.
 * @param termBufferLength        the recording's term length (an extension keeps the original)
 * @param extended               true if an existing recording was extended
 */
public record RecordedPublication(
        long recordingId,
        ExclusivePublication publication,
        long recordingSubscriptionId,
        long startPosition,
        long joinPosition,
        int termBufferLength,
        boolean extended) {
}
