package journal;

import engine.CommandGate;
import io.aeron.archive.status.RecordingPos;
import org.agrona.concurrent.status.CountersReader;

/**
 * The D11 gate over the Archive's recording position counter for one recording: the engine
 * agent may read the recorded stream up to the position the Archive has written to file.
 *
 * The counter id is resolved once at startup. {@link #limit()} is a single volatile read of the
 * counter; {@link #isLive()} also confirms the counter still belongs to this recording, so a
 * stopped recording or a reclaimed and reused counter id is detected. Neither allocates.
 */
public final class RecordingPositionGate implements CommandGate {

    private final CountersReader counters;
    private final int counterId;
    private final long recordingId;

    RecordingPositionGate(CountersReader counters, int counterId, long recordingId) {
        this.counters = counters;
        this.counterId = counterId;
        this.recordingId = recordingId;
    }

    @Override
    public long limit() {
        return counters.getCounterValue(counterId);
    }

    @Override
    public boolean isLive() {
        return RecordingPos.isActive(counters, counterId, recordingId);
    }

    /** The recording this gate follows. */
    public long recordingId() {
        return recordingId;
    }
}
