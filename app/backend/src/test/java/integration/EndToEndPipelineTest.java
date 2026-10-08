package integration;

import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import event.CapturingExecutionHandler;
import event.CapturingExecutionHandler.Observed;
import event.ExecutionEventType;
import event.InboundPipeline;
import event.OutboundPipeline;
import gateway.FixFrameDecoder;
import gateway.OrderGateway;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SRS §6.2 end-to-end pipeline test. Drives raw FIX wire bytes in at the very
 * front and asserts on ExecutionEvents observed at a subscriber on the far
 * (outbound) side, verifying the whole path:
 *
 *   raw bytes
 *     -> FixFrameDecoder            (length-prefixed framing, real Netty decode)
 *     -> OrderGateway.onFrame       (parse + timestamp + publish)
 *     -> inbound RingBuffer<OrderEvent>
 *     -> MatchingEngineHandler      (build Order, drive matching, publish executions)
 *     -> outbound RingBuffer<ExecutionEvent>
 *     -> CapturingExecutionHandler  (test subscriber)
 *
 * Option A (chosen): the framing hop runs the real FixFrameDecoder inside an
 * EmbeddedChannel rather than the static frame helper, so the decode path is
 * exercised honestly. The test itself plays the thin Netty->gateway adapter
 * (the production adapter is Phase 4): it drains each decoded byte[] frame out
 * of the channel and hands it to gateway.onFrame.
 *
 * Two async hops (inbound + outbound consumer threads), so assertions wait via
 * CapturingExecutionHandler.awaitAtLeast rather than reading synchronously.
 * Event order is deterministic: one inbound consumer processes ring events FIFO
 * and the handler publishes synchronously in-callback, so a single outbound
 * consumer observes executions in submission order.
 */
class EndToEndPipelineTest {

    private static final byte SOH = 0x01;
    private static final char SOH_C = (char) SOH;

    private MatchingEngine engine;
    private OutboundPipeline outbound;
    private InboundPipeline inbound;
    private OrderGateway gateway;
    private CapturingExecutionHandler captured;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        engine   = new MatchingEngine();
        captured = new CapturingExecutionHandler();

        // Outbound half first: the engine handler needs a live ring to produce into.
        outbound = new OutboundPipeline();
        outbound.handleEventsWith(captured);
        outbound.start();

        MatchingEngineHandler handler =
                new MatchingEngineHandler(engine, outbound.getRingBuffer());
        engine.setExecutionListener(handler);   // caller wires the listener (as in 7.5)

        // Inbound half: the engine handler is the ring's sole consumer.
        inbound = new InboundPipeline(handler);
        inbound.start();

        gateway = new OrderGateway(inbound.getRingBuffer());

