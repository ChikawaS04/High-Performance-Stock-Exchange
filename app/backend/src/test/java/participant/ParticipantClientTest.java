package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import event.ExecutionEventType;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Codec tests: the frames the client puts on the wire match the terminal protocol exactly. */
class ParticipantClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void encodeNew_matchesProtocolFrame() throws Exception {
        Action a = Action.newLimit(1_000_000L, Side.BUY, TimeInForce.GTC, 1_500_000L, 50L);
        JsonNode n = mapper.readTree(ParticipantClient.encode(mapper, a, "ASML"));
        assertEquals("NEW", n.get("type").asText());
        assertEquals(1_000_000L, n.get("clOrdId").asLong());
        assertEquals("BUY", n.get("side").asText());
        assertEquals("LIMIT", n.get("ordType").asText());
        assertEquals("GTC", n.get("tif").asText());
        assertEquals(1_500_000L, n.get("price").asLong());
        assertEquals(50L, n.get("qty").asLong());
        assertEquals("ASML", n.get("symbol").asText());
    }

    @Test
    void encodeIocSell_carriesIoc() throws Exception {
        Action a = Action.newLimit(2_000_000L, Side.SELL, TimeInForce.IOC, 1_498_700L, 20L);
        JsonNode n = mapper.readTree(ParticipantClient.encode(mapper, a, "ASML"));
        assertEquals("SELL", n.get("side").asText());
        assertEquals("IOC", n.get("tif").asText());
    }

    @Test
    void encodeCancel_matchesProtocolFrame() throws Exception {
        Action a = Action.cancel(1_000_009L, 1_000_003L);
        JsonNode n = mapper.readTree(ParticipantClient.encode(mapper, a, "ASML"));
        assertEquals("CANCEL", n.get("type").asText());
        assertEquals(1_000_009L, n.get("clOrdId").asLong());
        assertEquals(1_000_003L, n.get("origClOrdId").asLong());
    }

    @Test
    void parseExec_decodesFillFrame() throws Exception {
        String frame = "{\"type\":\"EXEC\",\"execType\":\"ORDER_FILLED\",\"orderId\":2000001,"
                + "\"tradeId\":7,\"price\":1500300,\"filledQuantity\":20,\"remainingQuantity\":0,"
                + "\"aggressorOrderId\":2000001,\"passiveOrderId\":1000004,\"timestamp\":123}";
        ExecReport r = ParticipantClient.parseExec(mapper.readTree(frame));
        assertEquals(ExecutionEventType.ORDER_FILLED, r.execType());
        assertEquals(1_500_300L, r.priceUnits());
        assertEquals(20L, r.filledQuantity());
        assertEquals(2_000_001L, r.aggressorOrderId());
        assertEquals(1_000_004L, r.passiveOrderId());
        // Attribution derives purely from the id ranges.
        assertEquals(Bots.Owner.TAKER, Bots.owner(r.aggressorOrderId()));
        assertEquals(Bots.Owner.MAKER, Bots.owner(r.passiveOrderId()));
    }

    @Test
    void isFill_onlyForFillTypes() {
        assertEquals(true, ParticipantClient.isFill(ExecutionEventType.ORDER_FILLED));
        assertEquals(true, ParticipantClient.isFill(ExecutionEventType.ORDER_PARTIALLY_FILLED));
        assertEquals(false, ParticipantClient.isFill(ExecutionEventType.ORDER_ACCEPTED));
        assertEquals(false, ParticipantClient.isFill(ExecutionEventType.ORDER_CANCELLED));
        assertEquals(false, ParticipantClient.isFill(ExecutionEventType.ORDER_EXPIRED));
    }
}
