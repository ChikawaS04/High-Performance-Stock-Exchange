package net;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-channel frame limiter in a channel built like the server's (P16-9): limiter, then Netty's own protocol
 * handler (which answers pings and inserts its UTF-8 validator right before itself), then a sink that stands in
 * for the frame handler and counts what reaches it.
 */
class InboundFrameLimiterTest {

    private static final long MS = 1_000_000L;

    private final AtomicLong clock = new AtomicLong(7_000 * MS);
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (EmbeddedChannel ch : channels) ch.finishAndReleaseAll();
    }

    /** An EmbeddedChannel with an IP remote address, as a real socket has. */
    private static final class IpChannel extends EmbeddedChannel {
        private static volatile InetSocketAddress next;
        private final InetSocketAddress remote = next;

        IpChannel() {
            super(DefaultChannelId.newInstance(), false, false);
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return remote;
        }
    }

    /** Counts and releases what passes the limiter and the protocol handler. */
    private static final class Sink extends ChannelInboundHandlerAdapter {
        final List<Class<?>> seen = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            seen.add(msg.getClass());
            ReferenceCountUtil.release(msg);
        }
    }

    private AccessControl access(List<String> exempt) {
        return new AccessControl(AccessControl.Limits.defaults(List.of(), exempt), clock::get);
    }

    private EmbeddedChannel channel(AccessControl access, String ip, Sink sink) {
        try {
            IpChannel.next = new InetSocketAddress(InetAddress.getByAddress(ClientKey.literal(ip)), 50_000);
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
        EmbeddedChannel ch = new IpChannel();
        ChannelAccess state = new ChannelAccess();
        ch.pipeline().addLast(new InboundFrameLimiter(access, state));
        ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws"));
        ch.pipeline().addLast(sink);
        try {
            ch.register();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        // After a real upgrade Netty's handshake handler removes itself; remove it here to get that pipeline.
        ch.pipeline().remove("io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandshakeHandler");
        assertTrue(access.admit(state, ch.remoteAddress(), List.of()));
        channels.add(ch);
        return ch;
    }

    private static TextWebSocketFrame text(String s) {
        return new TextWebSocketFrame(s);
    }

    private static int count(EmbeddedChannel ch, Class<?> type) {
        int n = 0;
        Object msg;
        while ((msg = ch.readOutbound()) != null) {
            if (type.isInstance(msg)) n++;
            ReferenceCountUtil.release(msg);
        }
        return n;
    }

    @Test
    void theLimiterSitsAheadOfNettysUtf8Validator() {
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(access(List.of()), "198.51.100.1", sink);
        List<String> names = ch.pipeline().names();
        int limiter = -1;
        int validator = -1;
        for (int i = 0; i < names.size(); i++) {
            if (ch.pipeline().get(names.get(i)) instanceof InboundFrameLimiter) limiter = i;
            if (ch.pipeline().get(names.get(i)) instanceof Utf8FrameValidator) validator = i;
        }
        assertTrue(limiter >= 0 && validator > limiter, "limiter " + limiter + " before validator " + validator);

        // Behaviourally: with the bucket spent, an invalid UTF-8 frame is dropped before the validator sees it
        // (the validator would close the channel); with tokens, it reaches the validator, which closes.
        for (int i = 0; i < 30; i++) ch.writeInbound(text("x"));
        ch.writeInbound(new TextWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {(byte) 0xC3, (byte) 0x28})));
        assertTrue(ch.isOpen(), "metered and dropped first");
        clock.addAndGet(1_000 * MS);
        assertThrows(Exception.class, () -> ch.writeInbound(
                new TextWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {(byte) 0xC3, (byte) 0x28}))));
        assertFalse(ch.isOpen(), "admitted, then refused by Netty's validator");
    }

    @Test
    void framesWithinTheLimitPassAndABurstIsTrimmed() {
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(access(List.of()), "198.51.100.1", sink);
        for (int i = 0; i < 100; i++) ch.writeInbound(text("{\"type\":\"NEW\"}"));
        assertEquals(30, sink.seen.size());
        clock.addAndGet(1_000 * MS);
        for (int i = 0; i < 10; i++) ch.writeInbound(text("{\"type\":\"NEW\"}"));
        assertEquals(40, sink.seen.size(), "ten a second refill");
        assertTrue(ch.isOpen());
    }

    @Test
    void garbageOfEveryKindIsCountedLikeAnyFrame() {
        Sink sink = new Sink();
        AccessControl a = access(List.of());
        EmbeddedChannel ch = channel(a, "198.51.100.1", sink);
        for (int i = 0; i < 10; i++) {
            ch.writeInbound(text("not json at all"));
            ch.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {1, 2, 3})));
            ch.writeInbound(new ContinuationWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {4})));
        }
        assertEquals(30, sink.seen.size(), "the burst, whatever the frames are");
        assertEquals(0, a.droppedChannel());
        ch.writeInbound(text("{\"type\":\"NEW\"}"));
        assertEquals(30, sink.seen.size(), "a well-formed frame is refused once garbage spent the bucket");
        assertEquals(1, a.droppedChannel());
    }

    @Test
    void aPingFloodGetsNoPongsPastTheBurst() {
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(access(List.of()), "198.51.100.1", sink);
        for (int i = 0; i < 200; i++) {
            ch.writeInbound(new PingWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {(byte) i})));
        }
        assertEquals(30, count(ch, PongWebSocketFrame.class), "only the admitted pings were answered");
    }

    @Test
    void aCloseFrameAlwaysPasses() {
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(access(List.of()), "198.51.100.1", sink);
        for (int i = 0; i < 100; i++) ch.writeInbound(text("x"));
        ch.writeInbound(new CloseWebSocketFrame(1000, "bye"));
        assertEquals(1, count(ch, CloseWebSocketFrame.class), "Netty answered the close handshake");
    }

    @Test
    void sustainedExcessClosesWith1008AndAnotherChannelKeepsPassing() {
        AccessControl a = access(List.of());
        Sink floodSink = new Sink();
        Sink calmSink = new Sink();
        EmbeddedChannel flood = channel(a, "198.51.100.1", floodSink);
        EmbeddedChannel calm = channel(a, "198.51.100.2", calmSink);
        int calmSent = 0;
        for (int step = 0; step < 700 && flood.isOpen(); step++) {   // 10 ms steps: 100 frames a second
            clock.addAndGet(10 * MS);
            flood.writeInbound(text("{\"type\":\"NEW\"}"));
            if (step % 20 == 0) {
                calm.writeInbound(text("{\"type\":\"NEW\"}"));
                calmSent++;
            }
        }
        assertFalse(flood.isOpen(), "closed");
        Object out;
        CloseWebSocketFrame close = null;
        while ((out = flood.readOutbound()) != null) {
            if (out instanceof CloseWebSocketFrame c) close = (CloseWebSocketFrame) c.retain();
            ReferenceCountUtil.release(out);
        }
        assertNotNull(close, "a close frame was written");
        assertEquals(1008, close.statusCode());
        close.release();
        assertEquals(1, a.closed1008());
        assertEquals(calmSent, calmSink.seen.size(), "the other client was never limited");
        assertTrue(calm.isOpen());
    }

    @Test
    void anExemptChannelPassesTheBotsPeakUntouched() {
        Sink sink = new Sink();
        EmbeddedChannel bot = channel(access(List.of("198.51.100.10")), "198.51.100.10", sink);
        for (int i = 0; i < 253; i++) {   // F8: 253 frames in one second
            clock.addAndGet(1_000 * MS / 253);
            bot.writeInbound(text("{\"type\":\"NEW\",\"clOrdId\":" + i + "}"));
        }
        assertEquals(253, sink.seen.size());
        for (Class<?> c : sink.seen) assertEquals(TextWebSocketFrame.class, c);
    }

    @Test
    void droppedFramesAreReleased() {
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(access(List.of()), "198.51.100.1", sink);
        List<WebSocketFrame> frames = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            TextWebSocketFrame f = text("{\"type\":\"NEW\"}");
            frames.add(f);
            ch.writeInbound(f);
        }
        for (WebSocketFrame f : frames) assertEquals(0, f.refCnt(), "passed and dropped frames alike are released");
    }
}
