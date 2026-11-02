package journal;

import codec.ExecutionEventCodec;
import event.CapturingExecutionHandler.Observed;
import event.ExecutionEvent;
import event.ExecutionEventType;
import model.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test helpers for the outbound recording (P15-6): read it back as {@link Observed} records (the
 * same shape {@code CapturingExecutionHandler} captures from the ring, so the two compare with
 * {@code equals}), and build execution events.
 */
public final class OutboundRecordings {

    private static final long TIMEOUT_MS = 10_000;

    private OutboundRecordings() { }

    /** Every event in the outbound recording, in recording order; empty if there is none. */
    public static List<Observed> readAll(Journal journal) {
        RecordingDescriptor recording = journal.findRecording(Journal.OUTBOUND_STREAM_ID);
        List<Observed> out = new ArrayList<>();
        if (recording == null) return out;
        ExecutionEventCodec codec = new ExecutionEventCodec();
        ExecutionEvent e = new ExecutionEvent();
        new JournalReader(journal.archive()).replayAll(recording.recordingId(), (buffer, offset, length, header) -> {
            codec.decode(buffer, offset, e);
            out.add(observe(e));
        });
        return out;
    }

    /** Reads until the recording holds {@code expected} events (the Archive may lag the offer). */
    public static List<Observed> readAll(Journal journal, int expected) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        List<Observed> all;
        while ((all = readAll(journal)).size() < expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("outbound recording holds " + all.size() + " events, expected " + expected);
            }
            LockSupport.parkNanos(1_000_000L);
        }
        assertEquals(expected, all.size(), "outbound recording holds more events than were recorded");
        return all;
    }

    public static Observed observe(ExecutionEvent e) {
        return new Observed(e.seq, e.eventType, e.orderId, e.tradeId, e.price, e.filledQuantity,
                e.remainingQuantity, e.aggressorOrderId, e.passiveOrderId, e.aggressorSide,
                e.aggressorParticipantId, e.passiveParticipantId, e.businessDate, e.timestamp);
    }

    /** Asserts {@code events} carry seqs 1..n exactly, in order. */
    public static void assertGapFreeFromOne(List<Observed> events) {
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1, events.get(i).seq(), "outbound seq at index " + i);
        }
    }

    /** A fill with every field distinct, derived from {@code seq}. */
    public static ExecutionEvent fill(long seq) {
        ExecutionEvent e = new ExecutionEvent();
        e.seq = seq;
        e.eventType = ExecutionEventType.ORDER_PARTIALLY_FILLED;
        e.orderId = 1_000 + seq;
        e.tradeId = 2_000 + seq;
        e.price = 1_500_000 + seq * 100;
        e.filledQuantity = 3 + seq;
        e.remainingQuantity = 4 + seq;
        e.aggressorOrderId = 1_000 + seq;
        e.passiveOrderId = 5_000 + seq;
        e.aggressorSide = (seq & 1) == 0 ? Side.BUY : Side.SELL;
        e.aggressorParticipantId = 7;
        e.passiveParticipantId = 8;
        e.businessDate = -1;
        e.timestamp = 1_700_000_000_000_000_000L + seq;
        return e;
    }

    /** A non-fill event of {@code type}: sentinels in every fill field. */
    public static ExecutionEvent report(long seq, ExecutionEventType type, long orderId) {
        ExecutionEvent e = new ExecutionEvent();
        e.seq = seq;
        e.eventType = type;
        e.orderId = orderId;
        e.tradeId = -1;
        e.price = type == ExecutionEventType.ORDER_ACCEPTED ? 1_500_000 : -1;
        e.filledQuantity = -1;
        e.remainingQuantity = type == ExecutionEventType.ORDER_ACCEPTED || type == ExecutionEventType.ORDER_EXPIRED ? 10 : -1;
        e.aggressorOrderId = -1;
        e.passiveOrderId = -1;
        e.aggressorSide = null;
        e.aggressorParticipantId = -1;
        e.passiveParticipantId = -1;
        e.businessDate = type == ExecutionEventType.SESSION_OPENED || type == ExecutionEventType.SESSION_CLOSED
                ? 20261102 : -1;
        e.timestamp = 1_700_000_000_000_000_000L + seq;
        return e;
    }
}
