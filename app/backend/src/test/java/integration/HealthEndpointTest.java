package integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import market.MarketDataService;
import net.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import publisher.TradeLogger;
import publisher.WebSocketPublisher;
import util.EpochNanoClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P15-7 over real sockets: a {@link JournaledVenue} behind a {@link WebSocketServer} with the
 * pipeline as its {@code VenueStatus}, wired as Main wires it, queried with
 * {@link java.net.http.HttpClient} while a WebSocket client on the same port sends orders.
 */
class HealthEndpointTest {

    private static final long TIMEOUT_MS = 5_000;
    private static final long PX = 1_500_000L;

    @TempDir
    Path root;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private JournaledVenue venue;
    private WebSocketServer server;
    private WebSocket ws;

    @BeforeEach
    void setUp() throws Exception {
        venue = JournaledVenue.create(root);
        server = new WebSocketServer(0, venue.gateway(), new EpochNanoClock(), null, venue.pipeline());
        WebSocketPublisher publisher = new WebSocketPublisher(server.getChannelGroup(), server.getBookFrameCache());
        venue.outbound().handleEventsWith(publisher.executionHandler(), new TradeLogger());
        venue.snapshots().handleEventsWith(publisher.snapshotHandler(), new MarketDataService());
        venue.start();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ws != null) ws.abort();
        if (server != null) server.stop();
        if (venue != null) venue.close();
    }

    @Test
    void healthReportsAFreshVenueThenMovesAfterOrders() throws Exception {
        HttpResponse<String> fresh = get("/health");
        assertEquals(200, fresh.statusCode());
        assertEquals("application/json", fresh.headers().firstValue("content-type").orElse(""));
        JsonNode before = mapper.readTree(fresh.body());
        assertTrue(before.path("running").asBoolean());
        assertTrue(before.get("session").isNull());
        assertEquals(0, before.path("appliedInboundSeq").asLong());
        assertEquals(0, before.path("journaledInboundSeq").asLong());
        assertEquals(0, before.path("recordedOutboundSeq").asLong());
        assertEquals(0, before.path("publishedOutboundSeq").asLong());

        // The upgrade passes through the router; the client keeps the highest EXEC seq it sees.
        AtomicLong lastExecSeq = new AtomicLong();
        ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.boundPort() + "/ws"), new ExecSeqListener(lastExecSeq))
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertTrue(await(() -> server.getChannelGroup().size() == 1), "client registered");

        // A resting bid, then a crossing sell: three commands, with an accept and a fill among the EXECs.
        send("{\"type\":\"NEW\",\"clOrdId\":1,\"side\":\"BUY\",\"price\":" + PX + ",\"qty\":10,\"symbol\":\"ASML\"}");
        send("{\"type\":\"NEW\",\"clOrdId\":2,\"side\":\"SELL\",\"price\":" + PX + ",\"qty\":4,\"symbol\":\"ASML\"}");
        send("{\"type\":\"NEW\",\"clOrdId\":3,\"side\":\"SELL\",\"price\":" + (PX + 100) + ",\"qty\":5,\"symbol\":\"ASML\"}");

        // Every counter can trail its upstream briefly, so wait for each one before comparing.
        JsonNode[] health = new JsonNode[1];
        assertTrue(await(() -> {
            health[0] = health();
            long published = health[0].path("publishedOutboundSeq").asLong();
            return health[0].path("appliedInboundSeq").asLong() == 3
                    && health[0].path("journaledInboundSeq").asLong() == 3
                    && published > 0
                    && health[0].path("recordedOutboundSeq").asLong() == published
                    && lastExecSeq.get() == published;
        }), () -> "health did not settle: " + health[0] + ", client's last EXEC seq " + lastExecSeq.get());

        JsonNode after = health[0];
        assertTrue(after.path("running").asBoolean());
        assertEquals(venue.pipeline().publishedSeq(), after.path("publishedOutboundSeq").asLong());
        assertTrue(after.path("publishedOutboundSeq").asLong() >= 3, "at least one EXEC per command");
    }

    @Test
    void unknownPathApiAndWrongMethodOverARealSocket() throws Exception {
        assertEquals(404, get("/nope").statusCode());
        assertEquals(404, get("/api").statusCode());
        assertEquals(200, get("/health?probe=render").statusCode());

        HttpResponse<String> post = http.send(HttpRequest.newBuilder(uri("/health"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
        assertEquals("GET", post.headers().firstValue("allow").orElse(""));
    }

    @Test
    void twoRequestsOnOneKeepAliveConnection() throws Exception {
        try (Socket socket = new Socket("localhost", server.boundPort())) {
            socket.setSoTimeout((int) TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            for (int i = 0; i < 2; i++) {
                out.write(("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                RawResponse response = RawResponse.read(in);
                assertTrue(response.statusLine.startsWith("HTTP/1.1 200"), response.statusLine);
                assertFalse(response.headers.contains("connection: close"), "kept alive");
                assertTrue(mapper.readTree(response.body).path("running").asBoolean());
            }
            assertFalse(socket.isClosed());
        }
    }

    // --- helpers ---

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.boundPort() + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode health() {
        try {
            return mapper.readTree(get("/health").body());
        } catch (Exception e) {
            throw new AssertionError("GET /health failed", e);
        }
    }

    private void send(String json) throws Exception {
        ws.sendText(json, true).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Bounded poll; 10 ms parks between checks. */
    private static boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
        }
        return condition.getAsBoolean();
    }

    /** Collects text frames and records the highest EXEC seq seen. */
    private final class ExecSeqListener implements WebSocket.Listener {
        private final AtomicLong lastExecSeq;
        private final StringBuilder partial = new StringBuilder();

        ExecSeqListener(AtomicLong lastExecSeq) {
            this.lastExecSeq = lastExecSeq;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    JsonNode frame = mapper.readTree(partial.toString());
                    if ("EXEC".equals(frame.path("type").asText())) {
                        lastExecSeq.accumulateAndGet(frame.path("seq").asLong(), Math::max);
                    }
                } catch (IOException e) {
                    throw new AssertionError("unparseable frame: " + partial, e);
                } finally {
                    partial.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }
    }

    /** One HTTP/1.1 response read off a raw socket, framed by Content-Length. */
    private record RawResponse(String statusLine, String headers, byte[] body) {

        static RawResponse read(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (matched < 4) {
                int b = in.read();
                if (b < 0) throw new IOException("connection closed mid-headers");
                head.write(b);
                matched = (b == end[matched]) ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            String text = head.toString(StandardCharsets.US_ASCII);
            String statusLine = text.substring(0, text.indexOf("\r\n"));
            String headers = text.toLowerCase(Locale.ROOT);
            int at = headers.indexOf("content-length:");
            if (at < 0) throw new IOException("no content-length: " + text);
            int length = Integer.parseInt(headers.substring(at + 15, headers.indexOf("\r\n", at)).trim());
            byte[] body = in.readNBytes(length);
            if (body.length != length) throw new IOException("short body");
            return new RawResponse(statusLine, headers, body);
        }
    }
}
