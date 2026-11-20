package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.cancels;
import static participant.Fixtures.news;

/** The client core's (re)connection decisions, RESEND catch-up and risk gate, with no socket (P16-5). */
class ParticipantClientResyncTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long BASE = Bots.Owner.NOISE.base();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode accepted(long seq, long id, long qty) {
        return json("{\"type\":\"EXEC\",\"seq\":" + seq + ",\"execType\":\"ORDER_ACCEPTED\",\"orderId\":" + id
                + ",\"price\":" + OPEN + ",\"remainingQuantity\":" + qty + ",\"side\":\"BUY\"}");
    }

    /** Our resting bid {@code id} is hit for {@code qty} by an outsider's sell. */
    private static JsonNode passiveFill(long seq, long id, long qty) {
        return json("{\"type\":\"EXEC\",\"seq\":" + seq + ",\"execType\":\"ORDER_FILLED\",\"orderId\":7,\"price\":"
                + OPEN + ",\"filledQuantity\":" + qty + ",\"remainingQuantity\":0,\"aggressorOrderId\":7,"
                + "\"passiveOrderId\":" + id + ",\"aggressorSide\":\"SELL\"}");
    }

    private static JsonNode print(long seq) {
        return json("{\"type\":\"EXEC\",\"seq\":" + seq + ",\"execType\":\"ORDER_FILLED\",\"orderId\":5,\"price\":"
                + OPEN + ",\"filledQuantity\":1,\"remainingQuantity\":0,\"aggressorOrderId\":5,\"passiveOrderId\":6,"
                + "\"aggressorSide\":\"BUY\"}");
    }

    private static JsonNode session(long seq, String state) {
        return json("{\"type\":\"SESSION\",\"seq\":" + seq + ",\"state\":\"" + state + "\",\"businessDate\":20261118}");
    }

    private static ParticipantClient.Core core(RestingStrategy s) {
        return new ParticipantClient.Core(s, new RiskGate(Bots.Owner.NOISE, new RiskLimits(OPEN, 20, 1_000), 500, 10), null);
    }

    /** A core that connected fresh, open, and has applied seq 1..{@code lastSeq}. */
    private static ParticipantClient.Core liveThrough(RestingStrategy s, long lastSeq) {
        ParticipantClient.Core c = core(s);
        c.onHealth(true, lastSeq);
        ParticipantClient.Core.Connected k = c.onConnected();
        assertEquals(0, k.resendFrom(), "a fresh bot sends no RESEND");
        assertEquals(ParticipantClient.Phase.LIVE, c.phase);
        for (long seq = 1; seq <= lastSeq; seq++) c.onFrame(print(seq));
        return c;
    }

    @Test
    void aFreshConnectionGoesLiveAndTheFirstSequencedFrameSetsTheBaseline() {
        RestingStrategy s = new RestingStrategy(1, 100, OPEN);
        ParticipantClient.Core c = core(s);
        assertTrue(c.onTick(1).isEmpty(), "nothing before the connection");
        c.onHealth(true, 500);
        c.onConnected();
        assertEquals(1, news(c.onTick(2)).size());
        c.onFrame(print(501));
        assertEquals(501, c.seqs.lastApplied());
    }

    @Test
    void healthClosedMeansClosed() {
        RestingStrategy s = new RestingStrategy(1, 100, OPEN);
        ParticipantClient.Core c = core(s);
        c.onHealth(false, 0);
        c.onConnected();
        assertFalse(c.sessionOpen());
        assertTrue(c.onTick(1).isEmpty(), "no NEW while closed, so no reject storm");
    }

    @Test
    void aReconnectResendsFromTheLastAppliedSeqPlusOneAndAppliesTheReplayOnce() {
        RestingStrategy s = new RestingStrategy(1, 100, OPEN);
        ParticipantClient.Core c = liveThrough(s, 3);
        long id = news(c.onTick(10)).get(0).clOrdId();
        c.onFrame(accepted(4, id, 100));

        c.onDisconnected();
        assertTrue(c.onTick(11).isEmpty());
        c.onHealth(true, 8);
        ParticipantClient.Core.Connected k = c.onConnected();
        assertEquals(5, k.resendFrom());
        assertEquals(ParticipantClient.Phase.RESYNCING, c.phase);

        // Live frames from after the join arrive first, then the replay, with a duplicate.
        assertTrue(c.onFrame(print(9)).isEmpty());
        c.onFrame(print(5));
        c.onFrame(passiveFill(6, id, 100));
        c.onFrame(passiveFill(6, id, 100));   // the same seq twice
        assertTrue(c.onTick(12).isEmpty(), "no ticks while catching up");
        c.onFrame(print(7));
        c.onFrame(print(8));
        c.onFrame(print(9));

        assertEquals(100, s.own.position(), "the replayed fill moved the position once");
        assertEquals(100, c.gate.position(), "and the gate's too");
        ParticipantClient.Core.Resync r = c.onResendComplete();
        assertTrue(r.done());
        assertEquals(ParticipantClient.Phase.LIVE, c.phase);
        assertTrue(r.actions().isEmpty(), "the only order was filled: nothing uncertain");
        assertEquals(9, c.seqs.lastApplied());
    }

    @Test
    void afterTheCatchUpAnOrderTheVenueNeverAcknowledgedIsCancelled() {
        RestingStrategy s = new RestingStrategy(2, 100, OPEN);
        ParticipantClient.Core c = liveThrough(s, 2);
        long acked = news(c.onTick(10)).get(0).clOrdId();
        c.onFrame(accepted(3, acked, 100));
        long lost = news(c.onTick(11)).get(0).clOrdId();   // sent into the dying connection

        c.onDisconnected();
        c.onHealth(true, 3);
        assertEquals(4, c.onConnected().resendFrom());
        ParticipantClient.Core.Resync r = c.onResendComplete();
        assertTrue(r.done());
        assertEquals(List.of(lost), cancels(r.actions()).stream().map(Action::origClOrdId).toList());

        // The venue never knew it: the cancel is rejected, naming it, and the bot forgets it.
        c.onFrame(json("{\"type\":\"EXEC\",\"seq\":4,\"execType\":\"ORDER_REJECTED\",\"orderId\":" + lost + "}"));
        assertEquals(List.of(acked), s.restingIds(), "nothing orphaned, nothing phantom");
    }

    @Test
    void aGapLeftAfterTheReplayIsNotDone() {
        RestingStrategy s = new RestingStrategy(0, 100, OPEN);
        ParticipantClient.Core c = liveThrough(s, 2);
        c.onDisconnected();
        c.onHealth(true, 2);
        c.onConnected();
        c.onFrame(print(5));   // 3 and 4 never came
        assertFalse(c.onResendComplete().done());
        assertEquals(ParticipantClient.Phase.RESYNCING, c.phase);
        c.onFrame(print(3));
        c.onFrame(print(4));
        assertTrue(c.onResendComplete().done());
    }

    @Test
    void aSessionCloseReplayedWhileCatchingUpCancelsOnceLive() {
        RestingStrategy s = new RestingStrategy(1, 100, OPEN);
        ParticipantClient.Core c = liveThrough(s, 1);
        long id = news(c.onTick(10)).get(0).clOrdId();
        c.onFrame(accepted(2, id, 100));
        c.onDisconnected();
        c.onHealth(true, 3);   // read before the close was applied
        c.onConnected();
        assertTrue(c.onFrame(session(3, "CLOSED")).isEmpty(), "no action while catching up");
        assertFalse(s.own.resting().iterator().next().cancelPending(), "the strategy was not asked to cancel yet");
        ParticipantClient.Core.Resync r = c.onResendComplete();
        assertFalse(c.sessionOpen());
        assertEquals(List.of(id), cancels(r.actions()).stream().map(Action::origClOrdId).toList());
        assertTrue(c.onTick(20).isEmpty());
    }

    @Test
    void aVenueThatLostItsHistoryResetsTheBaselineAndCancelsEverything() {
        RestingStrategy s = new RestingStrategy(1, 100, OPEN);
        ParticipantClient.Core c = liveThrough(s, 50);
        long id = news(c.onTick(10)).get(0).clOrdId();
        c.onFrame(accepted(51, id, 100));
        c.onDisconnected();
        c.onHealth(true, 10);   // a wiped journal
        ParticipantClient.Core.Connected k = c.onConnected();
        assertTrue(k.venueReset());
        assertEquals(0, k.resendFrom());
        assertEquals(List.of(id), cancels(k.actions()).stream().map(Action::origClOrdId).toList());
        assertEquals(ParticipantClient.Phase.LIVE, c.phase);
        assertFalse(c.seqs.hasBaseline());
        c.onFrame(json("{\"type\":\"EXEC\",\"seq\":11,\"execType\":\"ORDER_REJECTED\",\"orderId\":" + id + "}"));
        assertTrue(s.restingIds().isEmpty());
        assertEquals(11, c.seqs.lastApplied());
    }

    @Test
    void aSessionOpenIsReportedOnlyWhenLive() {
        RestingStrategy s = new RestingStrategy(0, 100, OPEN);
        ParticipantClient.Core c = core(s);
        c.onHealth(false, 0);
        c.onConnected();
        c.onFrame(session(1, "OPEN"));
        assertTrue(c.takeSessionOpened());
        assertFalse(c.takeSessionOpened(), "once");
        c.onFrame(session(2, "CLOSED"));
        c.onDisconnected();
        c.onHealth(false, 2);
        c.onConnected();
        c.onFrame(session(3, "OPEN"));
        assertFalse(c.takeSessionOpened(), "an open replayed while catching up does not trigger a refresh");
        assertTrue(c.sessionOpen(), "but the state is folded in");
    }

    @Test
    void aRefusedNewIsNeverSentAndTheStrategyForgetsIt() {
        RestingStrategy s = new RestingStrategy(1, 600, OPEN);   // above the gate's 500
        ParticipantClient.Core c = core(s);
        c.onHealth(true, 0);
        c.onConnected();
        assertTrue(c.onTick(1).isEmpty());
        assertEquals(1, c.takeRefusals());
        assertEquals(RiskGate.Refusal.ORDER_TOO_LARGE, c.lastRefusal);
        assertFalse(s.hasResting(), "no phantom resting order to block a stop");
        assertEquals(BASE, s.execs.get(0).orderId());
    }
}
