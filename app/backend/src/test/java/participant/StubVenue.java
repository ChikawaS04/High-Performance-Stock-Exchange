package participant;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A tiny stand-in venue for the bots' connection tests: {@code GET /health} with a scripted status,
 * and {@code /ws} recording every text frame received and sending whatever the test pushes. Real
 * sockets on 127.0.0.1, an ephemeral port.
 */
final class StubVenue implements AutoCloseable {

    final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    final AtomicLong publishedSeq = new AtomicLong();
    final AtomicInteger healthFailuresLeft = new AtomicInteger();
    final AtomicInteger healthCalls = new AtomicInteger();
    final AtomicInteger connections = new AtomicInteger();
    volatile String session = "OPEN";
    /** Called on the event loop for every text frame, after it is recorded; may answer with {@link #send}. */
    volatile Consumer<String> onText = s -> { };

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final ChannelGroup clients = new DefaultChannelGroup("stub", GlobalEventExecutor.INSTANCE);
    private final Channel server;

    StubVenue() throws InterruptedException {
        server = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65_536),
                                new WebSocketServerProtocolHandler("/ws"), new Handler());
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
    }

    URI venue() {
        return URI.create("http://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort());
    }

    URI ws() {
        return venue().resolve("/ws").toString().replace("http:", "ws:").transform(URI::create);
    }

    URI health() {
        return venue().resolve("/health");
    }

    /** Push one text frame to every connected client. */
    void send(String json) {
        clients.writeAndFlush(new TextWebSocketFrame(json));
    }

    /** Close every client connection, as a venue restart does. */
    void dropAll() throws InterruptedException {
        clients.close().await(5, TimeUnit.SECONDS);
    }

    int connected() {
        return clients.size();
    }

    /** The next received frame containing {@code fragment}, waiting at most {@code seconds}; null on timeout. */
    String await(String fragment, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return null;
            String s = received.poll(left, TimeUnit.NANOSECONDS);
            if (s == null) return null;
            if (s.contains(fragment)) return s;
        }
    }

    @Override
    public void close() throws InterruptedException {
        clients.close().await(5, TimeUnit.SECONDS);
        server.close().await(5, TimeUnit.SECONDS);
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
    }

    private final class Handler extends SimpleChannelInboundHandler<Object> {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                clients.add(ctx.channel());
                connections.incrementAndGet();
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof TextWebSocketFrame t) {
                String text = t.text();
                received.add(text);
                onText.accept(text);
                return;
            }
            if (msg instanceof FullHttpRequest req && req.uri().equals("/health")) {
                healthCalls.incrementAndGet();
                boolean fail = healthFailuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
                String body = "{\"running\":true,\"session\":\"" + session + "\",\"publishedOutboundSeq\":"
                        + publishedSeq.get() + "}";
                FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        fail ? HttpResponseStatus.SERVICE_UNAVAILABLE : HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(fail ? "{}" : body, StandardCharsets.UTF_8));
                res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
                ctx.writeAndFlush(res);
                return;
            }
            FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
            res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(res);
        }
    }
}
