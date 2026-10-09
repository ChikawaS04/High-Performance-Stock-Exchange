package net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import event.CapturingOrderHandler;
import event.InboundPipeline;
import event.OrderEventType;
import gateway.OrderGateway;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the frame handler directly via EmbeddedChannel over a real gateway + inbound ring. */
class WebSocketFrameHandlerTest {

    private static final long TIMEOUT_MS = 2_000;
    private static final long NEG_TIMEOUT_MS = 300; // long enough to prove nothing arrives

    /** Deterministic stand-in for the shared EpochNanoClock; the value only has to be recognisable. */
    private static final long TS = 1_700_000_000_123_456_789L;
    private static final LongSupplier CLOCK = () -> TS;

    private static final String NEW_JSON =
            "{\"type\":\"NEW\",\"clOrdId\":7,\"side\":\"BUY\",\"price\":1502500,\"qty\":100,\"symbol\":\"ASML\"}";

    private ChannelGroup group;
    private CapturingOrderHandler captured;
    private InboundPipeline pipeline;
    private OrderGateway gateway;
    private ObjectMapper mapper;
    private EmbeddedChannel channel;

    @BeforeEach
    void setup() {
        group = new DefaultChannelGroup("test", GlobalEventExecutor.INSTANCE);
        captured = new CapturingOrderHandler();
        pipeline = new InboundPipeline(captured);
        pipeline.start();
        gateway = new OrderGateway(pipeline.getRingBuffer());
        mapper = new ObjectMapper();
        channel = new EmbeddedChannel(new WebSocketFrameHandler(group, gateway, mapper, CLOCK));
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
        pipeline.shutdown();
    }

    // --- membership (carried from P4-4) ---

    @Test
    void addsChannelToGroupOnHandshakeComplete() {
        assertTrue(group.isEmpty());
        channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws", new DefaultHttpHeaders(), null));
        assertEquals(1, group.size());
        assertTrue(group.contains(channel));
    }

    @Test
    void ignoresUnrelatedUserEvents() {
        channel.pipeline().fireUserEventTriggered(new Object());
        assertTrue(group.isEmpty());
    }

    // --- inbound JSON -> FIX -> ring (P4-5) ---

