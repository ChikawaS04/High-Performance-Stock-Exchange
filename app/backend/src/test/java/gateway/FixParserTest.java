package gateway;

import event.OrderEvent;
import event.OrderEventType;
import model.Side;
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
}