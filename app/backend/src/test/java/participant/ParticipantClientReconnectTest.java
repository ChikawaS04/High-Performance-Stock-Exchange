package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;

/**
 * The bots' connection over real sockets against {@link StubVenue} (P16-5): reconnect with back-off
 * after the venue drops every connection, RESEND from the last seq + 1, the replay applied once,
 * trading resumed; and the SIGTERM stop: cancels sent, confirmations awaited, bounded.
 */
class ParticipantClientReconnectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StubVenue venue;
    private final List<ParticipantClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        venue = new StubVenue();
    }

    @AfterEach
    void tearDown() throws Exception {
        ParticipantClient.stopAll(clients, 0, 200);
        venue.close();
    }

    private ParticipantClient client(Strategy s) {
        ParticipantClient c = new ParticipantClient(venue.ws(), venue.health(), "ASML", s,
                new RiskGate(s.owner(), new RiskLimits(OPEN, 20, 100_000), 10_000, 100), 20, null, MAPPER);
        clients.add(c);
        return c;
    }

    private static boolean eventually(BooleanSupplier condition, long seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        return condition.getAsBoolean();
    }

    private static String accepted(long seq, long id) {
        return "{\"type\":\"EXEC\",\"seq\":" + seq + ",\"execType\":\"ORDER_ACCEPTED\",\"orderId\":" + id
                + ",\"price\":" + OPEN + ",\"remainingQuantity\":100,\"side\":\"BUY\"}";
    }

    private static String partialFill(long seq, long id, long qty, long left) {
        return "{\"type\":\"EXEC\",\"seq\":" + seq + ",\"execType\":\"ORDER_PARTIALLY_FILLED\",\"orderId\":7,"
                + "\"price\":" + OPEN + ",\"filledQuantity\":" + qty + ",\"remainingQuantity\":0,"
                + "\"aggressorOrderId\":7,\"passiveOrderId\":" + id + ",\"aggressorSide\":\"SELL\"}";
    }

    private static String cancelled(long seq, long id) {
        return "{\"type\":\"EXEC\",\"seq\":" + seq + ",\"execType\":\"ORDER_CANCELLED\",\"orderId\":" + id + "}";
    }

    @Test
    void afterTheVenueDropsTheConnectionTheBotReconnectsResendsAndResumes() throws Exception {
        RestingStrategy s = new RestingStrategy(2, 100, OPEN);
        ParticipantClient c = client(s);
        c.start(HttpClient.newHttpClient());

        String first = venue.await("\"NEW\"", 10);
        assertNotNull(first, "the bot trades once connected");
        long id = MAPPER.readTree(first).path("clOrdId").asLong();
        venue.send(accepted(1, id));
        venue.send("{\"type\":\"EXEC\",\"seq\":2,\"execType\":\"ORDER_REJECTED\",\"orderId\":99}");
        String second = venue.await("\"NEW\"", 10);
        assertNotNull(second);
        long id2 = MAPPER.readTree(second).path("clOrdId").asLong();
        venue.send(accepted(3, id2));
        assertTrue(eventually(() -> c.status().contains("lastSeq=3"), 10), c.status());

        // The venue restarts: every connection drops and /health fails twice.
        venue.healthFailuresLeft.set(2);
        venue.publishedSeq.set(5);
        int healthBefore = venue.healthCalls.get();
        venue.dropAll();

        // The replay and the live stream interleave: send the replay only once the RESEND arrives.
        venue.onText = text -> {
            if (text.contains("\"RESEND\"")) {
                venue.send(partialFill(5, id, 40, 0));          // live, past the gap
                venue.send(partialFill(4, id, 30, 0));          // replay
                venue.send(partialFill(5, id, 40, 0));          // replay, a duplicate of the live one
                venue.send("{\"type\":\"RESEND_COMPLETE\",\"fromSeq\":4,\"toSeq\":5,\"count\":2}");
            }
        };
        String resend = venue.await("\"RESEND\"", 15);
        assertNotNull(resend, "the bot reconnects and asks for what it missed");
        JsonNode r = MAPPER.readTree(resend);
        assertEquals(4, r.path("fromSeq").asLong(), "from its last applied seq + 1");
        assertTrue(venue.healthCalls.get() - healthBefore >= 3, "two failed attempts, then one that worked");
        assertEquals(2, venue.connections.get());

        assertTrue(eventually(() -> c.status().contains("phase=LIVE") && c.status().contains("lastSeq=5"), 10),
                c.status());
        assertTrue(c.status().contains("reconnects=1"), c.status());
        assertTrue(eventually(() -> s.own.position() == 70, 5), "30 + 40, the duplicate applied once: " + s.own.position());
        assertEquals(1, s.execs.stream().filter(e -> e.filledQuantity() == 40).count());
    }

    @Test
    void stopAllCancelsRestingOrdersAndWaitsForTheVenue() throws Exception {
        RestingStrategy s = new RestingStrategy(1, 100, OPEN);
        ParticipantClient c = client(s);
        c.start(HttpClient.newHttpClient());
        String nw = venue.await("\"NEW\"", 10);
        assertNotNull(nw);
        long id = MAPPER.readTree(nw).path("clOrdId").asLong();
        venue.send(accepted(1, id));
        assertTrue(eventually(s::hasResting, 5));
        assertTrue(eventually(() -> c.status().contains("lastSeq=1"), 5));

        venue.onText = text -> {
            if (text.contains("\"CANCEL\"")) venue.send(cancelled(2, id));
        };
        long t0 = System.nanoTime();
        ParticipantClient.stopAll(List.of(c), ParticipantSimMain.STOP_ACK_WAIT_MILLIS, ParticipantSimMain.STOP_CLOSE_MILLIS);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertFalse(s.hasResting(), "the venue confirmed the cancel");
        assertTrue(ms < 2_000, "no need to wait out the deadline: " + ms + " ms");
    }

    @Test
    void stopAllGivesUpAtTheDeadlineAndFinishesWellInsideFiveSeconds() throws Exception {
        RestingStrategy s = new RestingStrategy(1, 100, OPEN);
        ParticipantClient c = client(s);
        c.start(HttpClient.newHttpClient());
        String nw = venue.await("\"NEW\"", 10);
        assertNotNull(nw);
        venue.send(accepted(1, MAPPER.readTree(nw).path("clOrdId").asLong()));
        assertTrue(eventually(s::hasResting, 5));
        assertTrue(eventually(() -> c.status().contains("lastSeq=1"), 5));

        long t0 = System.nanoTime();
        ParticipantClient.stopAll(List.of(c), ParticipantSimMain.STOP_ACK_WAIT_MILLIS, ParticipantSimMain.STOP_CLOSE_MILLIS);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms >= 2_900 && ms < 5_000, "bounded at about 3.5 s: " + ms + " ms");
        assertTrue(venue.await("\"CANCEL\"", 1) != null, "the cancel was sent");
        assertTrue(venue.received.stream().filter(f -> f.contains("\"CANCEL\"")).count() >= 1,
                "and re-sent while waiting");
    }

    @Test
    void aBotThatCannotReadHealthNeverTrades() throws Exception {
        venue.healthFailuresLeft.set(Integer.MAX_VALUE);
        RestingStrategy s = new RestingStrategy(1, 100, OPEN);
        ParticipantClient c = client(s);
        c.start(HttpClient.newHttpClient());
        assertTrue(eventually(() -> venue.healthCalls.get() >= 3, 10), "it keeps trying with back-off");
        assertEquals(0, venue.connections.get(), "no connection without /health");
        assertTrue(venue.received.isEmpty());
    }
}
