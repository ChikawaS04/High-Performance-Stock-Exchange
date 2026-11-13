package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import event.ExecutionEventType;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frames the client puts on the wire, what it reads off it, and the session gate. */
class ParticipantClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void encodeNew_matchesProtocolFrame() throws Exception {
        Action a = Action.newLimit(100_000_000_000_000L, Side.BUY, TimeInForce.GTC, 1_500_000L, 50L);
        JsonNode n = mapper.readTree(ParticipantClient.encode(mapper, a, "ASML"));
        assertEquals("NEW", n.get("type").asText());
        assertEquals(100_000_000_000_000L, n.get("clOrdId").asLong());
        assertEquals("BUY", n.get("side").asText());
        assertEquals("LIMIT", n.get("ordType").asText());
        assertEquals("GTC", n.get("tif").asText());
        assertEquals(1_500_000L, n.get("price").asLong());
        assertEquals(50L, n.get("qty").asLong());
        assertEquals("ASML", n.get("symbol").asText());
        assertFalse(n.has("maxFloor"), "a plain limit sends no display size");
    }

    @Test
    void encodeIceberg_carriesMaxFloor() throws Exception {
        Action a = Action.newIceberg(500_000_000_000_001L, Side.SELL, 1_500_100L, 20_000L, 200L);
        JsonNode n = mapper.readTree(ParticipantClient.encode(mapper, a, "ASML"));
        assertEquals("SELL", n.get("side").asText());
        assertEquals("GTC", n.get("tif").asText());
        assertEquals(200L, n.get("maxFloor").asLong());
        assertEquals(20_000L, n.get("qty").asLong());
    }

    @Test
    void encodeCancel_matchesProtocolFrame() throws Exception {
        Action a = Action.cancel(100_000_000_000_009L, 100_000_000_000_003L);
        JsonNode n = mapper.readTree(ParticipantClient.encode(mapper, a, "ASML"));
        assertEquals("CANCEL", n.get("type").asText());
        assertEquals(100_000_000_000_009L, n.get("clOrdId").asLong());
        assertEquals(100_000_000_000_003L, n.get("origClOrdId").asLong());
    }

    @Test
    void parseExec_decodesAFillAndItsAggressorSide() throws Exception {
        String frame = "{\"type\":\"EXEC\",\"execType\":\"ORDER_FILLED\",\"orderId\":200000000000001,"
                + "\"tradeId\":7,\"price\":1500300,\"filledQuantity\":20,\"remainingQuantity\":0,"
                + "\"aggressorOrderId\":200000000000001,\"passiveOrderId\":100000000000004,"
                + "\"aggressorSide\":\"SELL\",\"timestamp\":123}";
        ExecReport r = ParticipantClient.parseExec(mapper.readTree(frame));
        assertEquals(ExecutionEventType.ORDER_FILLED, r.execType());
        assertEquals(1_500_300L, r.priceUnits());
        assertEquals(20L, r.filledQuantity());
        assertEquals(Side.SELL, r.side());
        assertEquals(Bots.Owner.TREND, Bots.owner(r.aggressorOrderId()));
        assertEquals(Bots.Owner.MAKER, Bots.owner(r.passiveOrderId()));
    }

    @Test
    void parseExec_readsTheSideOfAnAcceptedOrderAndToleratesNoSide() throws Exception {
        ExecReport acc = ParticipantClient.parseExec(mapper.readTree("{\"type\":\"EXEC\",\"execType\":\"ORDER_ACCEPTED\","
                + "\"orderId\":5,\"price\":1500000,\"remainingQuantity\":10,\"aggressorSide\":null,\"side\":\"BUY\"}"));
        assertEquals(Side.BUY, acc.side());
        ExecReport old = ParticipantClient.parseExec(mapper.readTree("{\"type\":\"EXEC\",\"execType\":\"ORDER_CANCELLED\","
                + "\"orderId\":5}"));
        assertNull(old.side());
    }

    @Test
    void isFill_onlyForFillTypes() {
        assertTrue(ParticipantClient.isFill(ExecutionEventType.ORDER_FILLED));
        assertTrue(ParticipantClient.isFill(ExecutionEventType.ORDER_PARTIALLY_FILLED));
        assertFalse(ParticipantClient.isFill(ExecutionEventType.ORDER_ACCEPTED));
        assertFalse(ParticipantClient.isFill(ExecutionEventType.ORDER_CANCELLED));
        assertFalse(ParticipantClient.isFill(ExecutionEventType.ORDER_EXPIRED));
    }

    /** Sends one order per tick and one cancel on close; records what it was shown. */
    private static final class Recording implements Strategy {
        final List<MarketView> views = new ArrayList<>();
        final List<ExecReport> execs = new ArrayList<>();
        int closes;

        @Override public String name() { return "REC"; }
        @Override public Bots.Owner owner() { return Bots.Owner.NOISE; }

        @Override
        public List<Action> onTick(MarketView view, long nowMillis) {
            views.add(view);
            return List.of(Action.newLimit(1, Side.BUY, TimeInForce.GTC, view.bestBid(), 1));
        }

        @Override public void onExec(ExecReport exec) { execs.add(exec); }

        @Override
        public List<Action> onSessionClose() {
            closes++;
            return List.of(Action.cancel(2, 1));
        }
    }

    @Test
    void theStrategyIsTickedOnlyWhileTheSessionIsOpen() throws Exception {
        Recording s = new Recording();
        ParticipantClient.Core core = new ParticipantClient.Core(s, null);
        assertTrue(core.onTick(1).isEmpty(), "closed until told otherwise");

        assertTrue(core.onFrame(mapper.readTree("{\"type\":\"SESSION\",\"state\":\"OPEN\"}")).isEmpty());
        core.onFrame(mapper.readTree("{\"type\":\"BOOK\",\"bestBid\":1499900,\"bestAsk\":1500100}"));
        List<Action> out = core.onTick(2);
        assertEquals(1, out.size());
        assertEquals(1_499_900L, s.views.get(0).bestBid());
        assertEquals(1_500_000L, s.views.get(0).midUnits());

        List<Action> close = core.onFrame(mapper.readTree("{\"type\":\"SESSION\",\"state\":\"CLOSED\"}"));
        assertEquals(List.of(Action.cancel(2, 1)), close, "the close cancels go out");
        assertTrue(core.onTick(3).isEmpty());
        assertTrue(core.onFrame(mapper.readTree("{\"type\":\"SESSION\",\"state\":\"CLOSED\"}")).isEmpty(),
                "a repeated CLOSED does not cancel again");
        assertEquals(1, s.closes);
    }

    @Test
    void onceStoppingTheStrategyIsNeverTickedAgainAndASessionCloseSendsNothingExtra() throws Exception {
        Recording s = new Recording();
        ParticipantClient.Core core = new ParticipantClient.Core(s, null);
        core.setSessionOpen(true);
        assertEquals(1, core.onTick(1).size());
        core.stopping = true;
        assertTrue(core.onTick(2).isEmpty(), "no order may follow the stop's cancel sweep");
        assertTrue(core.onFrame(mapper.readTree("{\"type\":\"SESSION\",\"state\":\"CLOSED\"}")).isEmpty());
        assertEquals(0, s.closes, "the stop sends its own cancels");
    }

    @Test
    void everyExecReachesTheStrategyAndFillsSetTheLastTrade() throws Exception {
        Recording s = new Recording();
        ParticipantClient.Core core = new ParticipantClient.Core(s, null);
        core.setSessionOpen(true);
        core.onFrame(mapper.readTree("{\"type\":\"EXEC\",\"execType\":\"ORDER_ACCEPTED\",\"orderId\":9,"
                + "\"price\":1500000,\"remainingQuantity\":10,\"side\":\"SELL\"}"));
        core.onFrame(mapper.readTree("{\"type\":\"EXEC\",\"execType\":\"ORDER_FILLED\",\"orderId\":8,\"price\":1500000,"
                + "\"filledQuantity\":10,\"remainingQuantity\":0,\"aggressorOrderId\":8,\"passiveOrderId\":9,"
                + "\"aggressorSide\":\"BUY\"}"));
        assertEquals(2, s.execs.size());
        assertEquals(1_500_000L, core.view().lastTradeUnits());
        assertEquals(List.of(Action.cancel(2, 1)), core.onStop());
    }
}
