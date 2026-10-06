package net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Application-level keepalive for the WebSocket terminal.
 *
 * <p><b>Why this exists.</b> The book the client renders is a pure projection of the live stream:
 * the frontend reducer freezes/marks-stale the ladder the moment the socket closes, and nothing in
 * this stack previously put any traffic on an otherwise silent connection. An idle socket carrying
 * no frames for a couple of minutes was being reaped (by the browser, the OS, or any intermediary
 * that drops idle connections), and the terminal went blank. One small WebSocket PING per interval
 * keeps the socket warm; the browser answers it with an automatic PONG, so a healthy-but-quiet
 * connection is held open.
 *
 * <p><b>No new dependency.</b> This deliberately does not use {@code IdleStateHandler} (which lives
 * in the {@code netty-handler} module this project does not depend on). The project's Netty surface
 * is intentionally just {@code netty-codec} + {@code netty-codec-http} + transport, so the ping is
 * scheduled directly on the channel's event loop via {@code ctx.executor().scheduleAtFixedRate}.
 * The trade-off is that the ping fires on a fixed cadence rather than only-when-idle; for a
 * keepalive that is immaterial (one tiny control frame per interval, no application state).
 *
 * <p><b>Threading.</b> The scheduled task runs on this channel's event loop — the single worker
 * thread — so the {@code writeAndFlush} needs no synchronization and does not widen the
 * single-writer surface: a PING carries no application state and touches no book or ring.
 *
 * <p><b>Lifecycle.</b> Pinging starts on the WebSocket {@link WebSocketServerProtocolHandler.HandshakeComplete}
 * event, never on {@code channelActive}: before the handshake the channel is still HTTP and a
 * {@code PingWebSocketFrame} would have no WebSocket encoder to pass through. The task is cancelled
 * on {@code channelInactive} and {@code handlerRemoved}, so a closed connection schedules nothing.
 * The HandshakeComplete event is re-fired to the tail so the per-channel {@link WebSocketFrameHandler}
 * still registers the channel in its group.
 *
 * <p>Not {@code @Sharable}: it holds a per-channel {@link ScheduledFuture}, and the server
 * initializer creates one per channel, matching {@link WebSocketFrameHandler}'s per-channel lifetime.
 */
public final class WebSocketHeartbeatHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHeartbeatHandler.class);

    private final long intervalSeconds;
    private ScheduledFuture<?> pinger;

    public WebSocketHeartbeatHandler(long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete && pinger == null) {
            pinger = ctx.executor().scheduleAtFixedRate(
                    () -> {
                        if (ctx.channel().isActive()) {
                            ctx.writeAndFlush(new PingWebSocketFrame());
                        }
                    },
                    intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            log.debug("Heartbeat armed ({}s) for {}", intervalSeconds, ctx.channel().remoteAddress());
        }
        // Always propagate: the frame handler also reacts to HandshakeComplete (group registration).
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancel();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancel();
    }

    private void cancel() {
        if (pinger != null) {
            pinger.cancel(false);
            pinger = null;
        }
    }
}
