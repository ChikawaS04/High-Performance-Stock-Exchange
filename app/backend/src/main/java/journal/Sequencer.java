package journal;

import codec.OrderEventCodec;
import com.lmax.disruptor.EventHandler;
import event.OrderEvent;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import org.agrona.BitUtil;
import org.agrona.BufferUtil;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The sequencer (SRS §3.8): the inbound ring's sole consumer and the recorded inbound
 * stream's sole writer (SRS §5.2). For every command, in ring order, it assigns the next
 * inbound seq (from 1, no gaps), journals the gateway's receipt stamp clamped so journal
 * timestamps never decrease (D1), encodes every field with SBE (D7) and offers the bytes to
 * the inbound publication.
 *
 * Back-pressure and administrative actions (term rotation) are retried with the same bytes
 * and the idle strategy, so a command is never dropped and never overtaken. Any other offer
 * failure means the stream can no longer be recorded (no subscriber, publication closed,
 * maximum position reached): the sequencer throws without committing the seq, the Disruptor
 * stops its consumer thread with the command still in the ring, and the ring then holds the
 * gateway. The venue halts rather than publish unrecorded or skip a command.
 *
 * Zero allocation per command (SRS §5.1): a flyweight codec, one preallocated direct buffer,
 * primitive state and an allocation-free idle strategy. Exceptions are on the fatal path only.
 * No logging. Single-threaded: only the Disruptor's consumer thread calls {@link #onEvent}.
 */
public final class Sequencer implements EventHandler<OrderEvent> {

    /** No timestamp journaled yet: nothing to clamp against. */
    public static final long NO_TIMESTAMP = Long.MIN_VALUE;

    private final ExclusivePublication publication;
    private final IdleStrategy retryIdle;
    private final OrderEventCodec codec = new OrderEventCodec();
    private final UnsafeBuffer buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(
            BitUtil.align(OrderEventCodec.MAX_ENCODED_LENGTH, BitUtil.CACHE_LINE_LENGTH), BitUtil.CACHE_LINE_LENGTH));

    private long lastSeq;
    private long lastTimestamp;
    /** {@link #lastSeq}, published with a release store for readers on other threads. */
    private final AtomicLong journaledSeq;

    /**
     * @param publication   the recorded inbound publication (from {@link Journal#openRecordedPublication})
     * @param lastSeq       the last seq already journaled, 0 if none
     * @param lastTimestamp the last timestamp already journaled, {@link #NO_TIMESTAMP} if none
     * @param retryIdle     how to wait between offers while the stream is back-pressured
     */
    public Sequencer(ExclusivePublication publication, long lastSeq, long lastTimestamp, IdleStrategy retryIdle) {
        this.publication = publication;
        this.lastSeq = lastSeq;
        this.lastTimestamp = lastTimestamp;
        this.retryIdle = retryIdle;
        this.journaledSeq = new AtomicLong(lastSeq);
    }

    /** {@link #open(Journal, IdleStrategy)} with a {@link BackoffIdleStrategy}. */
    public static Sequencer open(Journal journal) {
        return open(journal, new BackoffIdleStrategy());
    }

    /**
     * Opens the recorded inbound stream (starting or extending its recording, D10) and
     * continues from its end: the last journaled seq and timestamp are read back from the
     * recording's final term. An empty or newly started recording starts at seq 1 with no
     * timestamp floor.
     */
    public static Sequencer open(Journal journal, IdleStrategy retryIdle) {
        return open(journal, journal.openRecordedPublication(Journal.INBOUND_STREAM_ID), retryIdle);
    }

    /**
     * As {@link #open(Journal, IdleStrategy)} over an inbound publication the caller has already
     * opened, so it can also subscribe the engine to it and gate on its recording before the
     * sequencer offers anything.
     */
    public static Sequencer open(Journal journal, RecordedPublication recorded, IdleStrategy retryIdle) {
        long[] last = {0L, NO_TIMESTAMP};
        if (recorded.joinPosition() > recorded.startPosition()) {
            OrderEventCodec tailCodec = new OrderEventCodec();
            OrderEvent scratch = new OrderEvent();
            long from = JournalReader.tailStart(
                    recorded.startPosition(), recorded.joinPosition(), recorded.termBufferLength());
            new JournalReader(journal.archive()).replay(recorded.recordingId(), from, recorded.joinPosition(),
                    (buf, offset, length, header) -> {
                        last[0] = tailCodec.decode(buf, offset, scratch);
                        last[1] = scratch.timestamp;
                    });
        }
        return new Sequencer(recorded.publication(), last[0], last[1], retryIdle);
    }

    @Override
    public void onEvent(OrderEvent slot, long sequence, boolean endOfBatch) {
        final long seq = lastSeq + 1;
        if (slot.timestamp < lastTimestamp) {
            slot.timestamp = lastTimestamp;   // D1: journal timestamps never decrease
        }
        final int length = codec.encode(slot, seq, buffer, 0);

        long result;
        while ((result = publication.offer(buffer, 0, length)) < 0) {
            if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                retryIdle.idle();
            } else {
                throw new IllegalStateException("Inbound journal offer failed: "
                        + Publication.errorString(result) + ", seq=" + seq);
            }
        }
        retryIdle.reset();

        lastSeq = seq;
        lastTimestamp = slot.timestamp;
        journaledSeq.lazySet(seq);
    }

    /** The last seq journaled. Read only from the consumer thread, or after it has stopped. */
    public long lastSeq() {
        return lastSeq;
    }

    /**
     * The last seq journaled, readable from any thread: graceful shutdown waits for the engine
     * to apply up to it.
     */
    public long journaledSeq() {
        return journaledSeq.get();
    }

    /** The last timestamp journaled. Same threading rule as {@link #lastSeq()}. */
    public long lastTimestamp() {
        return lastTimestamp;
    }
}
