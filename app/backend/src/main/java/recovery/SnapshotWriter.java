package recovery;

import org.agrona.ExpandableDirectByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.LockSupport;

/**
 * The snapshot writer thread (P15-5): takes an encoded snapshot body off the engine thread and
 * persists it through a {@link SnapshotSink}, so file I/O never stalls matching.
 *
 * Handoff, one snapshot at a time, no queue. The writer owns one buffer. While {@link #isIdle()}
 * the engine thread may encode into {@link #buffer()} and then {@link #submit}; the volatile
 * {@code busy} write publishes the bytes, and from then on only the writer touches the buffer
 * until it clears {@code busy} again. If the engine finds the writer busy it does not wait: the
 * {@link SnapshotScheduler} defers its snapshot to a later command.
 *
 * A failed write is logged at ERROR and the venue keeps trading: snapshots shorten recovery, the
 * journal is the authority. An encode failure on the engine thread is handed here to be logged,
 * because the engine thread never logs.
 */
public final class SnapshotWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotWriter.class);

    /** Initial body buffer; it grows if a book ever needs more. About 13,000 resting orders. */
    static final int INITIAL_CAPACITY = 1 << 20;

    private final SnapshotSink sink;
    private final ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(INITIAL_CAPACITY);
    private final Thread thread;

    /** True from submit until the write has finished: the writer owns the buffer. */
    private volatile boolean busy;
    private volatile boolean running = true;
    /** Set by the engine thread, logged and cleared by the writer. */
    private volatile Throwable encodeFailure;
    private volatile long writtenCount;

    private int pendingLength;
    private long pendingSeq;
    private int pendingCloseDate = -1;
    /** Told on the writer thread after a close snapshot is written (P16-7: wakes journal retention). */
    private volatile Runnable closeListener;

    public SnapshotWriter(SnapshotSink sink) {
        this.sink = sink;
        this.thread = new Thread(this::run, "snapshot-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** True when the engine thread may encode into {@link #buffer()}. */
    public boolean isIdle() {
        return !busy;
    }

    /** The body buffer. Only while {@link #isIdle()}, and only by the thread that will submit. */
    public ExpandableDirectByteBuffer buffer() {
        return buffer;
    }

    /** Hands the encoded body to the writer thread. Call only after encoding while idle. */
    public void submit(int length, long seq) {
        submit(length, seq, -1);
    }

    /**
     * As {@link #submit(int, long)}, for a snapshot a SESSION_CLOSE of {@code closeDate} requested (-1
     * for any other); after such a snapshot is written the close listener runs on this writer thread.
     */
    public void submit(int length, long seq, int closeDate) {
        pendingLength = length;
        pendingSeq = seq;
        pendingCloseDate = closeDate;
        busy = true;              // publishes the buffer and the two fields above
        LockSupport.unpark(thread);
    }

    /**
     * Runs {@code listener} on the writer thread after each close snapshot is written (P16-7). It must
     * not block: journal retention's is an unpark. Any thread; set before the venue starts.
     */
    public void onCloseSnapshot(Runnable listener) {
        this.closeListener = listener;
    }

    /** Engine thread: an encode failed; the writer thread logs it. */
    public void reportEncodeFailure(Throwable failure) {
        encodeFailure = failure;
        LockSupport.unpark(thread);
    }

    /** Snapshots written successfully by the writer thread so far. */
    public long writtenCount() {
        return writtenCount;
    }

    /**
     * Waits until no write is in flight. Shutdown calls this before encoding its own snapshot into
     * {@link #buffer()} on the calling thread.
     *
     * @return false if a write was still in flight after {@code timeoutMs}
     */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (busy) {
            if (System.currentTimeMillis() > deadline) return false;
            LockSupport.parkNanos(1_000_000L);
        }
        return true;
    }

    private void run() {
        while (running) {
            Throwable failure = encodeFailure;
            if (failure != null) {
                encodeFailure = null;
                log.error("Snapshot encode failed on the engine thread; trading continues, the journal is intact",
                        failure);
            }
            if (busy) {
                int closeDate = pendingCloseDate;
                boolean written = writeLogged(pendingLength, pendingSeq);
                if (written) writtenCount++;
                busy = false;
                Runnable listener = closeListener;
                if (written && closeDate > 0 && listener != null) {
                    try {
                        listener.run();
                    } catch (RuntimeException e) {
                        log.warn("The close-snapshot listener failed: {}", e.toString());
                    }
                }
            } else {
                LockSupport.parkNanos(this, 100_000_000L);
            }
        }
    }

    private boolean writeLogged(int length, long seq) {
        try {
            sink.write(buffer, length, seq);
            return true;
        } catch (Throwable t) {
            log.error("Snapshot at inbound seq {} was not written; trading continues, the journal is intact", seq, t);
            return false;
        }
    }

    /** Stops the thread after any write in flight. */
    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        try {
            thread.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
