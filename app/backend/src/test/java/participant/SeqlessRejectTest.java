package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.news;

/**
 * P16-8: under the venue's disk hard stop a NEW is refused before sequencing, and only its sender gets an
 * EXEC ORDER_REJECTED with no seq. This pins how the bots handle that exact frame: applied as is, never
 * offered to the seq tracker (so no gap, no RESEND), the order forgotten and the open-order count back down.
 */
class SeqlessRejectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Byte for byte what the venue writes (publisher/ExecutionFrames.refusalJson). */
    private static JsonNode refusal(long orderId) throws Exception {
        return MAPPER.readTree("{\"type\":\"EXEC\",\"execType\":\"ORDER_REJECTED\",\"orderId\":" + orderId
                + ",\"tradeId\":-1,\"price\":-1,\"filledQuantity\":-1,\"remainingQuantity\":-1,\"aggressorOrderId\":-1,"
                + "\"passiveOrderId\":-1,\"aggressorSide\":null,\"aggressorParticipantId\":-1,\"passiveParticipantId\":-1,"
                + "\"timestamp\":1700000000123456789}");
    }

    private static JsonNode print(long seq) throws Exception {
        return MAPPER.readTree("{\"type\":\"EXEC\",\"seq\":" + seq + ",\"execType\":\"ORDER_FILLED\",\"orderId\":5,"
                + "\"price\":" + OPEN + ",\"filledQuantity\":1,\"remainingQuantity\":0,\"aggressorOrderId\":5,"
                + "\"passiveOrderId\":6,\"aggressorSide\":\"BUY\"}");
    }

    @Test
    void aSeqLessRejectIsAppliedAsItIsAndNeverTouchesTheSeqTracker() throws Exception {
        RestingStrategy strategy = new RestingStrategy(2, 100, OPEN);
        RiskGate gate = new RiskGate(Bots.Owner.NOISE, new RiskLimits(OPEN, 20, 1_000), 500, 10);
        ParticipantClient.Core core = new ParticipantClient.Core(strategy, gate, null);
        core.onHealth(true, 3);
        assertEquals(0, core.onConnected().resendFrom());
        for (long seq = 1; seq <= 3; seq++) core.onFrame(print(seq));
        assertEquals(3, core.seqs.lastApplied());

        List<Action> sent = news(core.onTick(1));
        assertEquals(1, sent.size());
        long id = sent.get(0).clOrdId();
        assertEquals(1, gate.openOrders(), "the NEW counts as open until its fate is known");

        assertTrue(core.onFrame(refusal(id)).isEmpty(), "no action follows a reject");
        assertEquals(0, gate.openOrders(), "the refused order no longer counts against the limit");
        assertTrue(strategy.restingIds().isEmpty(), "the strategy forgot it");
        assertEquals(event.ExecutionEventType.ORDER_REJECTED, strategy.execs.get(strategy.execs.size() - 1).execType());

        // The seq tracker never saw it: no gap, the next expected seq unchanged, so nothing asks for a RESEND.
        assertEquals(3, core.seqs.lastApplied());
        assertFalse(core.seqs.hasGap());
        assertEquals(4, core.seqs.next());
        core.onFrame(print(4));
        assertEquals(4, core.seqs.lastApplied());
        assertFalse(core.seqs.hasGap());
        assertEquals(ParticipantClient.Phase.LIVE, core.phase);

        // And the bot keeps trading: its next NEW goes out.
        assertEquals(1, news(core.onTick(2)).size());
    }
}
