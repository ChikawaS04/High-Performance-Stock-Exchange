package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.news;

/**
 * The bots' back-off after the venue's seq-less refusals (P16-9, P16-8 finding 2): through the client's core
 * (which frames count, and that the bot's own risk gate refusals do not), and over real sockets against a stub
 * venue whose {@code /health} shows the disk in STOP and then out of it.
 */
class ParticipantClientBackoffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StubVenue venue;
    private final List<ParticipantClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        ParticipantClient.stopAll(clients, 0, 200);
        if (venue != null) venue.close();
    }

    /** What the venue writes for a NEW refused by the hard stop (publisher/ExecutionFrames.refusalJson). */
    private static String refusalText(long orderId) {
        return "{\"type\":\"EXEC\",\"execType\":\"ORDER_REJECTED\",\"orderId\":" + orderId
                + ",\"tradeId\":-1,\"price\":-1,\"filledQuantity\":-1,\"remainingQuantity\":-1,\"aggressorOrderId\":-1,"
                + "\"passiveOrderId\":-1,\"aggressorSide\":null,\"aggressorParticipantId\":-1,\"passiveParticipantId\":-1,"
                + "\"timestamp\":1700000000123456789}";
    }

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    private static JsonNode accepted(long seq, long id) throws Exception {
        return json("{\"type\":\"EXEC\",\"seq\":" + seq + ",\"execType\":\"ORDER_ACCEPTED\",\"orderId\":" + id
                + ",\"price\":" + OPEN + ",\"remainingQuantity\":100,\"side\":\"BUY\"}");
    }

    private static ParticipantClient.Core liveCore(RestingStrategy s, RiskGate gate) {
        ParticipantClient.Core core = new ParticipantClient.Core(s, gate, null, 30_000);
        core.onHealth(true, 0);
        core.onConnected();
        return core;
    }

    @Test
    void venueRefusalsOfOwnOrdersHoldTheTickAndAnotherBotsAcceptDoesNotResumeIt() throws Exception {
        RestingStrategy s = new RestingStrategy(100, 100, OPEN);
        ParticipantClient.Core core = liveCore(s, new RiskGate(Bots.Owner.NOISE, new RiskLimits(OPEN, 20, 100_000),
                1_000, 100));
        long t = 1_000;
        for (int i = 0; i < 3; i++) {
            List<Action> sent = news(core.onTick(t += 20));
            assertEquals(1, sent.size());
            core.onFrame(json(refusalText(sent.get(0).clOrdId())));
        }
        assertTrue(core.onTick(t += 20).isEmpty(), "paused after three refusals");
        assertEquals(3, core.backoff.refusals());

        long makersId = Bots.Owner.MAKER.base() + 1;
        core.onFrame(accepted(1, makersId));            // broadcast: the maker's order, not this bot's
        assertTrue(core.onTick(t += 20).isEmpty(), "another bot's accept does not resume this one");

        // The first own accept (an order that was in flight) resets the back-off.
        long ownId = s.own.ids().next();
        core.onFrame(accepted(2, ownId));
        assertEquals(1, news(core.onTick(t += 20)).size(), "trading again");
    }

    /** Amendment 4: the risk gate's local synthetic rejects never reach the back-off. */
    @Test
    void theBotsOwnRiskGateRefusalsNeverCountAsVenueRefusals() throws Exception {
        RestingStrategy s = new RestingStrategy(100, 100, OPEN);
        // At most one open order: every NEW after the first is refused locally, before it is sent.
        RiskGate gate = new RiskGate(Bots.Owner.NOISE, new RiskLimits(OPEN, 20, 100_000), 1_000, 1);
        ParticipantClient.Core core = liveCore(s, gate);
        long t = 1_000;
        assertEquals(1, news(core.onTick(t += 20)).size());
        for (int i = 0; i < 20; i++) {
            assertTrue(news(core.onTick(t += 20)).isEmpty(), "refused by the bot's own gate");
        }
        assertEquals(20, gate.refused(), "the gate refused them");
        assertEquals(0, core.backoff.refusals(), "none counted as a venue refusal");
        assertFalse(core.backoff.paused());
        // The synthetic reject went to the strategy, which forgot each order; the tick is not held.
        assertTrue(core.takeRefusals() > 0);
    }

    @Test
    void theBotPausesWhileHealthShowsStopAndResumesWhenTheDiskIsOutOfStop() throws Exception {
        venue = new StubVenue();
        venue.diskState = "STOP";
        AtomicInteger news = new AtomicInteger();
        venue.onText = text -> {
            try {
                JsonNode n = MAPPER.readTree(text);
                if ("NEW".equals(n.path("type").asText())) {
                    news.incrementAndGet();
                    if ("STOP".equals(venue.diskState)) venue.send(refusalText(n.path("clOrdId").asLong()));
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        RestingStrategy s = new RestingStrategy(10_000, 100, OPEN);
        ParticipantClient c = new ParticipantClient(venue.ws(), venue.health(), "ASML", s,
                new RiskGate(s.owner(), new RiskLimits(OPEN, 20, 1_000_000), 10_000, 10_000), 20, null, MAPPER,
                1_000);   // the smallest cap: one-second pauses keep the test short
        clients.add(c);
        c.start(HttpClient.newHttpClient());

        assertTrue(eventually(() -> news.get() >= 3, 5), "the bot sent NEWs");
        int healthBefore = venue.healthCalls.get();
        // Paused: the NEW count settles (those already in flight land) and stays put across probes.
        assertTrue(eventually(() -> venue.healthCalls.get() >= healthBefore + 2, 6), "it probed /health");
        int paused = news.get();
        assertTrue(eventually(() -> venue.healthCalls.get() >= healthBefore + 4, 6), "more probes");
        assertEquals(paused, news.get(), "no NEW while the disk is in STOP");
        assertTrue(paused < 20, "it stopped soon after three refusals: " + paused);

        venue.diskState = "LOW";   // out of STOP: NEW is accepted again (not yet OK)
        assertTrue(eventually(() -> news.get() > paused + 5, 6), "trading resumed");
        assertTrue(c.status().contains("backoff=none"), c.status());
    }

    private static boolean eventually(BooleanSupplier condition, long seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        return condition.getAsBoolean();
    }
}