    @Test
    void newOrderJsonReachesInboundRing() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(NEW_JSON));

        CapturingOrderHandler.Observed obs = captured.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(obs, "NEW order should reach the inbound ring");
        assertEquals(OrderEventType.NEW_ORDER, obs.eventType());
        assertEquals(7L, obs.orderId());
        assertEquals(Side.BUY, obs.side());
        assertEquals(1_502_500L, obs.price());
        assertEquals(100L, obs.quantity());
    }

    @Test
    void sellOrderMapsToSellSide() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"NEW\",\"clOrdId\":8,\"side\":\"SELL\",\"price\":1500000,\"qty\":50,\"symbol\":\"ASML\"}"));
        CapturingOrderHandler.Observed obs = captured.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(obs);
        assertEquals(Side.SELL, obs.side());
        assertEquals(1_500_000L, obs.price());
    }

    @Test
    void cancelJsonReachesInboundRing() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"CANCEL\",\"clOrdId\":9,\"origClOrdId\":7}"));
        CapturingOrderHandler.Observed obs = captured.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(obs, "CANCEL should reach the inbound ring");
        assertEquals(OrderEventType.CANCEL_ORDER, obs.eventType());
        assertEquals(9L, obs.orderId());
        assertEquals(7L, obs.originalOrderId());
    }

    @Test
    void malformedJsonPublishesNothing() throws Exception {
        channel.writeInbound(new TextWebSocketFrame("{not valid json"));
        assertNull(captured.poll(NEG_TIMEOUT_MS, TimeUnit.MILLISECONDS), "malformed JSON must not publish");
    }

    @Test
    void unknownTypePublishesNothing() throws Exception {
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"FOO\",\"clOrdId\":1}"));
        assertNull(captured.poll(NEG_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void invalidSidePublishesNothing() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"NEW\",\"clOrdId\":1,\"side\":\"HOLD\",\"price\":15000,\"qty\":10,\"symbol\":\"ASML\"}"));
        assertNull(captured.poll(NEG_TIMEOUT_MS, TimeUnit.MILLISECONDS), "unknown side dropped at the edge");
    }

    @Test
    void wrongSymbolPublishesNothing() throws Exception {
        // JsonToFix encodes it; the FIX parser rejects the symbol; the gateway logs+drops.
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"NEW\",\"clOrdId\":1,\"side\":\"BUY\",\"price\":15000,\"qty\":10,\"symbol\":\"MSFT\"}"));
        assertNull(captured.poll(NEG_TIMEOUT_MS, TimeUnit.MILLISECONDS), "wrong symbol rejected by parser");
    }

    // --- time in force on the inbound edge (Phase 14) ---

    @Test
    void missingTifDefaultsToGtcOnTheWire() throws Exception {
        // NEW_JSON carries no tif; the handler must default it to GTC and emit 59=1. The echoed
        // raw FIX is byte-identical to the GTC encoding, which is also what the 5-arg form emits.
        channel.writeInbound(new TextWebSocketFrame(NEW_JSON));

        CapturingOrderHandler.Observed obs = captured.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(obs, "a NEW order with no tif should still reach the inbound ring");

        JsonNode echo = readEcho();
        assertNotNull(echo, "the defaulted order should echo its raw FIX");
        String expected = new String(
                JsonToFix.newOrderSingle(7L, Side.BUY, 1_502_500L, 100L, "ASML", TimeInForce.GTC),
                StandardCharsets.ISO_8859_1);
        assertEquals(expected, echo.path("raw").asText(), "missing tif must encode as 59=1 (GTC)");
    }

    @Test
    void explicitIocTifReachesTheWire() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"NEW\",\"clOrdId\":7,\"side\":\"BUY\",\"tif\":\"IOC\",\"price\":1502500,\"qty\":100,\"symbol\":\"ASML\"}"));

        CapturingOrderHandler.Observed obs = captured.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(obs, "an IOC order should reach the inbound ring");

        JsonNode echo = readEcho();
        assertNotNull(echo, "the IOC order should echo its raw FIX");
        String expected = new String(
                JsonToFix.newOrderSingle(7L, Side.BUY, 1_502_500L, 100L, "ASML", TimeInForce.IOC),
                StandardCharsets.ISO_8859_1);
        assertEquals(expected, echo.path("raw").asText(), "tif IOC must encode as 59=3");
    }

    @Test
    void unknownTifPublishesNothing() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"NEW\",\"clOrdId\":1,\"side\":\"BUY\",\"tif\":\"DAY\",\"price\":1502500,\"qty\":100,\"symbol\":\"ASML\"}"));
        assertNull(captured.poll(NEG_TIMEOUT_MS, TimeUnit.MILLISECONDS), "unknown tif dropped at the edge");
    }

    @Test
    void unknownTifProducesNoEcho() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"NEW\",\"clOrdId\":1,\"side\":\"BUY\",\"tif\":\"DAY\",\"price\":1502500,\"qty\":100,\"symbol\":\"ASML\"}"));
        assertNull(channel.readOutbound(), "unknown tif must not echo");
    }

    // --- raw inbound FIX echo (P7-2) ---

    @Test
    void newOrderEchoesRawFixByteIdenticalToWhatTheParserConsumed() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(NEW_JSON));

        JsonNode echo = readEcho();
        assertNotNull(echo, "a NEW order should echo its raw FIX packet");
        assertEquals("FIX", echo.path("type").asText());
        assertEquals("INBOUND", echo.path("direction").asText());
        assertEquals(TS, echo.path("timestamp").asLong(), "echo must stamp from the injected clock");
        assertEquals(1L, echo.path("seqNum").asLong(), "first echo on this channel");

        // Byte identity: the echo must carry exactly what JsonToFix produced and the
        // gateway handed to FixParser, not a re-encoding of the JSON.
        String expected = new String(
                JsonToFix.newOrderSingle(7L, Side.BUY, 1_502_500L, 100L, "ASML"), StandardCharsets.ISO_8859_1);
        assertEquals(expected, echo.path("raw").asText());

        // Latin-1 round-trip is exact, so the string maps back to the original bytes.
        assertArrayEquals(
                JsonToFix.newOrderSingle(7L, Side.BUY, 1_502_500L, 100L, "ASML"),
                echo.path("raw").asText().getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void cancelEchoesRawFixAndSeqNumIncrementsPerEcho() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(NEW_JSON));
        JsonNode first = readEcho();
        assertNotNull(first);
        assertEquals(1L, first.path("seqNum").asLong());

        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"CANCEL\",\"clOrdId\":9,\"origClOrdId\":7}"));
        JsonNode second = readEcho();
        assertNotNull(second, "a CANCEL should echo too");
        assertEquals(2L, second.path("seqNum").asLong(), "counter advances once per emitted echo");

        String expected = new String(
                JsonToFix.orderCancelRequest(9L, 7L), StandardCharsets.ISO_8859_1);
        assertEquals(expected, second.path("raw").asText());
    }

    @Test
    void malformedJsonProducesNoEcho() throws Exception {
        channel.writeInbound(new TextWebSocketFrame("{not valid json"));
        assertNull(channel.readOutbound(), "malformed JSON must not echo");
    }

    @Test
    void unknownTypeAndInvalidSideProduceNoEcho() throws Exception {
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"FOO\",\"clOrdId\":1}"));
        assertNull(channel.readOutbound(), "unknown type must not echo");

        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"NEW\",\"clOrdId\":1,\"side\":\"HOLD\",\"price\":15000,\"qty\":10,\"symbol\":\"ASML\"}"));
        assertNull(channel.readOutbound(), "invalid side must not echo");
    }

    @Test
    void echoGoesToTheOriginatingChannelOnly() throws Exception {
        // Explicit DefaultChannelId: EmbeddedChannel's default id has a constant hashCode and
        // equals any other, so DefaultChannelGroup silently rejects the second member (P4-6 trap).
        EmbeddedChannel other = new EmbeddedChannel(
                DefaultChannelId.newInstance(),
                new WebSocketFrameHandler(group, gateway, mapper, CLOCK));
        try {
            assertTrue(group.add(channel));
            assertTrue(group.add(other));
            assertEquals(2, group.size(), "both channels must really be in the group");

            channel.writeInbound(new TextWebSocketFrame(NEW_JSON));

            assertNotNull(readEcho(), "the originating channel receives the echo");
            assertNull(other.readOutbound(),
                    "the echo must target the originating channel, never the channel group");
        } finally {
            other.finishAndReleaseAll();
        }
    }

    // --- iceberg max floor on the inbound edge (Phase 14-7) ---

    @Test
    void missingMaxFloorEmitsNo111() throws Exception {
        // NEW_JSON carries no maxFloor; the echoed raw FIX must equal the GTC encoding with no 111.
        channel.writeInbound(new TextWebSocketFrame(NEW_JSON));

        CapturingOrderHandler.Observed obs = captured.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(obs);

        JsonNode echo = readEcho();
        assertNotNull(echo);
        String expected = new String(
                JsonToFix.newOrderSingle(7L, Side.BUY, 1_502_500L, 100L, "ASML", TimeInForce.GTC, 0L),
                StandardCharsets.ISO_8859_1);
        assertEquals(expected, echo.path("raw").asText(), "no maxFloor must encode without tag 111");
    }

    @Test
    void maxFloorCarriedToTheWireAs111() throws Exception {
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"NEW\",\"clOrdId\":7,\"side\":\"SELL\",\"tif\":\"GTC\",\"price\":1502500,\"qty\":1000,\"maxFloor\":100,\"symbol\":\"ASML\"}"));

        CapturingOrderHandler.Observed obs = captured.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(obs, "an iceberg order should reach the inbound ring");

        JsonNode echo = readEcho();
        assertNotNull(echo);
        String expected = new String(
                JsonToFix.newOrderSingle(7L, Side.SELL, 1_502_500L, 1000L, "ASML", TimeInForce.GTC, 100L),
                StandardCharsets.ISO_8859_1);
        assertEquals(expected, echo.path("raw").asText(), "maxFloor must encode as tag 111=100");
    }

    /** Read one outbound text frame as parsed JSON, releasing it. Null when none is queued. */
    private JsonNode readEcho() throws Exception {
        Object out = channel.readOutbound();
        if (out == null) return null;
        TextWebSocketFrame frame = (TextWebSocketFrame) out;
        try {
            return mapper.readTree(frame.text());
        } finally {
            frame.release();
        }
    }
}
