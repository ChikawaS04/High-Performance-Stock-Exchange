package net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import event.ExecutionEvent;
import event.ExecutionHistory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import journal.OutboundRecordings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import publisher.ExecutionFrames;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RESEND serving (P15-6, SRS §3.6, D16) over a scripted history: frames go to the requesting
 * channel only, identical to the live serializer's; out-of-range and malformed requests; the
 * per-channel and venue-wide limits; a client that disconnects mid-resend; a failed replay.
 *
 * Channels are {@link EmbeddedChannel}s with distinct ids (a shared default id makes the group
 * drop the second). Their event loop runs a write on the calling thread, so the resend thread
 * writes directly; the test reads a channel only after {@link ResendService#finishedRequests()}
 * has advanced, which orders those writes before the read.
 */
class ResendServiceTest {

    private static final int EVENTS = 10;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutionFrames frames = new ExecutionFrames();
    private ChannelGroup group;
    private ScriptedHistory history;
    private ResendService service;
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    /** Events 1..n; may hold every replay until released, or fail. */
    private static final class ScriptedHistory implements ExecutionHistory {
        final List<ExecutionEvent> events = new ArrayList<>();
        final AtomicLong published = new AtomicLong();
        final AtomicLong lastThroughSeq = new AtomicLong(-1);
        volatile CountDownLatch hold;
        volatile RuntimeException failure;

        ScriptedHistory(int n) {
            for (int i = 1; i <= n; i++) events.add(OutboundRecordings.fill(i));
            published.set(n);
        }

        @Override
        public long publishedSeq() {
            return published.get();
        }

        @Override
        public Result replay(long fromSeq, long throughSeq, Visitor visitor) {
            lastThroughSeq.set(throughSeq);
            CountDownLatch h = hold;
            if (h != null) {
                try {
                    assertTrue(h.await(10, TimeUnit.SECONDS), "held replay released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) throw failure;
            long last = 0;
            long count = 0;
            for (ExecutionEvent e : events) {
                last = e.seq;
                if (e.seq < fromSeq) continue;
                if (!visitor.onEvent(e)) return new Result(last, count, true);
                count++;
            }
            return new Result(last, count, false);
        }
    }

    @BeforeEach
    void setUp() {
        group = new DefaultChannelGroup("test", GlobalEventExecutor.INSTANCE);
        history = new ScriptedHistory(EVENTS);
        service = new ResendService(history, frames::toJson);
    }

    @AfterEach
    void tearDown() {
        service.close();
        for (EmbeddedChannel ch : channels) ch.finishAndReleaseAll();
    }

    private EmbeddedChannel connect() {
        EmbeddedChannel ch = new EmbeddedChannel(DefaultChannelId.newInstance(),
                new WebSocketFrameHandler(group, null, mapper, () -> 0L, null, service));
        ch.pipeline().fireUserEventTriggered(
                new WebSocketServerProtocolHandler.HandshakeComplete("/ws", new DefaultHttpHeaders(), null));
        channels.add(ch);
        return ch;
    }

    private static void resend(EmbeddedChannel ch, String fromSeq) {
        ch.writeInbound(new TextWebSocketFrame("{\"type\":\"RESEND\",\"fromSeq\":" + fromSeq + "}"));
    }

    private void awaitFinished(long n) {
        await(() -> service.finishedRequests() >= n, n + " resend(s) finished");
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

    private List<String> execs(int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) out.add(frames.toJson(OutboundRecordings.fill(i)));
        return out;
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String complete(long fromSeq, long toSeq, long count) {
        return "{\"type\":\"RESEND_COMPLETE\",\"fromSeq\":" + fromSeq + ",\"toSeq\":" + toSeq + ",\"count\":" + count + "}";
    }

    private static String rejected(long fromSeq, String reason) {
        return "{\"type\":\"RESEND_REJECTED\",\"fromSeq\":" + fromSeq + ",\"reason\":\"" + reason + "\"}";
    }

    @Test
    void framesGoToTheRequestingChannelOnlyAndMatchTheLiveSerializer() {
        EmbeddedChannel a = connect();
        EmbeddedChannel b = connect();
        assertEquals(2, group.size(), "distinct channel ids: both registered");

        resend(a, "3");
        awaitFinished(1);

        List<String> expected = execs(3, EVENTS);
        expected.add(complete(3, EVENTS, EVENTS - 2));
        assertEquals(expected, drain(a));
        assertTrue(drain(b).isEmpty(), "the other channel receives nothing");
        assertEquals(EVENTS, history.lastThroughSeq.get(), "the target is the published seq at request time");
    }

    @Test
    void fromSeqBelowOneResendsEverythingAndBeyondTheEndResendsNothing() {
        EmbeddedChannel a = connect();
        resend(a, "0");
        awaitFinished(1);
        List<String> all = execs(1, EVENTS);
        all.add(complete(0, EVENTS, EVENTS));
        assertEquals(all, drain(a));

        resend(a, "-5");
        awaitFinished(2);
        List<String> negative = execs(1, EVENTS);
        negative.add(complete(-5, EVENTS, EVENTS));
        assertEquals(negative, drain(a));

        resend(a, Integer.toString(EVENTS + 1));
        awaitFinished(3);
        assertEquals(List.of(complete(EVENTS + 1, EVENTS, 0)), drain(a));
    }

    @Test
    void malformedRequestsAreDroppedWithNoReply() {
        EmbeddedChannel a = connect();
        a.writeInbound(new TextWebSocketFrame("{\"type\":\"RESEND\"}"));
        resend(a, "\"3\"");
        resend(a, "3.5");
        resend(a, "null");
        resend(a, "99999999999999999999");   // integral, but beyond a long
        a.writeInbound(new TextWebSocketFrame("{\"type\":\"RESEND\",\"fromSeq\":"));
        // Validation happens synchronously on the calling (worker) thread: nothing was queued.
        assertEquals(0, service.finishedRequests());
        assertEquals(-1, history.lastThroughSeq.get(), "no replay was started");
        assertTrue(drain(a).isEmpty(), "no reply and no FIX echo");
    }

    @Test
    void aSecondRequestFromTheSameChannelIsRejectedWhileOneIsInProgress() {
        history.hold = new CountDownLatch(1);
        EmbeddedChannel a = connect();
        resend(a, "1");
        await(() -> history.lastThroughSeq.get() >= 0, "the first resend started");
        resend(a, "5");
        assertEquals(List.of(rejected(5, "IN_PROGRESS")), drain(a), "rejected at once, on the worker");

        history.hold.countDown();
        awaitFinished(1);
        List<String> expected = execs(1, EVENTS);
        expected.add(complete(1, EVENTS, EVENTS));
        assertEquals(expected, drain(a));
    }

    @Test
    void requestsBeyondTheVenueWideLimitAreRejectedBusy() {
        history.hold = new CountDownLatch(1);
        List<EmbeddedChannel> served = new ArrayList<>();
        for (int i = 0; i < ResendService.MAX_PENDING; i++) {
            EmbeddedChannel ch = connect();
            served.add(ch);
            resend(ch, "9");
        }
        EmbeddedChannel extra = connect();
        resend(extra, "9");
        assertEquals(List.of(rejected(9, "BUSY")), drain(extra));

        history.hold.countDown();
        awaitFinished(ResendService.MAX_PENDING);
        for (EmbeddedChannel ch : served) {
            assertEquals(List.of(frames.toJson(OutboundRecordings.fill(9)), frames.toJson(OutboundRecordings.fill(10)),
                    complete(9, EVENTS, 2)), drain(ch));
        }
    }

    @Test
    void aClientThatDisconnectsMidResendStopsItAndTheNextRequestIsServed() {
        EmbeddedChannel a = connect();
        // Closes the channel as the third frame is written, on the resend thread.
        a.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            int written;

            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ctx.writeAndFlush(msg, promise);
                if (++written == 3) ctx.close();
            }
        });
        EmbeddedChannel b = connect();

        resend(a, "1");
        awaitFinished(1);
        List<String> seenByA = drain(a);
        assertEquals(execs(1, 3), seenByA, "three frames, then the replay stopped: no completion frame");
        assertFalse(a.isActive());

        resend(b, "8");
        awaitFinished(2);
        List<String> expected = execs(8, EVENTS);
        expected.add(complete(8, EVENTS, 3));
        assertEquals(expected, drain(b));
    }

    @Test
    void aReplayThatFailsEndsWithRejectedFailed() {
        history.failure = new IllegalStateException("replay stalled (test)");
        EmbeddedChannel a = connect();
        resend(a, "2");
        awaitFinished(1);
        assertEquals(List.of(rejected(2, "FAILED")), drain(a));

        history.failure = null;
        resend(a, "10");
        awaitFinished(2);
        assertEquals(List.of(frames.toJson(OutboundRecordings.fill(10)), complete(10, EVENTS, 1)), drain(a),
                "the service carries on after a failed resend");
    }

    @Test
    void theCompletionFrameIsWellFormed() {
        EmbeddedChannel a = connect();
        resend(a, "4");
        awaitFinished(1);
        List<String> out = drain(a);
        JsonNode done = json(out.get(out.size() - 1));
        assertEquals("RESEND_COMPLETE", done.path("type").asText());
        assertEquals(4, done.path("fromSeq").asLong());
        assertEquals(EVENTS, done.path("toSeq").asLong());
        assertEquals(EVENTS - 3, done.path("count").asLong());
        assertEquals(EVENTS - 3, out.size() - 1);
    }

    @Test
    void afterCloseRequestsAreIgnored() {
        EmbeddedChannel a = connect();
        service.close();
        resend(a, "1");   // refused synchronously: nothing is queued once the service is closed
        assertEquals(0, service.finishedRequests());
        assertTrue(drain(a).isEmpty());
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
