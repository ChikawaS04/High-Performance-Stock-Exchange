package recovery;

import engine.AppliedListener;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;

import java.io.IOException;
import java.util.function.LongSupplier;

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
 * Retention fields (P16-7, schema version 2). A snapshot the close hook requested carries the
 * closed business date, even when it is deferred to a later command, and so does a shutdown
 * snapshot that finds that request still pending. Every snapshot also carries the outbound
 * recording's recorded position at the cut, read from the Archive's counter: at the cut every event
 * up to the handler's last seq has been published to the outbound ring and none beyond it, so the
 * recorded position is at most the position just after that seq (a lower bound, so a window
 * computed from it keeps slightly more, never less). During recovery replay the recording already
 * holds events beyond the regenerated seq, so the position is written as -1 (unknown).
 *
 * An encode failure is handed to the writer to log and the pending flag is cleared, so a bug in
 * snapshotting can never stop matching; the next trigger tries again.
 */
public final class SnapshotScheduler implements AppliedListener {

    private static final LongSupplier UNKNOWN = () -> -1L;

    private final MatchingEngine engine;
    private final MatchingEngineHandler handler;
    private final SnapshotWriter writer;
    private final SnapshotStore store;
    private final long interval;
    private final long recordingId;
    private final long outboundRecordingId;
    private final LongSupplier outboundRecordedPosition;
    private final SnapshotCodec codec = new SnapshotCodec();

    /** Applying thread only. */
    private boolean pending;
    /** The business date a pending close snapshot is for, or -1. Applying thread only. */
    private int pendingCloseDate = -1;

    /** Scratch for the handler's session symbol; used only while the applying thread is between commands or stopped. */
    private final byte[] symbol = new byte[8];

    /**
     * Without the outbound recording: snapshots carry no outbound position.
     *
     * @param store       the snapshot directory; the shutdown snapshot is written here directly
     * @param interval    applied commands between periodic snapshots ({@code SNAPSHOT_INTERVAL})
     * @param recordingId the inbound recording every position refers to
     */
    public SnapshotScheduler(MatchingEngine engine, MatchingEngineHandler handler, SnapshotWriter writer,
                             SnapshotStore store, long interval, long recordingId) {
        this(engine, handler, writer, store, interval, recordingId, -1L, UNKNOWN);
    }

    /**
     * @param outboundRecordingId      the outbound recording
     * @param outboundRecordedPosition the Archive's recorded position of it (P16-7); read once per snapshot
     */
    public SnapshotScheduler(MatchingEngine engine, MatchingEngineHandler handler, SnapshotWriter writer,
                             SnapshotStore store, long interval, long recordingId, long outboundRecordingId,
                             LongSupplier outboundRecordedPosition) {
        if (interval < 1) throw new IllegalArgumentException("interval must be >= 1, got " + interval);
        this.engine = engine;
        this.handler = handler;
        this.writer = writer;
        this.store = store;
        this.interval = interval;
        this.recordingId = recordingId;
        this.outboundRecordingId = outboundRecordingId;
        this.outboundRecordedPosition = outboundRecordedPosition;
    }

    private byte[] symbol() {
        handler.getSessionSymbol(symbol);
        return symbol;
    }

    /**
     * The session-close hook: snapshot after the command being applied. {@code JournaledPipeline}
     * installs it as the handler's session-close hook (P15-8), so the snapshot is taken at the
     * SESSION_CLOSE's seq and holds the closed state. It is marked with the closed date (P16-7).
     * Applying thread only.
     */
    public void requestSnapshot() {
        pending = true;
        int closed = handler.getLastClosedBusinessDate();
        if (!handler.isSessionOpen() && closed > 0) {
            pendingCloseDate = closed;
        }
    }

    @Override
    public void onApplied(long seq, long position) {
        if (seq % interval == 0) {
            pending = true;
        }
        if (pending && writer.isIdle()) {
            int closeDate = pendingCloseDate;
            pending = false;
            pendingCloseDate = -1;
            try {
                int length = encode(seq, position, closeDate);
                writer.submit(length, seq, closeDate);
            } catch (Throwable t) {
                writer.reportEncodeFailure(t);
            }
        }
    }

    private int encode(long seq, long position, int closeDate) {
        long outboundPosition = handler.isReplaying() ? -1L : outboundRecordedPosition.getAsLong();
        return codec.encode(writer.buffer(), engine, seq, position, recordingId,
                handler.getLastSeq(), handler.getCommandTimestamp(), handler.isSessionOpen(),
                handler.getBusinessDate(), handler.getLastClosedBusinessDate(), symbol(),
                outboundRecordingId, outboundPosition < 0 ? -1L : outboundPosition, closeDate);
    }

    /**
     * The graceful-shutdown snapshot (SRS §3.10), written to the snapshot directory on the calling
     * thread, after the applying thread has stopped at {@code seq}. Skipped when nothing was ever
     * applied or when a snapshot of this seq already exists (a restart with no new commands). A close
     * snapshot still pending (the writer was busy) is written now, with its close date.
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
        int length = encode(seq, position, pending ? pendingCloseDate : -1);
        store.write(writer.buffer(), length, seq);
        return true;
    }
}
