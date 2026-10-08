package gateway;

import com.lmax.disruptor.RingBuffer;
import event.CapturingOrderHandler;
import event.CapturingOrderHandler.Observed;
import event.InboundPipeline;
import event.OrderEvent;
import event.OrderEventType;
import model.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 7: OrderGateway drives FixParser -> inbound RingBuffer.
 *
 * Full inbound slice: gateway (producer) -> real InboundPipeline ring ->
 * CapturingOrderHandler (consumer). A fixed clock lets us assert the stamped
 * receipt timestamp deterministically.
 */
class OrderGatewayTest {

    private static final byte SOH   = 0x01;
    private static final char SOH_C = (char) SOH;
    private static final long FIXED_TS = 123_456_789L;

    private InboundPipeline pipeline;
    private CapturingOrderHandler handler;
    private OrderGateway gateway;

    @BeforeEach
    void setUp() {
        handler  = new CapturingOrderHandler();
        pipeline = new InboundPipeline(handler);
        pipeline.start();
        RingBuffer<OrderEvent> ring = pipeline.getRingBuffer();
        gateway = new OrderGateway(ring, () -> FIXED_TS); // package-private clock ctor
    }

    @AfterEach
    void tearDown() {
        pipeline.shutdown();
    }

    // --- Message builder -----------------------------------------------------
    // Assembles a full FIX message from body fields (everything from 35= onward,
    // no SOH — added here), prepending 8=FIX.4.2 with a correct BodyLength (tag 9)
    // and appending a correct 3-digit CheckSum (tag 10). Same builder used in the
    // Step 6 decoder tests — a wrong checksum here would make the valid-path
    // assertions meaningless.
    private static byte[] msg(String... bodyFields) {
        StringBuilder b = new StringBuilder();
        for (String f : bodyFields) b.append(f).append(SOH_C);
        byte[] body = b.toString().getBytes(StandardCharsets.US_ASCII);

        byte[] header = ("8=FIX.4.2" + SOH_C + "9=" + body.length + SOH_C)
                .getBytes(StandardCharsets.US_ASCII);

        int sum = 0;
        for (byte x : header) sum += (x & 0xFF);
        for (byte x : body)   sum += (x & 0xFF);
        byte[] trailer = ("10=" + String.format("%03d", sum & 0xFF) + SOH_C)
                .getBytes(StandardCharsets.US_ASCII);

        byte[] out = new byte[header.length + body.length + trailer.length];
        System.arraycopy(header,  0, out, 0,                          header.length);
        System.arraycopy(body,    0, out, header.length,             body.length);
        System.arraycopy(trailer, 0, out, header.length + body.length, trailer.length);
        return out;
    }

    // --- Tests ---------------------------------------------------------------

    @Test
    void validNewOrder_reachesRingBuffer() throws InterruptedException {
        // Session tags (49/56/34) included to prove the gateway tolerates+skips them.
        byte[] frame = msg("35=D", "49=CLIENT", "56=OMS", "34=2",
                "11=12345", "54=1", "44=150.25", "38=100", "55=ASML");

        gateway.onFrame(frame);

        Observed o = handler.poll(1, TimeUnit.SECONDS);
        assertNotNull(o, "valid NewOrderSingle should reach the ring buffer");
        assertEquals(OrderEventType.NEW_ORDER, o.eventType());
        assertEquals(12345L, o.orderId());
        assertEquals(Side.BUY, o.side());          // 54=1
        assertEquals(1_502_500L, o.price());       // 150.25 -> units
        assertEquals(100L, o.quantity());
        assertEquals(-1L, o.originalOrderId());    // cleared for new orders
        assertEquals(FIXED_TS, o.timestamp());     // stamped by the gateway
    }

    @Test
    void validCancel_reachesRingBuffer() throws InterruptedException {
        byte[] frame = msg("35=F", "49=CLIENT", "56=OMS", "11=777", "41=12345");

        gateway.onFrame(frame);

        Observed o = handler.poll(1, TimeUnit.SECONDS);
        assertNotNull(o, "valid OrderCancelRequest should reach the ring buffer");
        assertEquals(OrderEventType.CANCEL_ORDER, o.eventType());
        assertEquals(777L, o.orderId());
        assertEquals(12345L, o.originalOrderId());
        assertNull(o.side());                      // cancels carry no side
        assertEquals(-1L, o.price());              // cleared for cancels
        assertEquals(-1L, o.quantity());
        assertEquals(FIXED_TS, o.timestamp());
    }

    @Test
    void rejectedFrame_isDroppedNotPublished() throws InterruptedException {
        // Valid frame, then corrupt the last checksum digit so tag 10 no longer
        // matches the computed sum. parse() rejects on checksum before dispatch.
        byte[] frame = msg("35=D", "11=12345", "54=1", "44=150.25", "38=100", "55=ASML");
        int d3 = frame.length - 2; // trailer is 10=xxx<SOH>; -2 is the 3rd checksum digit
        frame[d3] = (byte) (frame[d3] == '0' ? '1' : '0'); // guaranteed different digit

        gateway.onFrame(frame);

        assertNull(handler.poll(200, TimeUnit.MILLISECONDS),
                "rejected frame must not reach the ring buffer");
        assertEquals(0, handler.count());
    }

    @Test
    void nullFrame_isDropped() throws InterruptedException {
        gateway.onFrame(null);

        assertNull(handler.poll(200, TimeUnit.MILLISECONDS));
        assertEquals(0, handler.count());
    }

    @Test
    void slotReuse_secondMessageDoesNotBleedFirst() throws InterruptedException {
        // A NEW_ORDER then a CANCEL through the same gateway. The cancel must show
        // its own fields with no residue from the new order (side/price/qty cleared).
        gateway.onFrame(msg("35=D", "11=100", "54=2", "44=99.50", "38=50", "55=ASML"));
        gateway.onFrame(msg("35=F", "11=101", "41=100"));

        Observed first = handler.poll(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertEquals(OrderEventType.NEW_ORDER, first.eventType());
        assertEquals(Side.SELL, first.side());     // 54=2
        assertEquals(995_000L, first.price());

        Observed second = handler.poll(1, TimeUnit.SECONDS);
        assertNotNull(second);
        assertEquals(OrderEventType.CANCEL_ORDER, second.eventType());
        assertEquals(101L, second.orderId());
        assertEquals(100L, second.originalOrderId());
        assertNull(second.side());                 // no bleed from the new order
        assertEquals(-1L, second.price());
        assertEquals(-1L, second.quantity());
    }
}