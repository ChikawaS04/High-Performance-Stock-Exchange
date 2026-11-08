package net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import http.ApiHandler;
import http.ApiRequest;
import http.ApiResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import util.ClearingStatus;
import util.ReporterStatus;
import util.VenueStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Drives {@link HttpRouter} directly via EmbeddedChannel, with a stub {@link VenueStatus} (P15-7). */
class HttpRouterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StubStatus status = new StubStatus();
    private final List<Object> passedOn = new ArrayList<>();
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = channel(status);
    }

    @AfterEach
    void tearDown() {
        passedOn.forEach(ReferenceCountUtil::release);
        channel.finishAndReleaseAll();
    }

    /** The router, then a tail that keeps whatever the router passes on. */
    private EmbeddedChannel channel(VenueStatus venueStatus) {
        return new EmbeddedChannel(DefaultChannelId.newInstance(),
                new HttpRouter("/ws", venueStatus, mapper),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        passedOn.add(msg);
                    }
                });
    }

    // --- /health ---

    @Test
    void healthReturns200WithTheStatusBody() throws Exception {
        status.applied = 12;
        status.journaled = 13;
        status.recorded = 29;
        status.published = 30;

        FullHttpResponse response = exchange(get("/health"));
        try {
            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals("application/json", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("no-store", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
            byte[] body = bytes(response);
            assertEquals(body.length, response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH));

            JsonNode json = mapper.readTree(body);
            assertTrue(json.path("running").asBoolean());
            assertEquals("CLOSED", json.path("session").asText(), "no session ever opened reads as closed");
            assertTrue(json.get("businessDate").isNull(), "no business date before the first session");
            assertEquals(12, json.path("appliedInboundSeq").asLong());
            assertEquals(13, json.path("journaledInboundSeq").asLong());
            assertEquals(29, json.path("recordedOutboundSeq").asLong());
            assertEquals(30, json.path("publishedOutboundSeq").asLong());
            assertEquals(7, json.size(), "no fields beyond the documented seven");
        } finally {
            response.release();
        }
        assertTrue(passedOn.isEmpty(), "/health is answered, not passed on");
        assertTrue(channel.isOpen(), "HTTP/1.1 keeps the connection by default");
    }

    private static ReporterStatus reporter(String state, long inbound, long outbound) {
        return new ReporterStatus() {
            @Override public String state() { return state; }
            @Override public long inboundSeq() { return inbound; }
            @Override public long outboundSeq() { return outbound; }
        };
    }

    @Test
    void healthReportsTheReporterWhenOneRuns() throws Exception {
        status.recorded = 29;
        status.reporter = reporter("RUNNING", 11, 25);
        FullHttpResponse response = exchange(get("/health"));
        try {
            JsonNode reporter = mapper.readTree(bytes(response)).path("reporter");
            assertEquals("RUNNING", reporter.path("state").asText());
            assertEquals(11, reporter.path("inboundSeq").asLong());
            assertEquals(25, reporter.path("outboundSeq").asLong());
            assertEquals(4, reporter.path("outboundLag").asLong());
        } finally {
            response.release();
        }
    }

    @Test
    void healthReportsClearingWhenItRuns() throws Exception {
        status.clearing = new ClearingStatus() {
            @Override public String state() { return "HELD"; }
            @Override public int lastClearedDate() { return 20261105; }
            @Override public int pendingDate() { return 20261106; }
            @Override public String heldBy() { return "reporter FAILED"; }
        };
        FullHttpResponse response = exchange(get("/health"));
        try {
            assertEquals(HttpResponseStatus.OK, response.status(), "clearing never changes the venue's answer");
            JsonNode clearing = mapper.readTree(bytes(response)).path("clearing");
            assertEquals("HELD", clearing.path("state").asText());
            assertEquals(20261105, clearing.path("lastClearedDate").asInt());
            assertEquals(20261106, clearing.path("pendingDate").asInt());
            assertEquals("reporter FAILED", clearing.path("heldBy").asText());
        } finally {
            response.release();
        }
    }

    @Test
    void clearingWithNothingToReportShowsNullsAndIsAbsentWithoutADatabase() throws Exception {
        FullHttpResponse absent = exchange(get("/health"));
        try {
            assertTrue(mapper.readTree(bytes(absent)).path("clearing").isMissingNode());
        } finally {
            absent.release();
        }
        status.clearing = new ClearingStatus() {
            @Override public String state() { return "STARTING"; }
            @Override public int lastClearedDate() { return -1; }
            @Override public int pendingDate() { return -1; }
            @Override public String heldBy() { return null; }
        };
        FullHttpResponse response = exchange(get("/health"));
        try {
            JsonNode clearing = mapper.readTree(bytes(response)).path("clearing");
            assertEquals("STARTING", clearing.path("state").asText());
            assertTrue(clearing.path("lastClearedDate").isNull());
            assertTrue(clearing.path("pendingDate").isNull());
            assertTrue(clearing.path("heldBy").isNull());
        } finally {
            response.release();
        }
    }

    @Test
    void aFailedReporterLeavesTheVenuesAnswerAt200() throws Exception {
        status.reporter = reporter("FAILED", 1, 1);
        FullHttpResponse response = exchange(get("/health"));
        try {
            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals("FAILED", mapper.readTree(bytes(response)).path("reporter").path("state").asText());
        } finally {
            response.release();
        }
    }

    @Test
    void healthReportsAnOpenSessionAndItsBusinessDate() throws Exception {
        status.session = VenueStatus.sessionWord(true, 20261102, 20261030);
        JsonNode json = healthBody();
        assertEquals("OPEN", json.path("session").asText());
        assertEquals(20261102, json.path("businessDate").asInt());
    }

    @Test
    void healthReportsAClosedSessionWithTheLastClosedDate() throws Exception {
        status.session = VenueStatus.sessionWord(false, -1, 20261102);
        JsonNode json = healthBody();
        assertEquals("CLOSED", json.path("session").asText());
        assertEquals(20261102, json.path("businessDate").asInt());
    }

    private JsonNode healthBody() throws Exception {
        FullHttpResponse response = exchange(get("/health"));
        try {
            assertEquals(HttpResponseStatus.OK, response.status());
            return mapper.readTree(bytes(response));
        } finally {
            response.release();
        }
    }

    @Test
    void queryStringOnHealthStillMatches() {
        assertStatus(get("/health?verbose=1&x"), HttpResponseStatus.OK);
    }

    @Test
    void healthAnswers503WithTheSameBodyWhenNotRunning() throws Exception {
        status.running = false;
        status.applied = 4;
        FullHttpResponse response = exchange(get("/health"));
        try {
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
            JsonNode json = mapper.readTree(bytes(response));
            assertFalse(json.path("running").asBoolean());
            assertEquals(4, json.path("appliedInboundSeq").asLong());
        } finally {
            response.release();
        }
    }

    @Test
    void healthIsNotFoundOnAServerWithNoStatus() {
        channel.finishAndReleaseAll();
        channel = channel(null);
        assertStatus(get("/health"), HttpResponseStatus.NOT_FOUND);
    }

    @Test
    void wrongMethodOnHealthIs405WithAllow() {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.DELETE, HttpMethod.HEAD}) {
            FullHttpResponse response = exchange(request(HttpVersion.HTTP_1_1, method, "/health"));
            try {
                assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status(), method.name());
                assertEquals("GET", response.headers().get(HttpHeaderNames.ALLOW));
                assertEquals("application/json", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
            } finally {
                response.release();
            }
        }
    }

    // --- 404 ---

    @Test
    void unknownPathIs404() {
        FullHttpResponse response = exchange(get("/nope"));
        try {
            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
            assertEquals("application/json", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
            assertEquals(bytes(response).length, response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH));
            assertFalse(new String(bytes(response), StandardCharsets.UTF_8).contains("nope"),
                    "the request path is never echoed");
        } finally {
            response.release();
        }
    }

    @Test
    void apiIs404WithoutAHandler() {
        assertStatus(get("/api"), HttpResponseStatus.NOT_FOUND);
        assertStatus(get("/api/trades?date=20261102"), HttpResponseStatus.NOT_FOUND);
    }

    // --- /api (P15-12) ---

    /** A handler that answers at once when {@link #now} is set, or keeps the callback for the test. */
    private static final class StubApi implements ApiHandler {
        final List<ApiRequest> requests = new ArrayList<>();
        final List<Consumer<ApiResponse>> waiting = new ArrayList<>();
        ApiResponse now;
        boolean closed;

        @Override public boolean isRoute(String path) { return Set.of("/api/trades", "/api/reports/daily").contains(path); }

        @Override public ApiResponse handle(ApiRequest request, Consumer<ApiResponse> done) {
            requests.add(request);
            if (now != null) return now;
            waiting.add(done);
            return null;
        }

        @Override public void close() { closed = true; }
    }

    private static final ApiResponse OK_BODY = ApiResponse.ok("{\"ok\":1}".getBytes(StandardCharsets.UTF_8));

    private EmbeddedChannel apiChannel(StubApi api) {
        channel.finishAndReleaseAll();
        channel = new EmbeddedChannel(DefaultChannelId.newInstance(), new HttpRouter("/ws", status, mapper, api));
        return channel;
    }

    /** Answers the oldest waiting request from another thread, as the executor does, then runs the loop's tasks. */
    private void answerFromAnotherThread(StubApi api, ApiResponse answer) throws InterruptedException {
        Consumer<ApiResponse> done = api.waiting.remove(0);
        Thread t = new Thread(() -> done.accept(answer), "api-test");
        t.start();
        t.join(10_000L);
        assertFalse(t.isAlive());
        channel.runPendingTasks();
    }

    private String body(FullHttpResponse response) {
        return new String(bytes(response), StandardCharsets.UTF_8);
    }

    @Test
    void underApiOnlyTheHandlersRoutesExist() {
        StubApi api = new StubApi();
        apiChannel(api);
        for (String uri : List.of("/api", "/api/", "/api/trades/", "/api/nope?date=20261107", "/apix")) {
            assertStatus(get(uri), HttpResponseStatus.NOT_FOUND);
        }
        assertTrue(api.requests.isEmpty(), "a path that is not a route never reaches the handler");
    }

    @Test
    void anotherMethodOnARouteIs405WithAllowGet() {
        StubApi api = new StubApi();
        apiChannel(api);
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.HEAD, HttpMethod.DELETE)) {
            FullHttpResponse response = exchange(request(HttpVersion.HTTP_1_1, method, "/api/trades?date=20261107"));
            try {
                assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status(), method.name());
                assertEquals("GET", response.headers().get(HttpHeaderNames.ALLOW));
            } finally {
                response.release();
            }
        }
        assertTrue(api.requests.isEmpty());
    }

    @Test
    void theHandlerGetsTheCopiedPathAndParameters() {
        StubApi api = new StubApi();
        api.now = OK_BODY;
        apiChannel(api);
        exchange(get("/api/trades?date=20261107&limit=5&limit=6")).release();
        assertEquals(new ApiRequest("/api/trades", Map.of("date", List.of("20261107"), "limit", List.of("5", "6"))),
                api.requests.get(0));
    }

    @Test
    void anImmediateAnswerKeepsItsStatusBodyAndRetryAfter() {
        StubApi api = new StubApi();
        api.now = ApiResponse.BUSY;
        apiChannel(api);
        FullHttpResponse response = exchange(get("/api/trades?date=20261107"));
        try {
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
            assertEquals("1", response.headers().get(HttpHeaderNames.RETRY_AFTER));
            assertEquals("no-store", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
            assertEquals("application/json", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("{\"error\":\"BUSY\"}", body(response));
        } finally {
            response.release();
        }
        assertTrue(channel.config().isAutoRead());
    }

    @Test
    void aLaterAnswerIsWrittenOnTheLoopAndReadingResumes() throws Exception {
        StubApi api = new StubApi();
        apiChannel(api);
        channel.writeInbound(get("/api/trades?date=20261107"));
        assertNull(channel.readOutbound(), "nothing is written before the executor answers");
        assertFalse(channel.config().isAutoRead(), "the channel is not read while the answer is outstanding");

        answerFromAnotherThread(api, OK_BODY);
        FullHttpResponse response = channel.readOutbound();
        try {
            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals("{\"ok\":1}", body(response));
            assertNull(response.headers().get(HttpHeaderNames.RETRY_AFTER));
            assertEquals(bytes(response).length, response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH));
        } finally {
            response.release();
        }
        assertTrue(channel.config().isAutoRead());
        assertTrue(channel.isOpen());
    }

    @Test
    void pipelinedRequestsAreAnsweredInTheOrderTheyArrived() throws Exception {
        StubApi api = new StubApi();
        apiChannel(api);
        FullHttpRequest health = get("/health");
        FullHttpRequest second = get("/api/reports/daily?date=20261107");
        channel.writeInbound(get("/api/trades?date=20261107"));
        channel.writeInbound(health);
        channel.writeInbound(second);
        assertNull(channel.readOutbound(), "/health waits behind the /api answer");
        assertEquals(1, api.requests.size(), "the second /api request is held, not started");

        answerFromAnotherThread(api, ApiResponse.ok("{\"n\":1}".getBytes(StandardCharsets.UTF_8)));
        FullHttpResponse first = channel.readOutbound();
        FullHttpResponse healthAnswer = channel.readOutbound();
        try {
            assertEquals("{\"n\":1}", body(first));
            assertTrue(body(healthAnswer).contains("\"running\""), "then /health");
        } finally {
            first.release();
            healthAnswer.release();
        }
        assertEquals(0, health.refCnt(), "a held request is released once handled");
        assertNull(channel.readOutbound(), "the second /api answer is still outstanding");
        assertEquals("/api/reports/daily", api.requests.get(1).path());
        assertFalse(channel.config().isAutoRead());

        answerFromAnotherThread(api, ApiResponse.ok("{\"n\":2}".getBytes(StandardCharsets.UTF_8)));
        FullHttpResponse last = channel.readOutbound();
        try {
            assertEquals("{\"n\":2}", body(last));
        } finally {
            last.release();
        }
        assertEquals(0, second.refCnt());
        assertTrue(channel.config().isAutoRead());
    }

    @Test
    void heldRequestsAreReleasedWhenTheChannelCloses() {
        StubApi api = new StubApi();
        apiChannel(api);
        channel.writeInbound(get("/api/trades?date=20261107"));
        FullHttpRequest held = get("/health");
        channel.writeInbound(held);
        assertEquals(1, held.refCnt());
        channel.close();
        assertEquals(0, held.refCnt());
        api.waiting.remove(0).accept(OK_BODY);   // a late answer for a closed channel is dropped
        channel.runPendingTasks();
        assertNull(channel.readOutbound());
    }

    @Test
    void tooManyPipelinedRequestsCloseTheConnection() {
        StubApi api = new StubApi();
        apiChannel(api);
        channel.writeInbound(get("/api/trades?date=20261107"));
        List<FullHttpRequest> held = new ArrayList<>();
        for (int i = 0; i <= HttpRouter.MAX_HELD; i++) {
            FullHttpRequest r = get("/health");
            held.add(r);
            channel.writeInbound(r);
        }
        assertFalse(channel.isOpen(), "request " + (HttpRouter.MAX_HELD + 1) + " behind an answer closes the connection");
        for (FullHttpRequest r : held) {
            assertEquals(0, r.refCnt());
        }
    }

    @Test
    void withoutKeepAliveTheChannelClosesAfterTheAnswer() throws Exception {
        StubApi api = new StubApi();
        apiChannel(api);
        channel.writeInbound(request(HttpVersion.HTTP_1_0, HttpMethod.GET, "/api/trades?date=20261107"));
        answerFromAnotherThread(api, OK_BODY);
        FullHttpResponse response = channel.readOutbound();
        response.release();
        assertFalse(channel.isOpen());
    }

    // --- /ws ---

    @Test
    void exactWebSocketUriPassesThroughRetained() {
        FullHttpRequest upgrade = get("/ws");
        channel.writeInbound(upgrade);

        assertNull(channel.readOutbound(), "the router writes no response for /ws");
        assertEquals(1, passedOn.size());
        assertSame(upgrade, passedOn.get(0), "passed through untouched");
        assertEquals(1, upgrade.refCnt(), "retained across the router's auto-release, for the handshaker");
    }

    @Test
    void webSocketPathWithAQueryIs404AndNotPassedOn() {
        // The protocol handler matches the whole URI exactly, so this never upgraded; it used to hang.
        assertStatus(get("/ws?token=1"), HttpResponseStatus.NOT_FOUND);
        assertTrue(passedOn.isEmpty());
    }

    @Test
    void webSocketFramesPassThroughAfterAnUpgrade() {
        TextWebSocketFrame frame = new TextWebSocketFrame("{\"type\":\"NEW\"}");
        channel.writeInbound(frame);
        assertEquals(List.of(frame), passedOn);
        assertEquals(1, frame.refCnt());
    }

    // --- keep-alive and Connection: close ---

    @Test
    void twoRequestsOnOneKeepAliveConnection() {
        assertStatus(get("/health"), HttpResponseStatus.OK);
        assertTrue(channel.isOpen());
        assertStatus(get("/nope"), HttpResponseStatus.NOT_FOUND);
        assertTrue(channel.isOpen(), "a 404 keeps the connection too");
        assertStatus(get("/health"), HttpResponseStatus.OK);
        assertTrue(channel.isOpen());
    }

    @Test
    void connectionCloseIsAnsweredThenClosed() {
        FullHttpRequest request = get("/health");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        FullHttpResponse response = exchange(request);
        try {
            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
        } finally {
            response.release();
        }
        assertFalse(channel.isOpen());
    }

    @Test
    void http10WithoutKeepAliveIsClosedAndWithItIsKept() {
        FullHttpResponse response = exchange(request(HttpVersion.HTTP_1_0, HttpMethod.GET, "/health"));
        try {
            assertEquals(HttpVersion.HTTP_1_0, response.protocolVersion());
            assertFalse(HttpHeaderValues.KEEP_ALIVE.contentEqualsIgnoreCase(
                    response.headers().get(HttpHeaderNames.CONNECTION, "")));
        } finally {
            response.release();
        }
        assertFalse(channel.isOpen(), "HTTP/1.0 closes by default");

        channel.finishAndReleaseAll();
        channel = channel(status);
        FullHttpRequest keep = request(HttpVersion.HTTP_1_0, HttpMethod.GET, "/health");
        keep.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response = exchange(keep);
        try {
            assertEquals("keep-alive", response.headers().get(HttpHeaderNames.CONNECTION));
        } finally {
            response.release();
        }
        assertTrue(channel.isOpen());
    }

    @Test
    void undecodableRequestIs400AndClosed() {
        FullHttpRequest bad = get("/health");
        bad.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("bad request line")));
        assertStatus(bad, HttpResponseStatus.BAD_REQUEST);
        assertFalse(channel.isOpen());
        assertTrue(passedOn.isEmpty());
    }

    // --- exceptionCaught ---

    @Test
    void connectionResetClosesTheChannelWithoutPropagating() {
        channel.pipeline().fireExceptionCaught(new IOException("Connection reset"));
        assertFalse(channel.isOpen());
        assertDoesNotThrow(() -> channel.checkException(), "handled, not fired to the tail");
    }

    @Test
    void unexpectedExceptionClosesTheChannelWithoutPropagating() {
        channel.pipeline().fireExceptionCaught(new IllegalStateException("boom"));
        assertFalse(channel.isOpen());
        assertDoesNotThrow(() -> channel.checkException(), "handled, not fired to the tail");
    }

    // --- helpers ---

    private static FullHttpRequest get(String uri) {
        return request(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    private static FullHttpRequest request(HttpVersion version, HttpMethod method, String uri) {
        return new DefaultFullHttpRequest(version, method, uri);
    }

    private FullHttpResponse exchange(FullHttpRequest request) {
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response, "the router should answer " + request.uri());
        return response;
    }

    private void assertStatus(FullHttpRequest request, HttpResponseStatus expected) {
        String uri = request.uri();
        FullHttpResponse response = exchange(request);
        try {
            assertEquals(expected, response.status(), uri);
        } finally {
            response.release();
        }
    }

    private static byte[] bytes(FullHttpResponse response) {
        byte[] out = new byte[response.content().readableBytes()];
        response.content().getBytes(response.content().readerIndex(), out);
        return out;
    }

    private static final class StubStatus implements VenueStatus {
        boolean running = true;
        long applied;
        long journaled;
        long published;
        long recorded;
        long session;
        ReporterStatus reporter;
        ClearingStatus clearing;

        @Override public boolean running() { return running; }
        @Override public long session() { return session; }
        @Override public long appliedSeq() { return applied; }
        @Override public long journaledSeq() { return journaled; }
        @Override public long publishedSeq() { return published; }
        @Override public long recordedSeq() { return recorded; }
        @Override public ReporterStatus reporter() { return reporter; }
        @Override public ClearingStatus clearing() { return clearing; }
    }
}
