package participant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import model.Prices;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bots' open: /market/ignition first, BOTS_OPEN_PX only on a 404 (P16-5, amendment 1). */
class OpenPriceSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long VENUE_OPEN = 14_050_000L;    // $1,405.00
    private static final long FALLBACK = 14_000_000L;      // $1,400.00

    /** Answers from a script, then the last one forever; counts calls. */
    private static final class Script implements OpenPriceSource.Fetcher {
        final Deque<OpenPriceSource.Answer> answers = new ArrayDeque<>();
        OpenPriceSource.Answer last;
        int calls;

        Script then(int times, OpenPriceSource.Answer a) {
            for (int i = 0; i < times; i++) answers.add(a);
            return this;
        }

        @Override
        public OpenPriceSource.Answer fetch() {
            calls++;
            if (!answers.isEmpty()) last = answers.poll();
            return last;
        }
    }

    /** A sleeper that only records and advances a fake clock. */
    private static final class FakeTime implements OpenPriceSource.Sleeper {
        long now;
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }
    }

    private static OpenPriceSource.Answer ok(long units) {
        return OpenPriceSource.Answer.of(200, "{\"symbol\":\"ASML\",\"openPx\":" + units + ",\"date\":20261118}", -1);
    }

    private static final OpenPriceSource.Answer NOT_FOUND = OpenPriceSource.Answer.of(404, "", -1);

    private static OpenPriceSource.Answer unavailable(long retryAfter) {
        return OpenPriceSource.Answer.of(503, "{\"error\":\"UPSTREAM_UNAVAILABLE\"}", retryAfter);
    }

    private static long resolve(Script s, long fallback, FakeTime t) throws Exception {
        return OpenPriceSource.resolve(s, fallback, MAPPER, t, () -> t.now);
    }

    @Test
    void a200GivesTheVenuesOpen() throws Exception {
        FakeTime t = new FakeTime();
        assertEquals(VENUE_OPEN, resolve(new Script().then(1, ok(VENUE_OPEN)), FALLBACK, t));
        assertTrue(t.sleeps.isEmpty());
    }

    @Test
    void a503Then200NeverUsesTheFallbackAndHonoursRetryAfter() throws Exception {
        FakeTime t = new FakeTime();
        Script s = new Script().then(2, unavailable(7)).then(1, ok(VENUE_OPEN));
        assertEquals(VENUE_OPEN, resolve(s, FALLBACK, t));
        assertEquals(List.of(7_000L, 7_000L), t.sleeps);
    }

    @Test
    void aLong503NeverUsesTheFallback() throws Exception {
        FakeTime t = new FakeTime();
        Script s = new Script().then(5_000, unavailable(60)).then(1, ok(VENUE_OPEN));
        assertEquals(VENUE_OPEN, resolve(s, FALLBACK, t));
        assertEquals(5_001, s.calls);
        assertTrue(t.now >= 5_000L * 60_000L, "waited the whole time, about 3.5 days of Retry-After");
    }

    @Test
    void retryAfterIsClampedToOneToSixtySeconds() throws Exception {
        FakeTime t = new FakeTime();
        Script s = new Script().then(1, unavailable(0)).then(1, unavailable(3_600)).then(1, unavailable(-1))
                .then(1, ok(VENUE_OPEN));
        resolve(s, FALLBACK, t);
        assertEquals(List.of(OpenPriceSource.RETRY_MILLIS, 60_000L, OpenPriceSource.RETRY_MILLIS), t.sleeps);
    }

    @Test
    void a404UsesTheFallback() throws Exception {
        FakeTime t = new FakeTime();
        assertEquals(FALLBACK, resolve(new Script().then(1, NOT_FOUND), FALLBACK, t));
    }

    @Test
    void a404WithTheFallbackUnsetStopsTheStart() {
        FakeTime t = new FakeTime();
        OpenPriceSource.NoOpenPriceException e = assertThrows(OpenPriceSource.NoOpenPriceException.class,
                () -> resolve(new Script().then(1, NOT_FOUND), Prices.NA, t));
        assertTrue(e.getMessage().contains("BOTS_OPEN_PX"));
    }

    @Test
    void otherFailuresAreRetriedWithoutTheFallback() throws Exception {
        FakeTime t = new FakeTime();
        Script s = new Script()
                .then(1, OpenPriceSource.Answer.failed("ConnectException"))
                .then(1, OpenPriceSource.Answer.of(500, "boom", -1))
                .then(1, OpenPriceSource.Answer.of(200, "{\"openPx\":\"x\"}", -1))
                .then(1, OpenPriceSource.Answer.of(200, "{\"openPx\":14050050}", -1))   // off the tick
                .then(1, OpenPriceSource.Answer.of(200, "not json", -1))
                .then(1, ok(VENUE_OPEN));
        assertEquals(VENUE_OPEN, resolve(s, FALLBACK, t));
        assertEquals(5, t.sleeps.size());
        assertTrue(t.sleeps.stream().allMatch(ms -> ms == OpenPriceSource.RETRY_MILLIS));
    }

    @Test
    void readOnceGivesTheOpenOrNa() throws Exception {
        assertEquals(VENUE_OPEN, OpenPriceSource.readOnce(new Script().then(1, ok(VENUE_OPEN)), MAPPER));
        assertEquals(Prices.NA, OpenPriceSource.readOnce(new Script().then(1, unavailable(5)), MAPPER));
        assertEquals(Prices.NA, OpenPriceSource.readOnce(new Script().then(1, NOT_FOUND), MAPPER));
        assertEquals(Prices.NA, OpenPriceSource.readOnce(new Script().then(1, OpenPriceSource.Answer.failed("x")), MAPPER));
    }

    @Test
    void parseDollarsIsExactAndNeverRounds() {
        assertEquals(14_050_000L, OpenPriceSource.parseDollars("1405.00", "BOTS_OPEN_PX"));
        assertEquals(14_050_000L, OpenPriceSource.parseDollars("1405", "BOTS_OPEN_PX"));
        assertEquals(14_051_000L, OpenPriceSource.parseDollars(" 1405.1 ", "BOTS_OPEN_PX"));
        assertEquals(14_051_200L, OpenPriceSource.parseDollars("1405.1200", "BOTS_OPEN_PX"));
        for (String bad : List.of("1405.005", "1405.12345", "0", "0.00", "-1", "abc", "1,405", ".5", "1e3", "")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> OpenPriceSource.parseDollars(bad, "BOTS_OPEN_PX"), bad);
            assertTrue(e.getMessage().startsWith("BOTS_OPEN_PX"), e.getMessage());
        }
    }

    @Test
    void theHttpFetcherReadsStatusBodyAndRetryAfter() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/market/ignition", ex -> {
            byte[] body = "{\"error\":\"UPSTREAM_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Retry-After", "12");
            ex.sendResponseHeaders(503, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/market/ignition");
            OpenPriceSource.Answer a = OpenPriceSource.http(HttpClient.newHttpClient(), uri).fetch();
            assertEquals(503, a.status());
            assertEquals(12, a.retryAfterSeconds());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theHttpFetcherTurnsAConnectionFailureIntoAnAnswer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.stop(0);   // nothing listens on the port now
        OpenPriceSource.Answer a = OpenPriceSource.http(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + port + "/market/ignition")).fetch();
        assertEquals(-1, a.status());
        assertTrue(a.failure() != null && !a.failure().isEmpty());
    }
}
