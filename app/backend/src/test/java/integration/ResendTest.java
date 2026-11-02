package integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.EventHandler;
import event.ExecutionEvent;
import gateway.OrderGateway;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import net.ResendService;
import net.WebSocketFrameHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import publisher.ExecutionFrames;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static integration.RecoveryScenario.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RESEND over the real outbound recording (P15-6, SRS §3.6, D16): a client receives exactly the
 * recorded events from {@code fromSeq}, each frame byte-identical to the live EXEC frame, then
 * the completion frame; another client receives nothing; out-of-range requests replay what
 * exists; and after a restart RESEND serves the whole history, gap-free.
 */
class ResendTest {

    @TempDir
    Path root;

    /** Live EXEC JSON as the publisher would have sent it, captured on the outbound ring. */
    private static final class LiveFrames implements EventHandler<ExecutionEvent> {
        final ExecutionFrames frames = new ExecutionFrames();
        final List<String> json = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onEvent(ExecutionEvent event, long sequence, boolean endOfBatch) {
            json.add(frames.toJson(event));
        }

        List<String> snapshot() {
            synchronized (json) {
                return new ArrayList<>(json);
            }
        }
    }

    private record Clients(ResendService service, EmbeddedChannel a, EmbeddedChannel b) implements AutoCloseable {
        @Override
        public void close() {
            service.close();
            a.finishAndReleaseAll();
            b.finishAndReleaseAll();
        }
    }

    private static Clients clients(JournaledVenue venue) {
        ResendService service = new ResendService(venue.pipeline().outboundHistory(), new ExecutionFrames()::toJson);
        ChannelGroup group = new DefaultChannelGroup("test", GlobalEventExecutor.INSTANCE);
        EmbeddedChannel a = channel(group, service);
        EmbeddedChannel b = channel(group, service);
        assertEquals(2, group.size());
        return new Clients(service, a, b);
    }

    private static EmbeddedChannel channel(ChannelGroup group, ResendService service) {
        EmbeddedChannel ch = new EmbeddedChannel(DefaultChannelId.newInstance(),
                new WebSocketFrameHandler(group, null, new ObjectMapper(), () -> 0L, null, service));
        ch.pipeline().fireUserEventTriggered(
                new WebSocketServerProtocolHandler.HandshakeComplete("/ws", new DefaultHttpHeaders(), null));
        return ch;
    }

    /** Sends a RESEND and returns every frame the channel received for it, completion last. */
    private static List<String> resend(Clients c, EmbeddedChannel ch, long fromSeq) {
        long before = c.service().finishedRequests();
        ch.writeInbound(new TextWebSocketFrame("{\"type\":\"RESEND\",\"fromSeq\":" + fromSeq + "}"));
        await(() -> c.service().finishedRequests() > before, "resend served");
        return drain(ch);
    }

    private static List<String> drain(EmbeddedChannel ch) {
        List<String> out = new ArrayList<>();
        Object msg;
        while ((msg = ch.readOutbound()) != null) {
            TextWebSocketFrame frame = (TextWebSocketFrame) msg;
            out.add(frame.text());
            frame.release();
        }
        return out;
    }

    private static String complete(long fromSeq, long toSeq, long count) {
        return "{\"type\":\"RESEND_COMPLETE\",\"fromSeq\":" + fromSeq + ",\"toSeq\":" + toSeq + ",\"count\":" + count + "}";
    }

    private static List<String> runScenario(JournaledVenue venue, LiveFrames live) {
        List<byte[]> frames = RecoveryScenario.frames();
        OrderGateway gateway = venue.gateway(RecoveryScenario.countingClock(1_000L));
        venue.start();
        for (byte[] frame : frames) gateway.onFrame(frame);
        await(() -> venue.pipeline().appliedSeq() == frames.size(), "scenario applied");
        await(() -> live.snapshot().size() == venue.pipeline().publishedSeq(), "live frames captured");
        return live.snapshot();
    }

    @Test
    void aResendReturnsExactlyTheRecordedFramesToTheRequestingChannelOnly() {
        JournaledVenue venue = JournaledVenue.create(root);
        LiveFrames live = new LiveFrames();
        venue.outbound().handleEventsWith(live);
        List<String> original = runScenario(venue, live);
        int n = original.size();

        try (Clients c = clients(venue)) {
            List<String> expected = new ArrayList<>(original.subList(2, n));
            expected.add(complete(3, n, n - 2));
            assertEquals(expected, resend(c, c.a(), 3), "seq 3 to n, byte-identical, then the completion");
            assertTrue(drain(c.b()).isEmpty(), "the other channel received none of it");

            List<String> all = new ArrayList<>(original);
            all.add(complete(0, n, n));
            assertEquals(all, resend(c, c.a(), 0), "fromSeq below 1 replays from the first event");

            assertEquals(List.of(complete(n + 5, n, 0)), resend(c, c.b(), n + 5), "beyond the end: nothing to resend");
            assertTrue(drain(c.a()).isEmpty());
        }
        venue.close();
    }

    @Test
    void afterARestartTheWholeHistoryIsResentGapFree() {
        JournaledVenue first = JournaledVenue.create(root);
        LiveFrames live = new LiveFrames();
        first.outbound().handleEventsWith(live);
        List<String> original = runScenario(first, live);
        first.close();

        JournaledVenue again = JournaledVenue.create(root);
        LiveFrames afterRestart = new LiveFrames();
        again.outbound().handleEventsWith(afterRestart);
        OrderGateway gateway = again.gateway(RecoveryScenario.countingClock(4_000_000_000_000_000_000L));
        again.start();
        gateway.onFrame(FixMessages.limit(500, '1', "1.00", 1));
        await(() -> afterRestart.snapshot().size() == 1, "one live event after the restart");

        try (Clients c = clients(again)) {
            List<String> expected = new ArrayList<>(original);
            expected.add(afterRestart.snapshot().get(0));
            expected.add(complete(1, original.size() + 1, original.size() + 1));
            assertEquals(expected, resend(c, c.a(), 1), "both runs' events, one continuous seq");
        }
        again.close();
    }
}
