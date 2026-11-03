package recovery;

import engine.AppliedListener;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;

import java.io.IOException;

/**
 * Decides when the engine thread takes a snapshot (P15-5, SRS §3.10), and takes it.
 *
 * Triggers: every {@code interval} applied inbound commands (seq a multiple of the interval),
 * and {@link #requestSnapshot()}, the session-close hook that P15-8 calls while the handler
 * applies SESSION_CLOSE. Graceful shutdown uses {@link #writeFinal} instead.
 *
 * Consistency. {@link #onApplied} runs on the applying thread between two commands, so the
 * state it encodes is exactly the state after {@code seq}, at recording position
 * {@code position}. Encoding is the only work done here: no I/O, no logging. If the writer is
 * still busy with the previous snapshot, nothing waits: the snapshot stays pending and is taken
 * after the first command applied once the writer is idle, at that command's seq.
 *
 * An encode failure is handed to the writer to log and the pending flag is cleared, so a bug in
 * snapshotting can never stop matching; the next trigger tries again.
 */
public final class SnapshotScheduler implements AppliedListener {

    private final MatchingEngine engine;
    private final MatchingEngineHandler handler;
    private final SnapshotWriter writer;
    private final SnapshotStore store;
    private final long interval;
    private final long recordingId;
    private final SnapshotCodec codec = new SnapshotCodec();

    /** Applying thread only. */
    private boolean pending;

    /** Scratch for the handler's session symbol; used only while the applying thread is between commands or stopped. */
    private final byte[] symbol = new byte[8];

    /**
     * @param store       the snapshot directory; the shutdown snapshot is written here directly
     * @param interval    applied commands between periodic snapshots ({@code SNAPSHOT_INTERVAL})
     * @param recordingId the inbound recording every position refers to
     */
    public SnapshotScheduler(MatchingEngine engine, MatchingEngineHandler handler, SnapshotWriter writer,
                             SnapshotStore store, long interval, long recordingId) {
        if (interval < 1) throw new IllegalArgumentException("interval must be >= 1, got " + interval);
        this.engine = engine;
        this.handler = handler;
        this.writer = writer;
        this.store = store;
        this.interval = interval;
        this.recordingId = recordingId;
    }

    private byte[] symbol() {
        handler.getSessionSymbol(symbol);
        return symbol;
    }

    /**
     * The session-close hook: snapshot after the command being applied. {@code JournaledPipeline}
     * installs it as the handler's session-close hook (P15-8), so the snapshot is taken at the
     * SESSION_CLOSE's seq and holds the closed state. Applying thread only.
     */
    public void requestSnapshot() {
        pending = true;
    }

    @Override
    public void onApplied(long seq, long position) {
        if (seq % interval == 0) {
            pending = true;
        }
        if (pending && writer.isIdle()) {
            pending = false;
            try {
                int length = codec.encode(writer.buffer(), engine, seq, position, recordingId,
                        handler.getLastSeq(), handler.getCommandTimestamp(), handler.isSessionOpen(),
                handler.getBusinessDate(), handler.getLastClosedBusinessDate(), symbol());
                writer.submit(length, seq);
            } catch (Throwable t) {
                writer.reportEncodeFailure(t);
            }
        }
    }

    /**
     * The graceful-shutdown snapshot (SRS §3.10), written to the snapshot directory on the calling
     * thread, after the applying thread has stopped at {@code seq}. Skipped when nothing was ever
     * applied or when a snapshot of this seq already exists (a restart with no new commands).
     *
     * @return true if a snapshot of {@code seq} exists afterwards (or none was needed), false if
     *         the writer was still busy after {@code timeoutMs}
     * @throws IOException if the file could not be written
     */
    public boolean writeFinal(long seq, long position, long timeoutMs) throws IOException {
        if (seq == 0 || store.exists(seq)) {
            return true;
        }
        if (!writer.awaitIdle(timeoutMs)) {
            return false;
        }
        if (store.exists(seq)) {
            return true;              // the periodic snapshot in flight was this very seq
        }
        int length = codec.encode(writer.buffer(), engine, seq, position, recordingId,
                handler.getLastSeq(), handler.getCommandTimestamp(), handler.isSessionOpen(),
                handler.getBusinessDate(), handler.getLastClosedBusinessDate(), symbol());
        store.write(writer.buffer(), length, seq);
        return true;
    }
}
