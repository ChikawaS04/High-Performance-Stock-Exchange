package journal;

/**
 * The catalog entry of one Archive recording, as far as the journal needs it: where it
 * starts and stops, and the publication parameters an extension must reuse (D10).
 *
 * {@code stopPosition} is the Archive's catalogued stop position. For a recording that is
 * not active it is the end of the journal; after a crash the Archive recovers it from the
 * segment files on start, so it is never unknown for an inactive recording.
 */
public record RecordingDescriptor(
        long recordingId,
        long startPosition,
        long stopPosition,
        int initialTermId,
        int segmentFileLength,
        int termBufferLength,
        int mtuLength,
        int streamId) {
}
