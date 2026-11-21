package integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import market.MarketDataService;
import net.AccessControl;
import net.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import publisher.TradeLogger;
import publisher.WebSocketPublisher;
import session.VenueStartup;
import util.EpochNanoClock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The public access limits over real sockets (P16-9), with a {@link JournaledVenue} behind a {@link WebSocketServer}
 * wired as Main wires it. The test's own connections come from 127.0.0.1, which is configured as the trusted
 * proxy, so each simulated visitor is told apart by its {@code X-Forwarded-For}, exactly as Caddy's connections
 * are in production; the exempt list then cannot contain loopback (the two lists may not overlap).
 */
class PublicLimitsIntegrationTest {

    private static final long TIMEOUT_MS = 10_000;
    private static final long PX = 1_500_000L;
    private static final String LOOPBACK = "127.0.0.1";

    @TempDir
    Path root;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final List<WebSocket> sockets = new ArrayList<>();

    private JournaledVenue venue;
    private WebSocketServer server;

    @AfterEach
    void tearDown() throws Exception {
        for (WebSocket ws : sockets) ws.abort();
        if (server != null) server.stop();
        if (venue != null) venue.close();
    }

    private void start(AccessControl.Limits limits) throws Exception {
        venue = JournaledVenue.create(root);
        gateway.OrderGateway gateway = venue.gateway();
        server = new WebSocketServer(0, gateway, new EpochNanoClock(), null, venue.pipeline(), null, null,
                new AccessControl(limits));
        WebSocketPublisher publisher = new WebSocketPublisher(server.getChannelGroup(), server.getBookFrameCache());
        venue.outbound().handleEventsWith(publisher.executionHandler(), new TradeLogger());
        venue.snapshots().handleEventsWith(publisher.snapshotHandler(), new MarketDataService());
        VenueStartup.start(venue.config(), venue.pipeline(), server, gateway, JournaledVenue.CLOCK);
    }

    /** Defaults, with this process as the trusted proxy and the given exempt list. */
    private static AccessControl.Limits limits(List<String> trusted, List<String> exempt) {
        return AccessControl.Limits.defaults(trusted, exempt);
    }

    // --- tests ---

    @Test
    void aBurstIsTrimmedToTheBucketAndTheDroppedFramesAreNeverJournaled() throws Exception {
        start(limits(List.of(LOOPBACK), List.of()));
        Client a = connect("203.0.113.1");
        for (int i = 1; i <= 100; i++) a.send(newOrder(i, PX - i * 100));   // all at once: 100 frames
        assertTrue(await(() -> dropped(health()) == 70), () -> "dropped: " + health());
        // Exactly the 30 admitted commands were journaled after the open; nothing more arrives.
        assertTrue(await(() -> venue.pipeline().journaledSeq() == JournaledVenue.OPEN_SEQ + 30),
                () -> "journaled " + venue.pipeline().journaledSeq());
        LockSupport.parkNanos(300_000_000L);
        assertEquals(JournaledVenue.OPEN_SEQ + 30, venue.pipeline().journaledSeq(), "dropped frames never journaled");
        assertEquals(70, health().path("access").path("framesDropped").path("channel").asLong());
        assertTrue(a.open(), "one burst does not close");
    }

    @Test
    void sustainedExcessClosesWith1008WhileAnotherClientKeepsTrading() throws Exception {
        start(new AccessControl.Limits(10, 30, 10, 30, 20, 100, 1_000, 200, 10, 64, List.of(LOOPBACK), List.of()));
        Client calm = connect("203.0.113.2");
        Client flood = connect("203.0.113.1");
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int id = 10_000;
        while (flood.open() && System.currentTimeMillis() < deadline) {
            flood.send(newOrder(++id, PX - 100));
            LockSupport.parkNanos(5_000_000L);   // about 200 frames a second
        }
        assertTrue(await(() -> flood.closeCode.get() == 1008), () -> "close code " + flood.closeCode.get());
        // The other client trades normally: its orders are accepted.
        for (int i = 1; i <= 5; i++) calm.send(newOrder(i, PX - 200));
        assertTrue(await(() -> calm.accepted.get() >= 5), () -> "accepted " + calm.accepted.get());
        JsonNode access = health().path("access");
        assertEquals(1, access.path("closed1008").asLong());
        assertTrue(access.path("framesDropped").path("channel").asLong() > 0);
        assertEquals(0, access.path("framesDropped").path("public").asLong(), "the flood never reached the public "
                + "bucket's limit");
    }

