package net;

import com.fasterxml.jackson.databind.ObjectMapper;
import gateway.OrderGateway;
import http.ApiHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
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
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import util.BookFrameCache;
import util.VenueStatus;

/**
 * Netty WebSocket server (SRS §3.6). Exposes {@code /ws} and, through {@link HttpRouter}, {@code /health} and {@code /api} on the same port (P15-7, P15-12); registers handshaken clients in a
 * shared {@link ChannelGroup}, and feeds inbound manual orders to the FIX gateway (P4-5).
 *
 * <p><b>Single-writer invariant (decision 2).</b> The worker group is <b>one thread</b>, so every
 * {@code onFrame} publish happens on one thread and the inbound Disruptor stays
 * {@code ProducerType.SINGLE} (§5.2). Do not widen the worker group. The P7-2 per-channel echo
 * counter also relies on this: with one worker thread it needs no synchronization.
 *
 * <p>{@code ws://}, no TLS (§3.6). One shared {@link ObjectMapper}, only ever touched by the
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
    /** Bound on event-loop termination at stop; no quiet period, so an idle server stops at once. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

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

    /** Serves RESEND (P15-6); null means RESEND frames are logged and dropped. Closed by {@link #stop()}. */
    private final ResendService resend;

    /** What {@code /health} reports (P15-7); null when this server serves no {@code /health}. */
    private final VenueStatus status;

    /** The {@code /api} routes (P15-12); null when this server serves none. Closed by {@link #stop()}. */
    private final ApiHandler api;

    public WebSocketServer(int port, OrderGateway gateway, LongSupplier clock) {
        this(port, gateway, clock, null);
    }

    /**
     * @param resend serves RESEND requests; the server owns it from here and closes it in
     *               {@link #stop()}, after the clients, so nothing reads the journal once stop returns
     */
    public WebSocketServer(int port, OrderGateway gateway, LongSupplier clock, ResendService resend) {
        this(port, gateway, clock, resend, null);
    }

    /**
     * @param resend serves RESEND requests, as in the four-argument constructor
     * @param status what {@code GET /health} reports (P15-7); null means this server serves no
     *               {@code /health} route and answers it with 404
     */
    public WebSocketServer(int port, OrderGateway gateway, LongSupplier clock, ResendService resend,
                           VenueStatus status) {
        this(port, gateway, clock, resend, status, null);
    }

    /**
     * @param status what {@code GET /health} reports, as in the five-argument constructor
     * @param api    the {@code /api} routes (P15-12); null means this server answers all of {@code /api}
     *               with 404. The server owns it from here and closes it in {@link #stop()}, after the
     *               clients and before the event loops
     */
    public WebSocketServer(int port, OrderGateway gateway, LongSupplier clock, ResendService resend,
                           VenueStatus status, ApiHandler api) {
        this.port = port;
        this.gateway = gateway;
        this.clock = clock;
        this.resend = resend;
        this.status = status;
        this.api = api;
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

    /** {@link #open()} then {@link #bind()}: for callers with nothing to run on the gateway's thread first. */
    public void start() throws InterruptedException {
        open();
        bind();
    }

    /**
     * Creates the event loops without binding the port (P15-8). The single worker exists from here,
     * so work can be handed to the gateway's thread ({@link #gatewayExecutor()}) before any client
     * can connect: Main opens the session this way, then calls {@link #bind()}.
     */
    public void open() {
        if (workerGroup != null) throw new IllegalStateException("already open");
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()); // single worker
    }

    /**
     * The single worker event loop: the only thread that calls the gateway (D15). Tasks run in
     * submission order, before any channel read, if they are submitted before {@link #bind()}.
     */
    public EventLoop gatewayExecutor() {
        if (workerGroup == null) throw new IllegalStateException("open() first");
        return (EventLoop) workerGroup.next();
    }

    /** Binds the port; {@link #open()} must have run. */
    public void bind() throws InterruptedException {
        if (workerGroup == null) throw new IllegalStateException("open() first");
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                        // Exact /ws passes to the handshake; /health and every other path end here.
                        p.addLast(new HttpRouter(WEBSOCKET_PATH, status, objectMapper, api));
                        p.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH));
                        p.addLast(new WebSocketHeartbeatHandler(HEARTBEAT_INTERVAL_SECONDS));
                        p.addLast(new WebSocketFrameHandler(channelGroup, gateway, objectMapper, clock, bookFrameCache,
                                resend));
                    }
                });

        serverChannel = b.bind(port).sync().channel();
        log.info("WebSocket server listening on ws://0.0.0.0:{}{}{}", boundPort(), WEBSOCKET_PATH,
                status == null ? "" : ", health on http://0.0.0.0:" + boundPort() + HttpRouter.HEALTH_PATH);
    }

    /**
     * Stops accepting connections, closes every client, stops the resend thread, and waits
     * (bounded) for the event loops to terminate. Once this returns, the worker thread can no
     * longer call the gateway, so graceful shutdown can drain the inbound ring knowing nothing more
     * will be published (P15-4), and no resend reads the journal it is about to close (P15-6).
     */
    public void stop() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        channelGroup.close().awaitUninterruptibly();
        if (resend != null) {
            resend.close();   // a resend in progress sees its channel closed and stops
        }
        if (api != null) {
            api.close();      // no more /api reads; an answer still in flight is dropped with its channel
        }
        boolean terminated = true;
        if (workerGroup != null) {
            terminated &= workerGroup.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .awaitUninterruptibly(2 * SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            terminated &= bossGroup.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .awaitUninterruptibly(2 * SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (terminated) {
            log.info("WebSocket server stopped");
        } else {
            log.warn("WebSocket server event loops did not terminate within {} s", 2 * SHUTDOWN_TIMEOUT_SECONDS);
        }
    }
}