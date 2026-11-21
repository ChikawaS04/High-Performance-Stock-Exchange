package net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The limiter's admit path allocates nothing (P16-9): a public frame that passes all three buckets goes through
 * the limiter on the channel's own thread with no allocation. Measured with the per-thread allocation counter,
 * as the recorder and sequencer allocation tests do, over one reused frame.
 */
class InboundFrameLimiterAllocationTest {

    private static final int WARMUP = 50_000;
    private static final int FRAMES = 200_000;
    private static final long SLACK_BYTES = 1_024;

    private static final class Counting extends ChannelInboundHandlerAdapter {
        long seen;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            seen++;   // the frame is reused by the test: neither released nor passed on
        }
    }

    @Test
    void theAdmitPathAllocatesNothing() throws Exception {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "per-thread allocation counter not available");
        com.sun.management.ThreadMXBean counter = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(counter.isThreadAllocatedMemorySupported(), "per-thread allocation counter not supported");
        counter.setThreadAllocatedMemoryEnabled(true);

        // Every frame conforms: a million a second sustained, and the clock moves a microsecond a frame.
        AtomicLong clock = new AtomicLong();
        AccessControl access = new AccessControl(new AccessControl.Limits(1_000_000, 1_000_000, 1_000_000,
                1_000_000, 1_000_000, 1_000_000, 5_000, 200, 10, 64, List.of(), List.of()), clock::get);
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte) 198, 51, 100, 1}),
                50_000);
        ChannelAccess state = new ChannelAccess();
        Counting sink = new Counting();
        EmbeddedChannel ch = new EmbeddedChannel(DefaultChannelId.newInstance(), false, false) {
            @Override
            protected SocketAddress remoteAddress0() {
                return remote;
            }
        };
        ch.pipeline().addLast(new InboundFrameLimiter(access, state), sink);
        ch.register();
        assertTrue(access.admit(state, ch.remoteAddress(), List.of()));
        assertTrue(state.isPublic(), "the metered path, not the exempt one");

        TextWebSocketFrame frame = new TextWebSocketFrame("{\"type\":\"NEW\"}");
        for (int i = 0; i < WARMUP; i++) {
            clock.addAndGet(1_000);
            ch.pipeline().fireChannelRead(frame);
        }
        long before = counter.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < FRAMES; i++) {
            clock.addAndGet(1_000);
            ch.pipeline().fireChannelRead(frame);
        }
        long allocated = counter.getCurrentThreadAllocatedBytes() - before;

        assertEquals(WARMUP + FRAMES, sink.seen, "every frame passed");
        assertEquals(0, access.droppedChannel() + access.droppedClient() + access.droppedPublic());
        assertTrue(allocated < SLACK_BYTES, "the limiter allocated " + allocated + " bytes over " + FRAMES + " frames");
        frame.release();
        ch.finishAndReleaseAll();
    }
}