    @Test
    void connectionCapsRefuse503AtTheUpgradeAndReleaseOnClose() throws Exception {
        start(new AccessControl.Limits(10, 30, 10, 30, 20, 100, 5_000, 4, 2, 64, List.of(LOOPBACK), List.of()));
        Client one = connect("203.0.113.1");
        connect("203.0.113.1");
        assertEquals(503, refusedStatus("203.0.113.1"), "the per-client cap");
        connect("203.0.113.2");
        connect("203.0.113.3");
        assertEquals(503, refusedStatus("203.0.113.4"), "the venue-wide cap");

        JsonNode access = health().path("access");
        assertEquals(4, access.path("connections").path("public").asInt());
        assertEquals(3, access.path("connections").path("clients").asInt());
        assertEquals(1, access.path("upgradesRefused").path("client").asLong());
        assertEquals(1, access.path("upgradesRefused").path("venue").asLong());

        one.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertTrue(await(() -> health().path("access").path("connections").path("public").asInt() == 3),
                "the closed connection gave its slot back");
        connect("203.0.113.4");   // a new client now fits
        JsonNode after = health().path("access").path("connections");
        assertEquals(4, after.path("public").asInt());
        assertEquals(0, after.path("idleClients").asInt(), "203.0.113.1 still has its other connection");
    }

    @Test
    void forwardedForIsIgnoredFromAnUntrustedSocket() throws Exception {
        // The proxy is somewhere else: this process's connections are keyed by their socket, whatever they claim.
        start(new AccessControl.Limits(10, 30, 10, 30, 20, 100, 5_000, 200, 2, 64, List.of("192.0.2.1"), List.of()));
        connect("203.0.113.1");
        connect("203.0.113.2");
        assertEquals(503, refusedStatus("203.0.113.3"), "one client (127.0.0.1) at its cap of 2");
        assertEquals(1, health().path("access").path("connections").path("clients").asInt());
        assertEquals(0, health().path("access").path("xffFallback").asLong());
    }

    @Test
    void aSpoofedLeftEntryIsIgnoredAndTheRightmostIsTheClient() throws Exception {
        start(new AccessControl.Limits(10, 30, 10, 30, 20, 100, 5_000, 200, 1, 64, List.of(LOOPBACK), List.of()));
        connect("203.0.113.1");
        assertEquals(503, refusedStatus("6.6.6.6, 203.0.113.1"), "still 203.0.113.1, at its cap of 1");
        connect("203.0.113.1, 203.0.113.9");   // the rightmost is a different client
        assertEquals(2, health().path("access").path("connections").path("clients").asInt());
    }

    @Test
    void anExemptSocketPassesTheBotsMeasuredPeakAndIsNotCounted() throws Exception {
        // Loopback exempt (the development default) and no trusted proxy.
        start(limits(List.of(), List.of(LOOPBACK, "::1")));
        Client bot = connect(null);
        long start = System.nanoTime();
        for (int i = 1; i <= 253; i++) bot.send(newOrder(i, PX - (i % 50) * 100));   // F8: 253 in one second
        assertTrue(System.nanoTime() - start < 1_000_000_000L, "sent within a second");
        assertTrue(await(() -> venue.pipeline().journaledSeq() == JournaledVenue.OPEN_SEQ + 253),
                () -> "journaled " + venue.pipeline().journaledSeq());
        JsonNode access = health().path("access");
        assertEquals(0, dropped(health()));
        assertEquals(1, access.path("connections").path("exempt").asInt());
        assertEquals(0, access.path("connections").path("public").asInt());
    }

