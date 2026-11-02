package journal;

import codec.ExecutionEventCodec;
import com.lmax.disruptor.EventHandler;
import event.ExecutionEvent;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import org.agrona.BitUtil;
import org.agrona.BufferUtil;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * The outbound recorder (SRS §3.5, §3.9): a consumer of the outbound ring and the recorded
 * outbound stream's sole writer (SRS §5.2). Every execution event is encoded with SBE (every
 * field, D7) and offered to the outbound publication, in seq order, so the recording is the
 * venue's execution record, read by recovery (D13), RESEND (D16) and the Reporter (P15-10).
 *
 * Gap check. Each event's seq must be exactly one above the last recorded seq. Anything else is
 * thrown before the offer, so the recording never holds a gap or a repeat; it can only be a
 * handler or recovery bug.
 *
 * Offer semantics are the sequencer's: back-pressure and administrative actions are retried with
 * the same bytes, so an event is never dropped or overtaken; any other failure throws without
 * committing. The recorder is registered with a fail-stop exception policy, so the consumer stops
 * with the slot unconsumed and the venue halts rather than run on with an incomplete record.
 *
 * Zero allocation per event (SRS §5.1): a flyweight codec, one preallocated direct buffer,
 * primitive state. Exceptions are on the fatal path only. No logging. Single-threaded: only the
 * Disruptor's consumer thread calls {@link #onEvent}.
 */
public final class OutboundRecorder implements EventHandler<ExecutionEvent> {

    private final ExclusivePublication publication;
    private final IdleStrategy retryIdle;
    private final LongConsumer beforeRecord;
    private final ExecutionEventCodec codec = new ExecutionEventCodec();
    private final UnsafeBuffer buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(
            BitUtil.align(ExecutionEventCodec.ENCODED_LENGTH, BitUtil.CACHE_LINE_LENGTH), BitUtil.CACHE_LINE_LENGTH));

    private long lastSeq;
    /** {@link #lastSeq}, published with a release store for readers on other threads. */
    private final AtomicLong recordedSeq;
    /** The last event in the recording when it was opened; null if it was empty. */
    private final ExecutionEvent lastRecordedAtOpen;

    /**
     * @param publication        the recorded outbound publication (from {@link Journal#openRecordedPublication})
     * @param lastSeq            the last seq already recorded, 0 if none
     * @param lastRecordedAtOpen the event carrying {@code lastSeq}, or null if none
     * @param retryIdle          how to wait between offers while the stream is back-pressured
     * @param beforeRecord       test seam, null in production: called with each seq before it is
     *                           encoded; may block or throw (a throw takes the fatal path)
     */
    public OutboundRecorder(ExclusivePublication publication, long lastSeq, ExecutionEvent lastRecordedAtOpen,
                            IdleStrategy retryIdle, LongConsumer beforeRecord) {
        this.publication = publication;
        this.lastSeq = lastSeq;
        this.lastRecordedAtOpen = lastRecordedAtOpen;
        this.retryIdle = retryIdle;
        this.beforeRecord = beforeRecord;
        this.recordedSeq = new AtomicLong(lastSeq);
    }

    /**
     * A recorder continuing the outbound recording {@code recorded} (opened or extended by
     * {@link Journal#openRecordedPublication}, D10): the last recorded event is read back from the
     * recording's final term, as the sequencer reads the inbound tail.
     */
    public static OutboundRecorder open(Journal journal, RecordedPublication recorded, IdleStrategy retryIdle,
                                        LongConsumer beforeRecord) {
        ExecutionEvent last = readLast(journal, recorded);
        return new OutboundRecorder(recorded.publication(), last == null ? 0L : last.seq, last, retryIdle, beforeRecord);
    }

    /** The last event recorded before {@code recorded}'s join position, or null if there is none. */
    static ExecutionEvent readLast(Journal journal, RecordedPublication recorded) {
        if (recorded.joinPosition() <= recorded.startPosition()) return null;
        ExecutionEventCodec tailCodec = new ExecutionEventCodec();
        ExecutionEvent last = new ExecutionEvent();
        long from = JournalReader.tailStart(recorded.startPosition(), recorded.joinPosition(), recorded.termBufferLength());
        long read = new JournalReader(journal.archive()).replay(recorded.recordingId(), from, recorded.joinPosition(),
                (buf, offset, length, header) -> tailCodec.decode(buf, offset, last));
        return read == 0 ? null : last;
    }

    @Override
    public void onEvent(ExecutionEvent event, long sequence, boolean endOfBatch) {
        final long seq = event.seq;
        if (seq != lastSeq + 1) {
            throw new IllegalStateException("Outbound seq " + seq + " after recorded seq " + lastSeq
                    + ": the outbound recording must be gap-free");
        }
        if (beforeRecord != null) {
            beforeRecord.accept(seq);
        }
        final int length = codec.encode(event, buffer, 0);

        long result;
        while ((result = publication.offer(buffer, 0, length)) < 0) {
            if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                retryIdle.idle();
            } else {
                throw new IllegalStateException("Outbound journal offer failed: "
                        + Publication.errorString(result) + ", seq=" + seq);
            }
        }
        retryIdle.reset();

        lastSeq = seq;
        recordedSeq.lazySet(seq);
    }

    /** The last seq offered to the recording. Readable from any thread. */
    public long recordedSeq() {
        return recordedSeq.get();
    }

    /** The last seq in the recording when it was opened (the D13 publish floor). */
    public long lastSeqAtOpen() {
        return lastRecordedAtOpen == null ? 0L : lastRecordedAtOpen.seq;
    }

    /** The event carrying {@link #lastSeqAtOpen()}, or null if the recording was empty. Do not modify. */
    public ExecutionEvent lastRecordedAtOpen() {
        return lastRecordedAtOpen;
    }
}
