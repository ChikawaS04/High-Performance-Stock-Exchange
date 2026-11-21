package net;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;

/**
 * Meters every inbound WebSocket frame of one channel against the public access limits (P16-9), before anything
 * acts on it. It sits after {@link HttpRouter} and ahead of Netty's protocol handler (and the UTF-8 validator
 * Netty inserts directly before it), so it sees every decoded frame: text, binary and continuation frames, pings
 * and pongs, malformed JSON and invalid UTF-8 alike, and garbage cannot bypass the buckets. Only Close frames
 * pass unmetered, so the close handshake always works.
 *
 * <p>A dropped frame is released and goes no further: no JSON parse, no gateway call, never sequenced or
 * journaled, and a dropped ping gets no pong, so a ping flood costs one bucket check per frame and no write.
 * When {@link AccessControl#onFrame} says the channel has been over its limit too long, a 1008 close frame is
 * written and the channel closed.
 *
 * <p>The admit path allocates nothing: a type check, one clock read and the bucket arithmetic. HTTP requests
 * before the upgrade pass through untouched. Not {@code @Sharable}: one per channel, on the single worker.
 */
final class InboundFrameLimiter extends ChannelInboundHandlerAdapter {

    private final AccessControl access;
    private final ChannelAccess channel;

    InboundFrameLimiter(AccessControl access, ChannelAccess channel) {
        this.access = access;
        this.channel = channel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof WebSocketFrame) || msg instanceof CloseWebSocketFrame) {
            ctx.fireChannelRead(msg);
            return;
        }
        int verdict = access.onFrame(channel, access.now());
        if (verdict == AccessControl.PASS) {
            ctx.fireChannelRead(msg);
            return;
        }
        ReferenceCountUtil.release(msg);
        if (verdict == AccessControl.CLOSE) {
            // From the tail, so the frame passes Netty's protocol handler, which records that a close was sent;
            // written from here it would be bypassed, and the channel close would add Netty's own 1000 close.
            ctx.channel().writeAndFlush(new CloseWebSocketFrame(1008, "rate limit"))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }
}
