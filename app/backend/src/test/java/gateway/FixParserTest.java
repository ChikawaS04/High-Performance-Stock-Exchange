package gateway;

import event.OrderEvent;
import event.OrderEventType;
import model.OrdType;
import model.Prices;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FixParserTest {

    private static final byte SOH = 0x01;

    private final FixParser parser = new FixParser();
    private final OrderEvent event = new OrderEvent();

    // --- Message builder -----------------------------------------------------
    // Assembles a complete FIX message from the ordered body fields (everything
    // from 35= onward, as "tag=value"), prepending a header and appending a
    // correctly computed BodyLength (tag 9) and CheckSum (tag 10). Correct
    // checksum is the point: it lets every reject test fail on field logic,
    // not on a bad trailer.
    private static byte[] msg(String... bodyFields) {
        StringBuilder bodySb = new StringBuilder();
        for (String f : bodyFields) {
            bodySb.append(f).append((char) SOH);
        }
        byte[] body = bodySb.toString().getBytes(StandardCharsets.US_ASCII);

        String header = "8=FIX.4.2" + (char) SOH + "9=" + body.length + (char) SOH;
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);

        int sum = 0;
        for (byte b : headerBytes) sum += (b & 0xFF);
        for (byte b : body)        sum += (b & 0xFF);
        sum &= 0xFF;

        byte[] trailer = ("10=" + String.format("%03d", sum) + (char) SOH)
                .getBytes(StandardCharsets.US_ASCII);

        byte[] out = new byte[headerBytes.length + body.length + trailer.length];
        System.arraycopy(headerBytes, 0, out, 0, headerBytes.length);
        System.arraycopy(body, 0, out, headerBytes.length, body.length);
        System.arraycopy(trailer, 0, out, headerBytes.length + body.length, trailer.length);
        return out;
    }

    // --- Accept paths --------------------------------------------------------

    @Test
    @DisplayName("valid 35=D (buy) populates every field")
    void validNewOrderBuy() {
        byte[] m = msg("35=D", "11=123", "54=1", "44=150.25", "38=100", "55=ASML");

        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(OrderEventType.NEW_ORDER, event.eventType);
        assertEquals(123L, event.orderId);
        assertEquals(Side.BUY, event.side);
        assertEquals(1_502_500L, event.price);
        assertEquals(100L, event.quantity);
        assertEquals(-1L, event.originalOrderId); // unused by D, cleared
    }

    @Test
    @DisplayName("valid 35=D (sell) maps 54=2 to SELL")
    void validNewOrderSell() {
        byte[] m = msg("35=D", "11=1", "54=2", "44=99.90", "38=5", "55=ASML");

        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(Side.SELL, event.side);
        assertEquals(999_000L, event.price);
    }

    @Test
    @DisplayName("valid 35=F populates ids and clears order-only fields")
    void validCancel() {
        byte[] m = msg("35=F", "11=456", "41=123");

        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(OrderEventType.CANCEL_ORDER, event.eventType);
        assertEquals(456L, event.orderId);
        assertEquals(123L, event.originalOrderId);
        assertNull(event.side);          // unused by F, cleared
        assertEquals(-1L, event.price);
        assertEquals(-1L, event.quantity);
    }

    // --- Reject paths --------------------------------------------------------

    @Test
    @DisplayName("35=D missing price (44) is rejected")
    void newOrderMissingPrice() {
        byte[] m = msg("35=D", "11=123", "54=1", "38=100", "55=ASML");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("invalid side code 54=3 is rejected")
    void invalidSide() {
        byte[] m = msg("35=D", "11=123", "54=3", "44=150.25", "38=100", "55=ASML");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("symbol other than the configured instrument is rejected")
    void wrongSymbol() {
        byte[] m = msg("35=D", "11=123", "54=1", "44=150.25", "38=100", "55=AAPL");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("zero quantity 38=0 is rejected")
    void zeroQuantity() {
        byte[] m = msg("35=D", "11=123", "54=1", "44=150.25", "38=0", "55=ASML");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("non-numeric ClOrdID is rejected")
    void nonNumericClOrdId() {
        byte[] m = msg("35=D", "11=ABC", "54=1", "44=150.25", "38=100", "55=ASML");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("unknown message type 35=X is rejected")
    void unknownMessageType() {
        byte[] m = msg("35=X", "11=123", "54=1", "44=150.25", "38=100", "55=ASML");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    // --- Time in force (tag 59), Phase 14 -----------------------------------

    @Test
    @DisplayName("tag 59=1 parses to GTC")
    void tifGtc() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML", "59=1");
        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(TimeInForce.GTC, event.tif);
    }

    @Test
    @DisplayName("tag 59=3 parses to IOC")
    void tifIoc() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML", "59=3");
        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(TimeInForce.IOC, event.tif);
    }

    @Test
    @DisplayName("tag 59=4 parses to FOK")
    void tifFok() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML", "59=4");
        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(TimeInForce.FOK, event.tif);
    }

    @Test
    @DisplayName("a missing tag 59 defaults to GTC (documented Day deviation)")
    void tifAbsentDefaultsGtc() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML");
        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(TimeInForce.GTC, event.tif);
    }

    @Test
    @DisplayName("tag 59=0 (Day) is rejected (decision C)")
    void tifDayRejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML", "59=0");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("tag 59=6 (GTD) is rejected (decision C)")
    void tifGtdRejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML", "59=6");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("non-numeric tag 59 is rejected")
    void tifNonNumericRejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML", "59=X");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("a cancel after an IOC new order leaves tif cleared on the reused slot")
    void tifClearedOnReusedSlotByCancel() {
        byte[] neu = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML", "59=3");
        assertTrue(parser.parse(neu, 0, neu.length, event));
        assertEquals(TimeInForce.IOC, event.tif);

        byte[] can = msg("35=F", "11=2", "41=1");
        assertTrue(parser.parse(can, 0, can.length, event));
        assertNull(event.tif, "cancel must clear the stale IOC from the reused slot");
    }

    // --- Max floor (tag 111), Phase 14-7 ------------------------------------

    @Test
    @DisplayName("tag 111 present parses as the iceberg display size")
    void maxFloorPresent() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=1000", "55=ASML", "111=100");
        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(100L, event.maxFloor);
    }

    @Test
    @DisplayName("a missing tag 111 defaults to 0 (not an iceberg)")
    void maxFloorAbsentDefaultsZero() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=1000", "55=ASML");
        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(0L, event.maxFloor);
    }

    @Test
    @DisplayName("tag 111=0 is rejected")
    void maxFloorZeroRejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=1000", "55=ASML", "111=0");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("non-numeric tag 111 is rejected")
    void maxFloorNonNumericRejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=1000", "55=ASML", "111=X");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("tag 111 greater than the order quantity is rejected")
    void maxFloorGreaterThanQtyRejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML", "111=150");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("a cancel after an iceberg new order clears maxFloor on the reused slot")
    void maxFloorClearedOnReusedSlotByCancel() {
        byte[] neu = msg("35=D", "11=1", "54=1", "44=150.25", "38=1000", "55=ASML", "111=100");
        assertTrue(parser.parse(neu, 0, neu.length, event));
        assertEquals(100L, event.maxFloor);

        byte[] can = msg("35=F", "11=2", "41=1");
        assertTrue(parser.parse(can, 0, can.length, event));
        assertEquals(0L, event.maxFloor, "cancel must clear the stale maxFloor from the reused slot");
    }

    // --- Order type / midpoint peg (tags 40, 18), Phase 14-9 ----------------

    @Test
    @DisplayName("missing tag 40 defaults to a LIMIT order")
    void ordTypeDefaultsToLimit() {
        byte[] m = msg("35=D", "11=1", "54=1", "44=150.25", "38=100", "55=ASML");
        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(OrdType.LIMIT, event.ordType);
    }

    @Test
    @DisplayName("40=2 is an explicit LIMIT order")
    void ordType40Of2IsLimit() {
        byte[] m = msg("35=D", "11=1", "54=1", "40=2", "44=150.25", "38=100", "55=ASML");
        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(OrdType.LIMIT, event.ordType);
    }

    @Test
    @DisplayName("40=P with 18=M and no 44 is accepted as a midpoint peg at price NA")
    void pegWith40PAnd18M_accepted() {
        byte[] m = msg("35=D", "11=1", "54=1", "40=P", "18=M", "38=100", "55=ASML");
        assertTrue(parser.parse(m, 0, m.length, event));
        assertEquals(OrdType.PEG_MID, event.ordType);
        assertEquals(Prices.NA, event.price);
        assertEquals(TimeInForce.GTC, event.tif);   // a missing 59 still defaults to GTC
    }

    @Test
    @DisplayName("a pegged order carrying tag 44 is rejected")
    void pegWithPriceRejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "40=P", "18=M", "44=150.25", "38=100", "55=ASML");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("a pegged order without ExecInst 18 is rejected")
    void pegWithout18Rejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "40=P", "38=100", "55=ASML");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("a pegged order with 18 other than M is rejected")
    void pegWith18OtherThanMRejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "40=P", "18=1", "38=100", "55=ASML");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("an unknown tag 40 value is rejected")
    void ordType40UnknownRejected() {
        byte[] m = msg("35=D", "11=1", "54=1", "40=1", "44=150.25", "38=100", "55=ASML");
        assertFalse(parser.parse(m, 0, m.length, event));
    }

    @Test
    @DisplayName("a cancel after a peg new order clears ordType on the reused slot")
    void ordTypeClearedOnReusedSlotByCancel() {
        byte[] neu = msg("35=D", "11=1", "54=1", "40=P", "18=M", "38=100", "55=ASML");
        assertTrue(parser.parse(neu, 0, neu.length, event));
        assertEquals(OrdType.PEG_MID, event.ordType);

        byte[] can = msg("35=F", "11=2", "41=1");
        assertTrue(parser.parse(can, 0, can.length, event));
        assertNull(event.ordType, "cancel must clear the stale ordType from the reused slot");
    }
}
