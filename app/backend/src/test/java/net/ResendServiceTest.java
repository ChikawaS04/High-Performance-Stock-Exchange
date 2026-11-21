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
        /** Events below it are gone, as after journal retention (P16-7); 1 means none are. */
        volatile long firstAvailable = 1;

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
            long first = 0;
            for (ExecutionEvent e : events) {
                if (e.seq < firstAvailable) continue;
                if (first == 0) first = e.seq;
                last = e.seq;
                if (e.seq < fromSeq) continue;
                if (!visitor.onEvent(e)) return new Result(last, count, true, first);
                count++;
            }
            return new Result(last, count, false, first);
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

    /** P16-7, R5: events before the retained history are gone; the rest is resent and the frames are unchanged. */
    @Test
    void aRequestBelowTheRetainedHistoryResendsFromTheEarliestSeq() {
        history.firstAvailable = 4;
        EmbeddedChannel a = connect();
        resend(a, "1");
        awaitFinished(1);
        List<String> expected = execs(4, EVENTS);
        expected.add(complete(1, EVENTS, EVENTS - 3));   // RESEND_COMPLETE as before: the request's fromSeq
        assertEquals(expected, drain(a));
        for (int i = 2; i <= 5; i++) {   // repeats are served alike; the WARN is rate-limited (ResendRangeLogTest)
            resend(a, "1");
            awaitFinished(i);
            assertEquals(expected, drain(a));
        }
    }

    /** P16-5 carry: a client already caught up (fromSeq = last + 1) gets an empty completion. */
    @Test
    void aCaughtUpRequestGetsAnEmptyCompletion() {
        EmbeddedChannel a = connect();
        resend(a, Integer.toString(EVENTS + 1));
        awaitFinished(1);
        assertEquals(List.of(complete(EVENTS + 1, EVENTS, 0)), drain(a));
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

    // --- public limits (P16-9) ---

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    /** A service with public limits on a test clock: {@code pool} public slots, a 60 s interval, {@code depth} events. */
    private ResendService limited(int pool, long depth) {
        service.close();
        service = new ResendService(history, frames::toJson,
                new ResendService.Limits(pool, 60_000_000_000L, depth), nanos::get);
        return service;
    }

    /** A channel admitted as public for client {@code key} (or exempt), as HttpRouter marks it at the upgrade. */
    private EmbeddedChannel connect(ChannelAccess.ClientState client, boolean exempt) {
        EmbeddedChannel ch = connect();
        ChannelAccess access = new ChannelAccess();
        access.admitted = true;
        access.exempt = exempt;
        access.client = client;
        ch.attr(ChannelAccess.KEY).set(access);
        return ch;
    }

    private static ChannelAccess.ClientState client(int n) {
        return new ChannelAccess.ClientState(ClientKey.of(new byte[] {(byte) 203, 0, 113, (byte) n}), 0L);
    }

    @Test
    void publicRequestsHaveTheirOwnPoolAndAFullPublicPoolAnswersBusy() {
        limited(1, 1_000);
        history.hold = new CountDownLatch(1);
        EmbeddedChannel first = connect(client(1), false);
        resend(first, "9");
        await(() -> history.lastThroughSeq.get() >= 0, "the public resend started");
        EmbeddedChannel second = connect(client(2), false);
        resend(second, "9");
        assertEquals(List.of(rejected(9, "BUSY")), drain(second), "the one public slot is taken");
        assertEquals(1, service.refusedPublicPool());

        history.hold.countDown();
        awaitFinished(1);
        assertEquals(List.of(frames.toJson(OutboundRecordings.fill(9)), frames.toJson(OutboundRecordings.fill(10)),
                complete(9, EVENTS, 2)), drain(first));
    }

    @Test
    void aClientGetsOnePublicResendPerIntervalOnAnyOfItsChannels() {
        limited(1, 1_000);
        ChannelAccess.ClientState one = client(1);
        EmbeddedChannel a = connect(one, false);
        resend(a, "10");
        awaitFinished(1);
        drain(a);

        EmbeddedChannel b = connect(one, false);   // the same client on another connection
        resend(b, "10");
        assertEquals(List.of(rejected(10, "BUSY")), drain(b));
        assertEquals(1, service.refusedPublicInterval());

        nanos.addAndGet(60_000_000_000L);           // the interval has passed
        resend(b, "10");
        awaitFinished(2);
        assertEquals(List.of(frames.toJson(OutboundRecordings.fill(10)), complete(10, EVENTS, 1)), drain(b));
    }

    @Test
    void publicResendsCannotStarveAnExemptOne() {
        limited(1, 1_000);
        history.hold = new CountDownLatch(1);
        EmbeddedChannel publicChannel = connect(client(1), false);
        resend(publicChannel, "9");
        await(() -> history.lastThroughSeq.get() >= 0, "the public resend is being served");
        // Public clients flood: every further public request is refused, none takes an exempt slot.
        for (int i = 2; i <= 10; i++) {
            EmbeddedChannel p = connect(client(i), false);
            resend(p, "1");
            assertEquals(List.of(rejected(1, "BUSY")), drain(p));
        }
        // Every exempt slot is still free: MAX_PENDING bots are all accepted.
        List<EmbeddedChannel> bots = new ArrayList<>();
        for (int i = 0; i < ResendService.MAX_PENDING; i++) {
            EmbeddedChannel bot = connect(null, true);
            resend(bot, "10");
            assertTrue(drain(bot).isEmpty(), "the exempt request was queued, not refused");
            bots.add(bot);
        }
        history.hold.countDown();
        awaitFinished(1 + ResendService.MAX_PENDING);
        for (EmbeddedChannel bot : bots) {
            assertEquals(List.of(frames.toJson(OutboundRecordings.fill(10)), complete(10, EVENTS, 1)), drain(bot));
        }
        assertEquals(0, service.refusedBusy());
    }

    @Test
    void queuedExemptRequestsAreServedBeforeQueuedPublicOnes() {
        limited(1, 1_000);
        history.hold = new CountDownLatch(1);
        EmbeddedChannel blocker = connect(null, true);
        resend(blocker, "10");                         // occupies the resend thread
        await(() -> history.lastThroughSeq.get() >= 0, "the first resend is being served");
        List<Long> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        EmbeddedChannel publicChannel = connect(client(1), false);
        EmbeddedChannel exempt = connect(null, true);
        publicChannel.pipeline().addFirst(recorder(order, 1L));
        exempt.pipeline().addFirst(recorder(order, 2L));
        resend(publicChannel, "10");                   // queued first
        resend(exempt, "10");                          // queued second
        history.hold.countDown();
        awaitFinished(3);
        assertEquals(List.of(2L, 1L), order, "the exempt request went first");
    }

    private static ChannelOutboundHandlerAdapter recorder(List<Long> order, long tag) {
        return new ChannelOutboundHandlerAdapter() {
            boolean seen;

            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (!seen) {
                    seen = true;
                    order.add(tag);
                }
                ctx.write(msg, promise);
            }
        };
    }

    @Test
    void aPublicRequestReachesBackAtMostTheConfiguredDepth() {
        limited(1, 3);
        EmbeddedChannel a = connect(client(1), false);
        resend(a, "1");
        awaitFinished(1);
        List<String> expected = execs(EVENTS - 2, EVENTS);       // the last 3 events only
        expected.add(complete(1, EVENTS, 3));                    // the client's own fromSeq, as for R5
        assertEquals(expected, drain(a));

        EmbeddedChannel bot = connect(null, true);               // an exempt request is not floored
        resend(bot, "1");
        awaitFinished(2);
        List<String> all = execs(1, EVENTS);
        all.add(complete(1, EVENTS, EVENTS));
        assertEquals(all, drain(bot));
    }

    @Test
    void aZeroPublicPoolRefusesEveryPublicRequest() {
        limited(0, 1_000);
        EmbeddedChannel a = connect(client(1), false);
        resend(a, "10");
        assertEquals(List.of(rejected(10, "BUSY")), drain(a));
        assertEquals(1, service.refusedPublicPool());
        assertEquals(0, service.finishedRequests());
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
