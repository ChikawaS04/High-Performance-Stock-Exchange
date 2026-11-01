package codec;

import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import model.Side;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Round-trips the execution message through ExecutionEventCodec (pure logic, no Aeron). */
class ExecutionEventCodecTest {

    private static final long NA = -1L;

    private final ExecutionEventCodec codec = new ExecutionEventCodec();
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[512]);

    private static ExecutionEvent exec(long seq, ExecutionEventType type, long orderId, long tradeId,
                                       long price, long filled, long remaining, long aggressor, long passive,
                                       Side aggressorSide, long aggressorParticipant, long passiveParticipant,
                                       int businessDate, long timestamp) {
        ExecutionEvent e = new ExecutionEvent();
        e.seq = seq;
        e.eventType = type;
        e.orderId = orderId;
        e.tradeId = tradeId;
        e.price = price;
        e.filledQuantity = filled;
        e.remainingQuantity = remaining;
        e.aggressorOrderId = aggressor;
        e.passiveOrderId = passive;
        e.aggressorSide = aggressorSide;
        e.aggressorParticipantId = aggressorParticipant;
        e.passiveParticipantId = passiveParticipant;
        e.businessDate = businessDate;
        e.timestamp = timestamp;
        return e;
    }

    /** A non-fill, non-session event: every fill and session field at its sentinel. */
    private static ExecutionEvent report(long seq, ExecutionEventType type, long orderId, long price,
                                         long remaining) {
        return exec(seq, type, orderId, NA, price, NA, remaining, NA, NA, null, NA, NA, -1, 1_000L + seq);
    }

    private ExecutionEvent roundTrip(ExecutionEvent src) {
        codec.encode(src, buffer, 0);
        ExecutionEvent dst = new ExecutionEvent();
        EventFields.fill(dst, 9);   // dirty target: decode must overwrite every field
        codec.decode(buffer, 0, dst);
        return dst;
    }

    private static void assertSameEvent(ExecutionEvent expected, ExecutionEvent actual) {
        assertEquals(expected.seq, actual.seq);
        assertEquals(expected.eventType, actual.eventType);
        assertEquals(expected.orderId, actual.orderId);
        assertEquals(expected.tradeId, actual.tradeId);
        assertEquals(expected.price, actual.price);
        assertEquals(expected.filledQuantity, actual.filledQuantity);
        assertEquals(expected.remainingQuantity, actual.remainingQuantity);
        assertEquals(expected.aggressorOrderId, actual.aggressorOrderId);
        assertEquals(expected.passiveOrderId, actual.passiveOrderId);
        assertEquals(expected.aggressorSide, actual.aggressorSide);
        assertEquals(expected.aggressorParticipantId, actual.aggressorParticipantId);
        assertEquals(expected.passiveParticipantId, actual.passiveParticipantId);
        assertEquals(expected.businessDate, actual.businessDate);
        assertEquals(expected.timestamp, actual.timestamp);
    }

    @Test
    void fillRoundTripsSideAndBothParticipants() {
        ExecutionEvent src = exec(1L, ExecutionEventType.ORDER_FILLED, 11L, 501L, 1_000_050L, 10L, 0L,
                11L, 12L, Side.SELL, 1L, 2L, -1, 1_761_000_000_000_000_000L);
        assertSameEvent(src, roundTrip(src));
    }

    @Test
    void partialFillRoundTrips() {
        ExecutionEvent src = exec(2L, ExecutionEventType.ORDER_PARTIALLY_FILLED, 11L, 502L, 1_000_000L, 4L, 6L,
                11L, 13L, Side.BUY, 1L, 1L, -1, 2_000L);
        assertSameEvent(src, roundTrip(src));
    }

    @Test
    void acceptedRoundTripsWithSentinels() {
        ExecutionEvent dst = roundTrip(report(3L, ExecutionEventType.ORDER_ACCEPTED, 21L, 1_000_000L, 100L));
        assertSameEvent(report(3L, ExecutionEventType.ORDER_ACCEPTED, 21L, 1_000_000L, 100L), dst);
        assertNull(dst.aggressorSide);
        assertEquals(NA, dst.aggressorParticipantId);
        assertEquals(NA, dst.passiveParticipantId);
    }

    @Test
    void acceptedPeggedOrderRoundTripsWithNoPrice() {
        ExecutionEvent src = report(4L, ExecutionEventType.ORDER_ACCEPTED, 22L, NA, 50L);
        assertSameEvent(src, roundTrip(src));
    }

    @Test
    void cancelledRejectedAndExpiredRoundTrip() {
        ExecutionEvent cancelled = report(5L, ExecutionEventType.ORDER_CANCELLED, 23L, NA, NA);
        ExecutionEvent rejected = report(6L, ExecutionEventType.ORDER_REJECTED, 24L, NA, NA);
        ExecutionEvent expired = report(7L, ExecutionEventType.ORDER_EXPIRED, 25L, NA, 40L);
        assertSameEvent(cancelled, roundTrip(cancelled));
        assertSameEvent(rejected, roundTrip(rejected));
        assertSameEvent(expired, roundTrip(expired));
    }

    @Test
    void sessionEventsRoundTripTheBusinessDate() {
        ExecutionEvent opened = exec(8L, ExecutionEventType.SESSION_OPENED, NA, NA, NA, NA, NA, NA, NA,
                null, NA, NA, 20261102, 9_000L);
        ExecutionEvent closed = exec(9L, ExecutionEventType.SESSION_CLOSED, NA, NA, NA, NA, NA, NA, NA,
                null, NA, NA, 20261102, 9_500L);
        assertSameEvent(opened, roundTrip(opened));
        assertSameEvent(closed, roundTrip(closed));
    }

    @Test
    void nullEventTypeRoundTripsAsNull() {
        ExecutionEvent src = report(10L, null, 1L, NA, NA);
        assertNull(roundTrip(src).eventType);
    }

    @Test
    void extremeValuesRoundTrip() {
        long[] extremes = {Long.MAX_VALUE, Long.MIN_VALUE + 1, 0L, -1L};
        for (long v : extremes) {
            ExecutionEvent src = exec(v, ExecutionEventType.ORDER_FILLED, v, v, v, v, v, v, v,
                    Side.BUY, v, v, (int) v, v);
            assertSameEvent(src, roundTrip(src));
        }
        ExecutionEvent maxDate = report(1L, ExecutionEventType.SESSION_OPENED, NA, NA, NA);
        maxDate.businessDate = Integer.MAX_VALUE;
        assertSameEvent(maxDate, roundTrip(maxDate));
    }

    @Test
    void encodedLengthIsFixed() {
        assertEquals(MessageHeaderEncoder.ENCODED_LENGTH + ExecutionEventMessageEncoder.BLOCK_LENGTH,
                ExecutionEventCodec.ENCODED_LENGTH);
        assertEquals(ExecutionEventCodec.ENCODED_LENGTH,
                codec.encode(report(1L, ExecutionEventType.ORDER_ACCEPTED, 1L, 100L, 1L), buffer, 0));
    }

    @Test
    void encodesAndDecodesAtANonZeroOffset() {
        ExecutionEvent src = exec(11L, ExecutionEventType.ORDER_FILLED, 1L, 2L, 3L, 4L, 5L, 6L, 7L,
                Side.SELL, 8L, 9L, -1, 10L);
        codec.encode(src, buffer, 37);
        ExecutionEvent dst = new ExecutionEvent();
        codec.decode(buffer, 37, dst);
        assertSameEvent(src, dst);
    }

    @Test
    void foreignSchemaIsRejected() {
        codec.encode(report(1L, ExecutionEventType.ORDER_ACCEPTED, 1L, 100L, 1L), buffer, 0);
        new MessageHeaderEncoder().wrap(buffer, 0).schemaId(99);
        assertThrows(IllegalStateException.class, () -> codec.decode(buffer, 0, new ExecutionEvent()));
    }

    @Test
    void aCommandIsNotDecodedAsAnExecution() {
        OrderEvent cancel = new OrderEvent();
        cancel.eventType = OrderEventType.CANCEL_ORDER;
        new OrderEventCodec().encode(cancel, 1L, buffer, 0);
        assertThrows(IllegalStateException.class, () -> codec.decode(buffer, 0, new ExecutionEvent()));
    }

    @Test
    void executionTypeMappingIsExhaustive() {
        assertEquals(ExecutionEventType.values().length, ExecTypeCode.values().length - 1);   // minus NULL_VAL
        for (ExecutionEventType t : ExecutionEventType.values()) {
            assertEquals(t.name(), ExecutionEventCodec.toCode(t).name());
            assertEquals(t, ExecutionEventCodec.toType(ExecutionEventCodec.toCode(t)));
        }
        assertNull(ExecutionEventCodec.toType(ExecTypeCode.NULL_VAL));
    }
}
