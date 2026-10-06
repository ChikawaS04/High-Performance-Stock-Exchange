package net;

import com.fasterxml.jackson.databind.ObjectMapper;
import gateway.OrderGateway;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.function.LongSupplier;

import util.BookFrameCache;

/**
 * Netty WebSocket server (SRS §3.6). Exposes {@code /ws}, registers handshaken clients in a
 * shared {@link ChannelGroup}, and feeds inbound manual orders to the FIX gateway (P4-5).
 *
 * <p><b>Single-writer invariant (decision 2).</b> The worker group is <b>one thread</b>, so every
 * {@code onFrame} publish happens on one thread and the inbound Disruptor stays
 * {@code ProducerType.SINGLE} (§5.2). Do not widen the worker group. The P7-2 per-channel echo
 * counter also relies on this: with one worker thread it needs no synchronization.
 *
 * <p>{@code ws://}, no TLS (§3.6). One shared {@link ObjectMapper} — only ever touched by the
 * single worker thread.
 *
 * <p><b>Clock (P7-2).</b> The server does not stamp anything itself; it holds the shared
 * {@link util.EpochNanoClock} solely to hand the same instance to every per-channel handler, so
 * the raw-FIX echo timestamps share the one epoch-nanos anchor established in P7-1.
 */
public final class WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);

    private static final String WEBSOCKET_PATH = "/ws";
    private static final int MAX_HTTP_CONTENT_LENGTH = 64 * 1024;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 30;

    private final int port;
    private final OrderGateway gateway;
    private final LongSupplier clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChannelGroup channelGroup =
            new DefaultChannelGroup("ws-clients", GlobalEventExecutor.INSTANCE);
    private final BookFrameCache bookFrameCache = new BookFrameCache();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public WebSocketServer(int port, OrderGateway gateway, LongSupplier clock) {
        this.port = port;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** Shared connected-client registry; P4-6's publisher writes execution/book frames to this. */
    public ChannelGroup getChannelGroup() {
        return channelGroup;
    }

    /**
     * Shared latest-BOOK-frame cache: the publisher fills it on each snapshot, and the per-channel
     * handler reads it to send a connecting client the current depth. Wired through here so the
     * publisher receives the same instance, mirroring {@link #getChannelGroup()}.
     */
    public BookFrameCache getBookFrameCache() {
        return bookFrameCache;
    }

    /** The actually-bound port. Useful when constructed with port 0 (ephemeral) in tests. */
    public int boundPort() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void start() throws InterruptedException {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()); // single worker

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                        p.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH));
                        p.addLast(new WebSocketHeartbeatHandler(HEARTBEAT_INTERVAL_SECONDS));
                        p.addLast(new WebSocketFrameHandler(channelGroup, gateway, objectMapper, clock, bookFrameCache));
                    }
                });

        serverChannel = b.bind(port).sync().channel();
        log.info("WebSocket server listening on ws://0.0.0.0:{}{}", boundPort(), WEBSOCKET_PATH);
    }

    public void stop() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        channelGroup.close().awaitUninterruptibly();
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        log.info("WebSocket server stopped");
    }
}