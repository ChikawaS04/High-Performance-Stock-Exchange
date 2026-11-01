package codec;

import event.OrderEvent;
import event.OrderEventType;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Round-trips every command message through OrderEventCodec (pure logic, no Aeron). */
class OrderEventCodecTest {

    private final OrderEventCodec codec = new OrderEventCodec();
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[512]);

    // --- builders, written the way the FIX parser and gateway write a carrier ---

    private static OrderEvent newOrder(long orderId, Side side, OrdType ordType, TimeInForce tif,
                                       long price, long qty, long maxFloor, long timestamp) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.NEW_ORDER;
        e.orderId = orderId;
        e.side = side;
        e.ordType = ordType;
        e.tif = tif;
        e.price = price;
        e.quantity = qty;
        e.maxFloor = maxFloor;
        e.timestamp = timestamp;
        e.originalOrderId = -1L;
        e.clearSessionFields();
        return e;
    }

    private static OrderEvent cancel(long orderId, long origId, long timestamp) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.CANCEL_ORDER;
        e.orderId = orderId;
        e.originalOrderId = origId;
        e.price = -1L;
        e.quantity = -1L;
        e.timestamp = timestamp;
        e.clearSessionFields();
        return e;
    }

    private static OrderEvent sessionOpen(int businessDate, String symbol, long tick, long lot, long scale,
                                          long timestamp) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.SESSION_OPEN;
        e.orderId = -1L;
        e.originalOrderId = -1L;
        e.price = -1L;
        e.quantity = -1L;
        e.timestamp = timestamp;
        e.businessDate = businessDate;
        byte[] ascii = symbol.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, e.symbol, 0, ascii.length);
        e.tickSize = tick;
        e.lotSize = lot;
        e.priceScale = scale;
        return e;
    }

    private OrderEvent roundTrip(OrderEvent src, long seq) {
        codec.encode(src, seq, buffer, 0);
        OrderEvent dst = new OrderEvent();
        EventFields.fill(dst, 9);   // dirty target: decode must overwrite every field
        assertEquals(seq, codec.decode(buffer, 0, dst));
        return dst;
    }

    private static void assertSameEvent(OrderEvent expected, OrderEvent actual) {
        assertEquals(expected.eventType, actual.eventType);
        assertEquals(expected.orderId, actual.orderId);
        assertEquals(expected.side, actual.side);
        assertEquals(expected.ordType, actual.ordType);
        assertEquals(expected.tif, actual.tif);
        assertEquals(expected.price, actual.price);
        assertEquals(expected.quantity, actual.quantity);
        assertEquals(expected.maxFloor, actual.maxFloor);
        assertEquals(expected.timestamp, actual.timestamp);
        assertEquals(expected.originalOrderId, actual.originalOrderId);
        assertEquals(expected.businessDate, actual.businessDate);
        assertArrayEquals(expected.symbol, actual.symbol);
        assertEquals(expected.tickSize, actual.tickSize);
        assertEquals(expected.lotSize, actual.lotSize);
        assertEquals(expected.priceScale, actual.priceScale);
    }

    // --- NEW ---

    @Test
    void newLimitOrderRoundTrips() {
        OrderEvent src = newOrder(1001L, Side.BUY, OrdType.LIMIT, TimeInForce.GTC,
                1_502_500L, 300L, 0L, 1_761_000_000_000_000_000L);
        assertSameEvent(src, roundTrip(src, 1L));
    }

    @Test
    void newPeggedOrderRoundTripsWithNoPrice() {
        OrderEvent src = newOrder(1002L, Side.SELL, OrdType.PEG_MID, TimeInForce.GTC,
                -1L, 50L, 0L, 123L);
        OrderEvent dst = roundTrip(src, 2L);
        assertSameEvent(src, dst);
        assertEquals(-1L, dst.price);
    }

    @Test
    void newIcebergOrderRoundTripsItsDisplaySize() {
        OrderEvent src = newOrder(1003L, Side.BUY, OrdType.LIMIT, TimeInForce.GTC,
                1_000_000L, 1_000L, 100L, 456L);
        assertEquals(100L, roundTrip(src, 3L).maxFloor);
    }

    @Test
    void everyTimeInForceRoundTrips() {
        for (TimeInForce tif : TimeInForce.values()) {
            OrderEvent src = newOrder(7L, Side.SELL, OrdType.LIMIT, tif, 100L, 1L, 0L, 1L);
            assertEquals(tif, roundTrip(src, 4L).tif);
        }
    }

    @Test
    void nullEnumsRoundTripAsNull() {
        OrderEvent src = newOrder(8L, null, null, null, 100L, 1L, 0L, 1L);
        OrderEvent dst = roundTrip(src, 5L);
        assertNull(dst.side);
        assertNull(dst.ordType);
        assertNull(dst.tif);
    }

    @Test
    void extremeValuesRoundTrip() {
        long[] extremes = {Long.MAX_VALUE, Long.MIN_VALUE + 1, 0L, -1L};
        for (long v : extremes) {
            OrderEvent src = newOrder(v, Side.BUY, OrdType.LIMIT, TimeInForce.IOC, v, v, v, v);
            assertSameEvent(src, roundTrip(src, v));
        }
    }

    // --- CANCEL ---

    @Test
    void cancelRoundTripsAndResetsTheOrderFields() {
        OrderEvent src = cancel(2001L, 1001L, 789L);
        OrderEvent dst = roundTrip(src, 6L);
        assertSameEvent(src, dst);
        assertNull(dst.side);
        assertNull(dst.ordType);
        assertNull(dst.tif);
        assertEquals(0L, dst.maxFloor);
    }

    // --- SESSION_OPEN / SESSION_CLOSE ---

    @Test
    void sessionOpenRoundTripsWithAFullWidthSymbol() {
        OrderEvent src = sessionOpen(20261102, "ABCDEFGH", 100L, 1L, 10_000L, 999L);
        OrderEvent dst = roundTrip(src, 7L);
        assertSameEvent(src, dst);
        assertEquals("ABCDEFGH", new String(dst.symbol, StandardCharsets.US_ASCII));
    }

    @Test
    void sessionOpenRoundTripsAShortSymbolNulPadded() {
        OrderEvent src = sessionOpen(20261102, "AAPL", 100L, 1L, 10_000L, 999L);
        OrderEvent dst = roundTrip(src, 8L);
        assertSameEvent(src, dst);
        byte[] expected = Arrays.copyOf("AAPL".getBytes(StandardCharsets.US_ASCII), OrderEvent.SYMBOL_LENGTH);
        assertArrayEquals(expected, dst.symbol);
    }

    @Test
    void sessionCloseRoundTripsTheBusinessDateOnly() {
        OrderEvent src = new OrderEvent();
        src.eventType = OrderEventType.SESSION_CLOSE;
        src.timestamp = 555L;
        src.orderId = -1L;
        src.originalOrderId = -1L;
        src.price = -1L;
        src.quantity = -1L;
        src.clearSessionFields();
        src.businessDate = Integer.MAX_VALUE;
        assertSameEvent(src, roundTrip(src, 9L));
    }

    // --- slot reuse: decoding into a dirty carrier leaves nothing stale ---

    @Test
    void newDecodedOverASessionOpenLeavesNoSessionValue() {
        OrderEvent carrier = new OrderEvent();
        codec.encode(sessionOpen(20261102, "MSFT", 100L, 1L, 10_000L, 1L), 1L, buffer, 0);
        codec.decode(buffer, 0, carrier);

        OrderEvent order = newOrder(5L, Side.BUY, OrdType.LIMIT, TimeInForce.GTC, 100L, 1L, 0L, 2L);
        codec.encode(order, 2L, buffer, 0);
        codec.decode(buffer, 0, carrier);

        assertSameEvent(order, carrier);
    }

    @Test
    void sessionOpenDecodedOverANewLeavesNoOrderValue() {
        OrderEvent carrier = new OrderEvent();
        codec.encode(newOrder(5L, Side.BUY, OrdType.PEG_MID, TimeInForce.FOK, -1L, 9L, 0L, 2L), 1L, buffer, 0);
        codec.decode(buffer, 0, carrier);

        OrderEvent open = sessionOpen(20261102, "MSFT", 100L, 1L, 10_000L, 3L);
        codec.encode(open, 2L, buffer, 0);
        codec.decode(buffer, 0, carrier);

        assertSameEvent(open, carrier);
    }

    // --- framing ---

    @Test
    void encodedLengthIsHeaderPlusBlockAndFitsTheMaximum() {
        int header = MessageHeaderEncoder.ENCODED_LENGTH;
        assertEquals(header + NewOrderCommandEncoder.BLOCK_LENGTH,
                codec.encode(newOrder(1L, Side.BUY, OrdType.LIMIT, TimeInForce.GTC, 100L, 1L, 0L, 1L), 1L, buffer, 0));
        assertEquals(header + CancelOrderCommandEncoder.BLOCK_LENGTH,
                codec.encode(cancel(1L, 2L, 1L), 1L, buffer, 0));
        assertEquals(header + SessionOpenCommandEncoder.BLOCK_LENGTH,
                codec.encode(sessionOpen(20261102, "X", 1L, 1L, 1L, 1L), 1L, buffer, 0));
        assertEquals(header + Math.max(Math.max(NewOrderCommandEncoder.BLOCK_LENGTH, CancelOrderCommandEncoder.BLOCK_LENGTH),
                        Math.max(SessionOpenCommandEncoder.BLOCK_LENGTH, SessionCloseCommandEncoder.BLOCK_LENGTH)),
                OrderEventCodec.MAX_ENCODED_LENGTH);
    }

    @Test
    void encodesAndDecodesAtANonZeroOffset() {
        OrderEvent src = newOrder(77L, Side.SELL, OrdType.LIMIT, TimeInForce.GTC, 200L, 3L, 0L, 4L);
        int offset = 123;
        codec.encode(src, 10L, buffer, offset);
        OrderEvent dst = new OrderEvent();
        assertEquals(10L, codec.decode(buffer, offset, dst));
        assertSameEvent(src, dst);
    }

    @Test
    void encodingIsLittleEndian() {
        long seq = 0x0102030405060708L;
        codec.encode(newOrder(1L, Side.BUY, OrdType.LIMIT, TimeInForce.GTC, 100L, 1L, 0L, 1L), seq, buffer, 0);
        // header: templateId is the second uint16; seq is the first field of the body.
        assertEquals(NewOrderCommandEncoder.TEMPLATE_ID, buffer.getByte(2));
        assertEquals(0, buffer.getByte(3));
        int body = MessageHeaderEncoder.ENCODED_LENGTH;
        for (int i = 0; i < 8; i++) {
            assertEquals(8 - i, buffer.getByte(body + i), "byte " + i + " of seq");
        }
    }

    @Test
    void foreignSchemaIsRejected() {
        codec.encode(cancel(1L, 2L, 3L), 1L, buffer, 0);
        new MessageHeaderEncoder().wrap(buffer, 0).schemaId(99);
        assertThrows(IllegalStateException.class, () -> codec.decode(buffer, 0, new OrderEvent()));
    }

    @Test
    void unknownTemplateIsRejected() {
        codec.encode(cancel(1L, 2L, 3L), 1L, buffer, 0);
        new MessageHeaderEncoder().wrap(buffer, 0).templateId(77);
        assertThrows(IllegalStateException.class, () -> codec.decode(buffer, 0, new OrderEvent()));
    }

    // --- enum mapping is 1:1 with the Java enums ---

    @Test
    void sideMappingIsExhaustive() {
        assertEquals(Side.values().length, SideCode.values().length - 1);   // minus NULL_VAL
        for (Side s : Side.values()) {
            assertEquals(s.name(), OrderEventCodec.toCode(s).name());
            assertEquals(s, OrderEventCodec.toSide(OrderEventCodec.toCode(s)));
        }
        assertNull(OrderEventCodec.toSide(SideCode.NULL_VAL));
    }

    @Test
    void ordTypeMappingIsExhaustive() {
        assertEquals(OrdType.values().length, OrdTypeCode.values().length - 1);
        for (OrdType t : OrdType.values()) {
            assertEquals(t.name(), OrderEventCodec.toCode(t).name());
            assertEquals(t, OrderEventCodec.toOrdType(OrderEventCodec.toCode(t)));
        }
        assertNull(OrderEventCodec.toOrdType(OrdTypeCode.NULL_VAL));
    }

    @Test
    void timeInForceMappingIsExhaustive() {
        assertEquals(TimeInForce.values().length, TimeInForceCode.values().length - 1);
        for (TimeInForce t : TimeInForce.values()) {
            assertEquals(t.name(), OrderEventCodec.toCode(t).name());
            assertEquals(t, OrderEventCodec.toTif(OrderEventCodec.toCode(t)));
        }
        assertNull(OrderEventCodec.toTif(TimeInForceCode.NULL_VAL));
    }
}
