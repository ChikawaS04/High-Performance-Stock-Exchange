package net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import util.ReporterStatus;
import util.VenueStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
    void apiIs404UntilItExists() {
        assertStatus(get("/api"), HttpResponseStatus.NOT_FOUND);
        assertStatus(get("/api/trades?date=20261102"), HttpResponseStatus.NOT_FOUND);
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

        @Override public boolean running() { return running; }
        @Override public long session() { return session; }
        @Override public long appliedSeq() { return applied; }
        @Override public long journaledSeq() { return journaled; }
        @Override public long publishedSeq() { return published; }
        @Override public long recordedSeq() { return recorded; }
        @Override public ReporterStatus reporter() { return reporter; }
    }
}
