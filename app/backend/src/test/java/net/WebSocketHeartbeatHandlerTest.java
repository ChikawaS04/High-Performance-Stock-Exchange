package net;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Drives the keepalive directly over an EmbeddedChannel. EmbeddedChannel keeps a virtual clock, so
 * {@code runScheduledPendingTasks()} advances it to the next scheduled ping and runs it — the test
 * is fully deterministic with no wall-clock wait (no Thread.sleep).
 */
class WebSocketHeartbeatHandlerTest {

    private static WebSocketServerProtocolHandler.HandshakeComplete handshake() {
        return new WebSocketServerProtocolHandler.HandshakeComplete("/ws", new DefaultHttpHeaders(), null);
    }

    @Test
    void emitsOnePingPerIntervalOnceTheHandshakeCompletes() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebSocketHeartbeatHandler(1));

        ch.pipeline().fireUserEventTriggered(handshake());
        assertNull(ch.readOutbound(), "no ping should be sent before the interval elapses");

        // EmbeddedChannel freezes its event-loop clock; advance it past the interval, then run
        // the now-due scheduled task. Fully deterministic, no wall-clock wait.
        ch.advanceTimeBy(1, TimeUnit.SECONDS);
        ch.runScheduledPendingTasks();
        Object out = ch.readOutbound();
        assertInstanceOf(PingWebSocketFrame.class, out, "a WebSocket PING should be emitted on the interval");
        ReferenceCountUtil.release(out);

        assertFalse(ch.finish());
    }

    @Test
    void schedulesNothingBeforeTheHandshake() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebSocketHeartbeatHandler(1));

        // No HandshakeComplete fired: the channel is still "HTTP", so nothing is scheduled and
        // advancing the clock produces no frame. A pre-handshake PingWebSocketFrame would have no
        // WebSocket encoder to pass through, which is exactly what this guards against.
        ch.runScheduledPendingTasks();
        assertNull(ch.readOutbound());

        assertFalse(ch.finish());
    }
}