        // Real decoder, driven off-channel; emits per-frame byte[] copies.
        channel = new EmbeddedChannel(new FixFrameDecoder());
    }

    @AfterEach
    void tearDown() {
        if (channel != null)  channel.finishAndReleaseAll();
        if (inbound != null)  inbound.shutdown();   // stop feeding outbound first
        if (outbound != null) outbound.shutdown();
    }

    // --- the Netty->gateway adapter the test stands in for ---

    /** Decode one wire buffer into frames and drive each into the gateway. */
    private void send(byte[] wire) {
        channel.writeInbound(Unpooled.wrappedBuffer(wire));
        Object frame;
        while ((frame = channel.readInbound()) != null) {
            gateway.onFrame((byte[]) frame);
        }
    }

    // --- tests ---

    @Test
    void newOrderRestsOnEmptyBook_publishesAccepted() {
        send(newOrder(100, '1', "150.00", 100));    // BUY, nothing to cross

        List<Observed> obs = captured.awaitAtLeast(1, 1000);
        assertEquals(1, obs.size());

        Observed a = obs.get(0);
        assertEquals(ExecutionEventType.ORDER_ACCEPTED, a.eventType());
        assertEquals(100, a.orderId());
        assertEquals(1_500_000, a.price());
        assertEquals(100, a.remainingQuantity());
    }

    @Test
    void crossingOrder_fullyFills_publishesAcceptedThenFilled() {
        send(newOrder(200, '2', "150.00", 100));    // SELL rests -> ACCEPTED
        send(newOrder(201, '1', "150.00", 100));    // BUY crosses fully -> FILLED

        List<Observed> obs = captured.awaitAtLeast(2, 1000);
        assertEquals(2, obs.size());

        assertEquals(ExecutionEventType.ORDER_ACCEPTED, obs.get(0).eventType());
        assertEquals(200, obs.get(0).orderId());

        Observed fill = obs.get(1);
        assertEquals(ExecutionEventType.ORDER_FILLED, fill.eventType());
        assertEquals(201, fill.orderId());          // aggressor
        assertEquals(201, fill.aggressorOrderId());
        assertEquals(200, fill.passiveOrderId());   // resting sell
        assertEquals(1_500_000, fill.price());          // passive (resting) price
        assertEquals(100, fill.filledQuantity());
        assertEquals(0, fill.remainingQuantity());
        assertTrue(fill.tradeId() > 0);
    }

    @Test
    void crossingOrder_partiallyFills_publishesPartialThenAcceptedForRemainder() {
        send(newOrder(300, '2', "150.00", 50));     // SELL 50 rests -> ACCEPTED
        send(newOrder(301, '1', "150.00", 80));     // BUY 80: fills 50, rests 30

        List<Observed> obs = captured.awaitAtLeast(3, 1000);
        assertEquals(3, obs.size());

        assertEquals(ExecutionEventType.ORDER_ACCEPTED, obs.get(0).eventType());
        assertEquals(300, obs.get(0).orderId());

        Observed partial = obs.get(1);
        assertEquals(ExecutionEventType.ORDER_PARTIALLY_FILLED, partial.eventType());
        assertEquals(301, partial.orderId());
        assertEquals(301, partial.aggressorOrderId());
        assertEquals(300, partial.passiveOrderId());
        assertEquals(1_500_000, partial.price());
        assertEquals(50, partial.filledQuantity());
        assertEquals(30, partial.remainingQuantity());
        assertTrue(partial.tradeId() > 0);

        // Trailing ACCEPTED for the 30 that rested — also the §6.2 book-state check:
        // the residual is on the book, so the aggressor's remainder was acknowledged.
        Observed rested = obs.get(2);
        assertEquals(ExecutionEventType.ORDER_ACCEPTED, rested.eventType());
        assertEquals(301, rested.orderId());
        assertEquals(30, rested.remainingQuantity());
    }

    @Test
    void cancelOfRestingOrder_publishesCancelled() {
        send(newOrder(400, '1', "150.00", 100));    // BUY rests -> ACCEPTED
        send(cancel(401, 400));                     // cancel order 400 -> CANCELLED

        List<Observed> obs = captured.awaitAtLeast(2, 1000);
        assertEquals(2, obs.size());

        assertEquals(ExecutionEventType.ORDER_ACCEPTED, obs.get(0).eventType());

        Observed cancelled = obs.get(1);
        assertEquals(ExecutionEventType.ORDER_CANCELLED, cancelled.eventType());
        // orderId = OrigClOrdID (the cancelled order). If onEvent's cancel branch
        // stamps the cancel request's ClOrdID instead, change 400 -> 401 here.
        assertEquals(400, cancelled.orderId());
    }

    @Test
    void cancelOfUnknownOrder_publishesRejected() {
        send(cancel(500, 999));                     // nothing resting with id 999

        List<Observed> obs = captured.awaitAtLeast(1, 1000);
        assertEquals(1, obs.size());

        Observed rejected = obs.get(0);
        assertEquals(ExecutionEventType.ORDER_REJECTED, rejected.eventType());
        // Same orderId caveat as cancelOfRestingOrder_publishesCancelled (999 vs 500).
        assertEquals(999, rejected.orderId());
    }

    @Test
    void malformedFrame_isDroppedAtGateway_nothingPublished() {
        // BodyLength stays intact so the decoder still frames it cleanly and it
        // reaches the gateway — but the checksum is wrong, so FixParser rejects at
        // the boundary (logged per SRS §5.4, then dropped). Nothing reaches the ring.
        send(msgBadChecksum("35=D", "11=600", "54=1", "44=150.00", "38=100", "55=ASML"));

        List<Observed> obs = captured.awaitAtLeast(1, 250);
        assertTrue(obs.isEmpty(),
                "a checksum-invalid frame must not produce any execution event");
    }

    // --- FIX message builders (54=1 BUY, 54=2 SELL; price in dollars, symbol ASML) ---

    private static byte[] newOrder(long clOrdId, char side, String price, long qty) {
        return msg("35=D", "11=" + clOrdId, "54=" + side,
                "44=" + price, "38=" + qty, "55=ASML");
    }

    private static byte[] cancel(long clOrdId, long origClOrdId) {
        return msg("35=F", "11=" + clOrdId, "41=" + origClOrdId);
    }

    /**
     * Assemble a complete wire message from body fields (35= onward, no SOH —
     * added here): prepend 8=FIX.4.2 and a correct BodyLength (tag 9), append a
     * correct 3-digit CheckSum (tag 10). Correct BodyLength is the point —
     * framing keys entirely off it.
     */
    private static byte[] msg(String... bodyFields) {
        byte[] body = body(bodyFields);
        byte[] header = header(body.length);
        byte[] trailer = trailer(checksum(header, body));
        return concat(header, body, trailer);
    }

    /** As {@link #msg} but with a deliberately wrong checksum; BodyLength unchanged. */
    private static byte[] msgBadChecksum(String... bodyFields) {
        byte[] body = body(bodyFields);
        byte[] header = header(body.length);
        byte[] trailer = trailer((checksum(header, body) + 1) & 0xFF);   // off by one
        return concat(header, body, trailer);
    }

    private static byte[] body(String[] bodyFields) {
        StringBuilder b = new StringBuilder();
        for (String f : bodyFields) b.append(f).append(SOH_C);
        return b.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] header(int bodyLen) {
        return ("8=FIX.4.2" + SOH_C + "9=" + bodyLen + SOH_C)
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] trailer(int checksum) {
        return ("10=" + String.format("%03d", checksum) + SOH_C)
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static int checksum(byte[] header, byte[] body) {
        int sum = 0;
        for (byte x : header) sum += (x & 0xFF);
        for (byte x : body)   sum += (x & 0xFF);
        return sum & 0xFF;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }
}