package net;

import com.fasterxml.jackson.databind.ObjectMapper;
import event.CapturingOrderHandler;
import event.InboundPipeline;
import gateway.OrderGateway;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import util.BookFrameCache;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HandshakeComplete must hand a just-connected client the cached current book, so a fresh or
 * reconnecting terminal is not stuck on an empty ladder until the next order flows.
 */
class WebSocketSnapshotOnConnectTest {

    private static final LongSupplier CLOCK = () -> 1_700_000_000_000_000_000L;
    private static final String BOOK_JSON =
            "{\"type\":\"BOOK\",\"bestBid\":15000,\"bestAsk\":-1,\"bids\":[[15000,6]],\"asks\":[],\"timestamp\":1}";

    private ChannelGroup group;
    private InboundPipeline pipeline;
    private OrderGateway gateway;
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        group = new DefaultChannelGroup("test", GlobalEventExecutor.INSTANCE);
        pipeline = new InboundPipeline(new CapturingOrderHandler());
        pipeline.start();
        gateway = new OrderGateway(pipeline.getRingBuffer());
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        pipeline.shutdown();
    }

    private static WebSocketServerProtocolHandler.HandshakeComplete handshake() {
        return new WebSocketServerProtocolHandler.HandshakeComplete("/ws", new DefaultHttpHeaders(), null);
    }

    @Test
    void sendsTheCachedBookToANewlyHandshakenClient() {
        BookFrameCache cache = new BookFrameCache();
        cache.set(BOOK_JSON);
        EmbeddedChannel ch = new EmbeddedChannel(
                new WebSocketFrameHandler(group, gateway, mapper, CLOCK, cache));

        ch.pipeline().fireUserEventTriggered(handshake());

        Object out = ch.readOutbound();
        assertInstanceOf(TextWebSocketFrame.class, out, "a BOOK frame should be sent on connect");
        TextWebSocketFrame frame = (TextWebSocketFrame) out;
        assertTrue(frame.text().contains("\"type\":\"BOOK\""));
        frame.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void sendsNothingWhenNoBookHasBeenPublishedYet() {
        BookFrameCache cache = new BookFrameCache(); // empty: no snapshot published yet
        EmbeddedChannel ch = new EmbeddedChannel(
                new WebSocketFrameHandler(group, gateway, mapper, CLOCK, cache));

        ch.pipeline().fireUserEventTriggered(handshake());

        assertNull(ch.readOutbound(), "no BOOK frame until the first snapshot is published");
        ch.finishAndReleaseAll();
    }
}
