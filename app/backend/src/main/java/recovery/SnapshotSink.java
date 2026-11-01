package recovery;

import org.agrona.DirectBuffer;

import java.io.IOException;

/**
 * Where an encoded snapshot body goes: {@link SnapshotStore} in production. A seam so a test can
 * stall the write and prove the engine thread never waits for it.
 */
@FunctionalInterface
public interface SnapshotSink {

    /** Persists the snapshot body {@code [0, length)} of {@code body}, taken after inbound seq {@code seq}. */
    void write(DirectBuffer body, int length, long seq) throws IOException;
}
