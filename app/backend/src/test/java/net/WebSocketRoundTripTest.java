package net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import event.InboundPipeline;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import gateway.OrderGateway;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import market.MarketDataService;
import model.Side;
import publisher.TradeLogger;
import publisher.WebSocketPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import util.EpochNanoClock;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4-8 — SRS §6.2 WebSocket round-trip. A real Netty WS client connects to the fully
 * assembled server (built in {@code @BeforeEach} on an ephemeral port, mirroring Main's
 * wiring order), submits orders as JSON, and asserts the EXEC/BOOK JSON pushed back.
 *
 * <p>Two properties of the running system dictate the test design:
 * <ul>
 *   <li><b>EXEC and BOOK are independent consumers</b> (separate sequence counters, §3.4)
 *       and interleave unpredictably — a BOOK can be written before an EXEC with an earlier
 *       timestamp. So {@link LoopbackClient#awaitFrame} never assumes "frame N is the fill":
 *       it matches on a parsed predicate and, crucially, does <b>not discard</b> non-matching
 *       frames — each stage asserts on both an EXEC and a BOOK, so a BOOK seen while waiting
 *       for its EXEC must survive for the next await. Matched frames are removed so no frame
 *       is matched twice; the expected book <i>shape</i> is folded into the predicate so a
 *       stale {@code [[1500000,10]]} never satisfies a {@code [[1500000,6]]} await. P7-2 adds a
 *       third frame type (FIX) into the same stream; the non-discarding matcher absorbs it
 *       with no change to the existing awaits.</li>
 *   <li><b>Four-plus daemon-thread hops</b> (client loop → server worker → inbound ring →
 *       engine → outbound/snapshot rings → publisher → client loop). Every assertion is a
 *       bounded await (2s ceiling, fast path returns immediately); no {@code Thread.sleep}
 *       is used as a synchronisation primitive. The sole poll is the group-registration
 *       precondition below, which is a membership gate, not an assertion sync.</li>
 * </ul>
 *
 * <p>The client scaffold reuses P4-4's {@code WebSocketServerTest} idiom verbatim
 * (HttpClientCodec → aggregator → {@code WebSocketClientProtocolHandler} with a
 * {@code HANDSHAKE_COMPLETE} latch), extended with a text-frame sink and matchers.
 */
class WebSocketRoundTripTest {

    private static final String ASML = "ASML";
    private static final long PX = 1_500_000L;             // $150.00 in units of $0.0001
    private static final long[][] NONE = new long[0][];    // empty side

    private MatchingEngine engine;
    private OutboundPipeline outbound;
    private SnapshotPipeline snapshots;
    private InboundPipeline inbound;
    private WebSocketServer server;
    private LoopbackClient client;

    /**
     * Full pipeline in Main's load-bearing order, on an ephemeral port. Then connect a client
     * and wait until the server has registered it in the ChannelGroup — otherwise the very
     * first snapshot could be published while the group is still empty and
     * {@code WebSocketPublisher}'s empty-group early-out would silently drop that BOOK frame.
     */
    @BeforeEach
    void setUp() throws Exception {
        engine = new MatchingEngine();
        outbound = new OutboundPipeline();
        snapshots = new SnapshotPipeline();

        MatchingEngineHandler handler =
                new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
        engine.setExecutionListener(handler);   // silent-failure trap if omitted: no EXEC ever

        inbound = new InboundPipeline(handler);
        OrderGateway gateway = new OrderGateway(inbound.getRingBuffer());

        // P7-2: the edge needs a clock for the raw-FIX echo stamp. A real EpochNanoClock here
        // rather than a fixed supplier, so the echo assertions also prove a sane epoch value.
        server = new WebSocketServer(0, gateway, new EpochNanoClock());
        WebSocketPublisher publisher = new WebSocketPublisher(server.getChannelGroup());

        // Register the two publisher roles on their respective rings, plus the console
        // subscribers, before starting any pipeline.
        outbound.handleEventsWith(publisher.executionHandler(), new TradeLogger());
        snapshots.handleEventsWith(publisher.snapshotHandler(), new MarketDataService());

        // Outbound rings must be live before inbound can publish into the engine.
        outbound.start();
        snapshots.start();
        inbound.start();
        server.start();

        client = new LoopbackClient();
        client.connect(server.boundPort());
        assertTrue(awaitGroupSize(1, 2000),
                "server should register the connected client before the test body runs");
    }

    /** Teardown in the strict reverse of setup, client first so no new frames enter. */
    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.stop();
        if (inbound != null) inbound.shutdown();
        if (snapshots != null) snapshots.shutdown();
        if (outbound != null) outbound.shutdown();
    }

    // ------------------------------------------------------------------ tests

    /**
     * One book walked empty → resting → reduced → empty, asserting the EXEC and BOOK at each
     * stage. Consolidated into a single sequential test because the three stages share book
     * state; splitting them would re-establish the resting book each time for no gain.
     */
    @Test
    void fullRoundTrip_restThenCrossThenCancel() throws Exception {
        // 1) Resting BUY 10 @ 150.00 -> ORDER_ACCEPTED + BOOK carrying the bid level.
        client.send(newOrder(1, "BUY", PX, 10));

        JsonNode accepted = client.awaitFrame(exec("ORDER_ACCEPTED", 1), 2000);
        assertNotNull(accepted, "resting BUY should push an ORDER_ACCEPTED EXEC");
        assertEquals(10, accepted.path("remainingQuantity").asLong());
        assertEquals(PX, accepted.path("price").asLong());
        assertEquals(-1, accepted.path("tradeId").asLong(), "ACCEPTED carries the -1 NA sentinel");

        JsonNode restBook = client.awaitFrame(book(PX, level(PX, 10), NONE), 2000);
        assertNotNull(restBook, "resting BUY should push a BOOK with bids [[1500000,10]]");
        assertEquals(-1, restBook.path("bestAsk").asLong(), "no ask side yet");

        // 2) Crossing SELL 4 @ 150.00 -> ORDER_FILLED at the passive price + BOOK reduced to 6.
        client.send(newOrder(2, "SELL", PX, 4));

        JsonNode filled = client.awaitFrame(exec("ORDER_FILLED", 2), 2000);
        assertNotNull(filled, "crossing SELL should push an ORDER_FILLED EXEC");
        assertTrue(filled.path("tradeId").asLong() > 0, "fill should carry a positive tradeId");
        assertEquals(PX, filled.path("price").asLong(), "fill at the passive resting price");
        assertEquals(4, filled.path("filledQuantity").asLong());
        assertEquals(0, filled.path("remainingQuantity").asLong());
        assertEquals(2, filled.path("aggressorOrderId").asLong());
        assertEquals(1, filled.path("passiveOrderId").asLong());

        JsonNode reducedBook = client.awaitFrame(book(PX, level(PX, 6), NONE), 2000);
        assertNotNull(reducedBook, "after the 4-lot fill the bid level should read 6");

        // 3) Cancel the resting remainder (OrigClOrdID = 1) -> ORDER_CANCELLED + empty book.
        client.send(cancelOrder(3, 1));

        // The handler stamps orderId = OrigClOrdID on the cancel report (verified against
        // MatchingEngineHandler's cancel branch), i.e. the cancelled order's id, not the
        // cancel request's ClOrdID.
        JsonNode cancelled = client.awaitFrame(exec("ORDER_CANCELLED", 1), 2000);
        assertNotNull(cancelled, "cancel should push an ORDER_CANCELLED EXEC for order id 1");

        JsonNode emptyBook = client.awaitFrame(book(-1, NONE, NONE), 2000);
        assertNotNull(emptyBook, "cancelling the only order should empty the book");
        assertEquals(-1, emptyBook.path("bestAsk").asLong());
    }

    /**
     * P7-2: the echoed packet must be the bytes the server actually parsed. Asserted by
     * rebuilding the expected packet with the same encoder the server used and comparing the
     * whole SOH-delimited string, so a divergent {@code 9=} or {@code 10=} would fail here.
     */
    @Test
    void rawFixEcho_isByteIdenticalToWhatTheParserConsumed() throws Exception {
        client.send(newOrder(1, "BUY", PX, 10));

        JsonNode echo = client.awaitFrame(fix(), 2000);
        assertNotNull(echo, "a NEW order should echo its raw FIX packet");
        assertEquals("INBOUND", echo.path("direction").asText());
        assertEquals(1, echo.path("seqNum").asLong(), "first echo on this connection");
        assertTrue(echo.path("timestamp").asLong() > 0, "echo stamps from the shared epoch clock");

        String expectedNew = new String(
                JsonToFix.newOrderSingle(1L, Side.BUY, PX, 10L, ASML), StandardCharsets.ISO_8859_1);
        assertEquals(expectedNew, echo.path("raw").asText());

        // A CANCEL echoes too, and the per-connection counter advances.
        client.send(cancelOrder(3, 1));

        JsonNode cancelEcho = client.awaitFrame(fix(), 2000);
        assertNotNull(cancelEcho, "a CANCEL should echo its raw FIX packet");
        assertEquals(2, cancelEcho.path("seqNum").asLong(), "counter advances once per echo");

        String expectedCancel = new String(
                JsonToFix.orderCancelRequest(3L, 1L), StandardCharsets.ISO_8859_1);
        assertEquals(expectedCancel, cancelEcho.path("raw").asText());
    }

    /**
     * Negative path: a malformed JSON frame is dropped at the boundary — nothing published,
     * and no echo — and neither the single worker thread nor the connection dies, proven by a
     * subsequent valid order still round-tripping on the same channel.
     */
    @Test
    void malformedJson_publishesNothing_andConnectionSurvives() throws Exception {
        client.send("{\"type\":\"NEW\", this is not valid json");
        client.assertNoFrame(execOrBook(), 500);   // bounded window; malformed never reaches the engine
        client.assertNoFrame(fix(), 500);          // P7-2: and it never produces an echo

        // Same connection, a well-formed order still works end to end.
        client.send(newOrder(10, "BUY", PX, 5));

        JsonNode accepted = client.awaitFrame(exec("ORDER_ACCEPTED", 10), 2000);
        assertNotNull(accepted, "a valid order after a malformed one should still be accepted");
        assertEquals(5, accepted.path("remainingQuantity").asLong());

        JsonNode validBook = client.awaitFrame(book(PX, level(PX, 5), NONE), 2000);
        assertNotNull(validBook, "the valid order should still push a BOOK frame");

        // The malformed frame consumed no sequence number: this is the connection's first echo.
        JsonNode echo = client.awaitFrame(fix(), 2000);
        assertNotNull(echo, "the valid order should echo");
        assertEquals(1, echo.path("seqNum").asLong(), "a dropped frame must not consume a seqNum");
    }

    // ------------------------------------------------------------ JSON builders

    private static String newOrder(long clOrdId, String side, long priceUnits, long qty) {
        return "{\"type\":\"NEW\",\"clOrdId\":" + clOrdId + ",\"side\":\"" + side + "\",\"price\":"
                + priceUnits + ",\"qty\":" + qty + ",\"symbol\":\"" + ASML + "\"}";
    }

    private static String cancelOrder(long clOrdId, long origClOrdId) {
        return "{\"type\":\"CANCEL\",\"clOrdId\":" + clOrdId + ",\"origClOrdId\":" + origClOrdId + "}";
    }

    // ------------------------------------------------------------ frame predicates

    private static Predicate<JsonNode> exec(String execType, long orderId) {
        return n -> "EXEC".equals(n.path("type").asText())
                && execType.equals(n.path("execType").asText())
                && n.path("orderId").asLong() == orderId;
    }

    private static Predicate<JsonNode> execOrBook() {
        return n -> {
            String t = n.path("type").asText();
            return "EXEC".equals(t) || "BOOK".equals(t);
        };
    }

    /** Any raw inbound FIX echo (P7-2). */
    private static Predicate<JsonNode> fix() {
        return n -> "FIX".equals(n.path("type").asText());
    }

    private static Predicate<JsonNode> book(long bestBid, long[][] bids, long[][] asks) {
        return n -> "BOOK".equals(n.path("type").asText())
                && n.path("bestBid").asLong() == bestBid
                && levelsMatch(n.path("bids"), bids)
                && levelsMatch(n.path("asks"), asks);
    }

    private static boolean levelsMatch(JsonNode arr, long[][] expected) {
        if (arr == null || !arr.isArray() || arr.size() != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            JsonNode lvl = arr.get(i);
            if (lvl.get(0).asLong() != expected[i][0] || lvl.get(1).asLong() != expected[i][1]) {
                return false;
            }
        }
        return true;
    }

    private static long[][] level(long price, long qty) {
        return new long[][]{{price, qty}};
    }

    // ------------------------------------------------------------ group-registration gate

    /** Bounded poll on the server-side ChannelGroup membership. Reuses the P4-4 idiom; this is
     *  a precondition gate (membership updates on the server loop), not an assertion sync. */
    private boolean awaitGroupSize(int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (server.getChannelGroup().size() == expected) return true;
            Thread.sleep(10);
        }
        return server.getChannelGroup().size() == expected;
    }

    // ------------------------------------------------------------ loopback client

    /**
     * A real Netty WS loopback client extending the P4-4 scaffold with a text-frame sink and
     * interleaving-tolerant matchers. The Netty client thread only ever appends raw frame text
     * to {@link #incoming}; all parsing and matching happens on the test thread.
     */
    private static final class LoopbackClient {

        private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        private final ObjectMapper mapper = new ObjectMapper();

        /** Handoff from the client event loop to the test thread. */
        private final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        /** Parsed-but-not-yet-matched frames; test-thread only. */
        private final List<JsonNode> received = new ArrayList<>();

        private Channel channel;

        void connect(int port) throws Exception {
            CountDownLatch handshakeDone = new CountDownLatch(1);
            URI uri = URI.create("ws://127.0.0.1:" + port + "/ws");

            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                            ch.pipeline().addLast(new WebSocketClientProtocolHandler(
                                    WebSocketClientHandshakerFactory.newHandshaker(
                                            uri, WebSocketVersion.V13, null, false,
                                            new DefaultHttpHeaders())));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<WebSocketFrame>() {
                                @Override
                                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                                    if (evt == WebSocketClientProtocolHandler
                                            .ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                                        handshakeDone.countDown();
                                    }
                                }

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
                                    if (frame instanceof TextWebSocketFrame text) {
                                        incoming.add(text.text());
                                    }
                                }
                            });
                        }
                    });

            channel = b.connect(uri.getHost(), port).sync().channel();
            assertTrue(handshakeDone.await(5, TimeUnit.SECONDS), "client handshake should complete");
        }

        void send(String json) {
            channel.writeAndFlush(new TextWebSocketFrame(json));   // marshalled onto the client loop
        }

        /**
         * Return the first frame satisfying {@code cond} within the timeout, removing it so it
         * cannot be matched again. Non-matching frames are retained for later awaits. Returns
         * {@code null} on timeout.
         */
        JsonNode awaitFrame(Predicate<JsonNode> cond, long timeoutMillis) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (true) {
                for (int i = 0; i < received.size(); i++) {
                    if (cond.test(received.get(i))) {
                        return received.remove(i);
                    }
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                String text = incoming.poll(remaining, TimeUnit.NANOSECONDS);
                if (text != null) received.add(mapper.readTree(text));
            }
        }

        /** Assert no frame satisfying {@code cond} arrives within the window. */
        void assertNoFrame(Predicate<JsonNode> cond, long windowMillis) throws Exception {
            JsonNode hit = awaitFrame(cond, windowMillis);
            assertNull(hit, "expected no matching frame but received: " + hit);
        }

        void close() throws Exception {
            if (channel != null) channel.close().sync();
            group.shutdownGracefully();
        }
    }
}