    @Test
    void anExemptAddressArrivingThroughTheTrustedProxyIsPublic() throws Exception {
        start(limits(List.of(LOOPBACK), List.of("172.30.0.10")));
        Client spoof = connect("172.30.0.10");
        for (int i = 1; i <= 50; i++) spoof.send(newOrder(i, PX - i * 100));
        assertTrue(await(() -> dropped(health()) == 20), () -> "dropped: " + health());
        JsonNode access = health().path("access");
        assertEquals(1, access.path("connections").path("public").asInt());
        assertEquals(0, access.path("connections").path("exempt").asInt());
    }

    // --- helpers ---

    private static String newOrder(long clOrdId, long px) {
        return "{\"type\":\"NEW\",\"clOrdId\":" + clOrdId + ",\"side\":\"BUY\",\"price\":" + px
                + ",\"qty\":10,\"symbol\":\"ASML\"}";
    }

    private static long dropped(JsonNode health) {
        JsonNode d = health.path("access").path("framesDropped");
        return d.path("channel").asLong() + d.path("client").asLong() + d.path("public").asLong();
    }

    private URI wsUri() {
        return URI.create("ws://" + LOOPBACK + ":" + server.boundPort() + "/ws");
    }

    /** Connects with the given X-Forwarded-For (null for none) and waits until the server registered it. */
    private Client connect(String forwardedFor) throws Exception {
        int before = server.getChannelGroup().size();
        Client c = new Client();
        WebSocket.Builder b = http.newWebSocketBuilder();
        if (forwardedFor != null) b.header("X-Forwarded-For", forwardedFor);
        c.ws = b.buildAsync(wsUri(), c).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        sockets.add(c.ws);
        assertTrue(await(() -> server.getChannelGroup().size() > before), "registered");
        return c;
    }

    /** The HTTP status of a refused upgrade. */
    private int refusedStatus(String forwardedFor) throws Exception {
        CompletableFuture<WebSocket> attempt = http.newWebSocketBuilder().header("X-Forwarded-For", forwardedFor)
                .buildAsync(wsUri(), new Client());
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> attempt.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        WebSocketHandshakeException refusal = assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
        return refusal.getResponse().statusCode();
    }

    private JsonNode health() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                            URI.create("http://" + LOOPBACK + ":" + server.boundPort() + "/health"))
                    .GET().timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(r.body());
        } catch (Exception e) {
            throw new AssertionError("GET /health failed", e);
        }
    }

    private static boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            LockSupport.parkNanos(10_000_000L);
        }
        return condition.getAsBoolean();
    }

    /** One visitor: sends text, counts its own accepts, records the close code. */
    private final class Client implements WebSocket.Listener {
        WebSocket ws;
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger closeCode = new AtomicInteger(-1);
        private final StringBuilder partial = new StringBuilder();
        private CompletableFuture<WebSocket> tail;

        boolean open() {
            return closeCode.get() < 0 && !ws.isInputClosed();
        }

        synchronized void send(String json) {
            if (tail == null) tail = CompletableFuture.completedFuture(ws);
            tail = tail.thenCompose(w -> w.sendText(json, true));
            try {
                tail.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // the venue closed the connection: the remaining sends fail, which a flood test expects
                tail = null;
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    JsonNode frame = mapper.readTree(partial.toString());
                    if ("EXEC".equals(frame.path("type").asText())
                            && "ORDER_ACCEPTED".equals(frame.path("execType").asText())
                            && frame.path("orderId").asLong() < 10_000) {
                        accepted.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                } finally {
                    partial.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.set(statusCode);
            return null;
        }
    }
}
