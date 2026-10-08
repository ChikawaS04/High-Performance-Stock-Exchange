package engine;

import event.CapturingExecutionHandler;
import event.CapturingExecutionHandler.Observed;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import event.OutboundPipeline;
import model.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineHandlerTest {

    private static final long TS = 42L;   // fixed clock -> deterministic timestamps

    private MatchingEngine engine;
    private OutboundPipeline outbound;
    private CapturingExecutionHandler captured;
    private MatchingEngineHandler handler;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        captured = new CapturingExecutionHandler();
        outbound = new OutboundPipeline();
        outbound.handleEventsWith(captured);
        outbound.start();
        handler = new MatchingEngineHandler(engine, outbound.getRingBuffer(), () -> TS);
        engine.setExecutionListener(handler);
    }

    @AfterEach
    void tearDown() {
        outbound.shutdown();
    }

    // --- helpers ---

    private static OrderEvent newOrder(long orderId, Side side, long price, long qty) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.NEW_ORDER;
        e.orderId = orderId;
        e.side = side;
        e.price = price;
        e.quantity = qty;
        e.timestamp = 1L;
        e.originalOrderId = -1L;
        return e;
    }

    private static OrderEvent cancel(long clOrdId, long origId) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.CANCEL_ORDER;
        e.orderId = clOrdId;
        e.side = null;
        e.price = -1L;
        e.quantity = -1L;
        e.timestamp = 1L;
        e.originalOrderId = origId;
        return e;
    }

    private void submit(OrderEvent e, long seq) {
        handler.onEvent(e, seq, true);
    }

    // --- tests ---

    @Test
    void nonCrossingOrder_restsAndEmitsAccepted() {
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);

        List<Observed> obs = captured.awaitAtLeast(1, 1000);
        assertEquals(1, obs.size());
        Observed o = obs.get(0);
        assertEquals(ExecutionEventType.ORDER_ACCEPTED, o.eventType());
        assertEquals(1, o.orderId());
        assertEquals(1_000_000, o.price());
        assertEquals(50, o.remainingQuantity());
        assertEquals(TS, o.timestamp());
    }

    @Test
    void exactCross_emitsFilledForAggressor() {
        submit(newOrder(1, Side.SELL, 1_000_000, 50), 0);   // rests -> ACCEPTED
        submit(newOrder(2, Side.BUY, 1_000_000, 50), 1);    // fully fills -> FILLED

        List<Observed> obs = captured.awaitAtLeast(2, 1000);
        assertEquals(2, obs.size());

        Observed fill = obs.get(1);
        assertEquals(ExecutionEventType.ORDER_FILLED, fill.eventType());
        assertEquals(2, fill.orderId());            // aggressor
        assertEquals(2, fill.aggressorOrderId());
        assertEquals(1, fill.passiveOrderId());     // resting ask
        assertEquals(1_000_000, fill.price());          // passive (resting) price
        assertEquals(50, fill.filledQuantity());
        assertEquals(0, fill.remainingQuantity());
        assertTrue(fill.tradeId() > 0);
    }

    @Test
    void partialCross_emitsPartialThenAcceptedForRemainder() {
        submit(newOrder(1, Side.SELL, 1_000_000, 50), 0);   // rests -> ACCEPTED
        submit(newOrder(2, Side.BUY, 1_000_000, 80), 1);    // fills 50, rests 30

        List<Observed> obs = captured.awaitAtLeast(3, 1000);
        assertEquals(3, obs.size());

        Observed partial = obs.get(1);
        assertEquals(ExecutionEventType.ORDER_PARTIALLY_FILLED, partial.eventType());
        assertEquals(2, partial.orderId());
        assertEquals(50, partial.filledQuantity());
        assertEquals(30, partial.remainingQuantity());

        Observed accepted = obs.get(2);
        assertEquals(ExecutionEventType.ORDER_ACCEPTED, accepted.eventType());
        assertEquals(2, accepted.orderId());
        assertEquals(30, accepted.remainingQuantity());
    }

    @Test
    void cancelRestingOrder_emitsCancelled() {
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);    // rests -> ACCEPTED
        submit(cancel(2, 1), 1);                         // cancel order 1

        List<Observed> obs = captured.awaitAtLeast(2, 1000);
        assertEquals(2, obs.size());
        Observed c = obs.get(1);
        assertEquals(ExecutionEventType.ORDER_CANCELLED, c.eventType());
        assertEquals(1, c.orderId());
    }

    @Test
    void cancelUnknownOrder_emitsRejected() {
        submit(cancel(2, 999), 0);

        List<Observed> obs = captured.awaitAtLeast(1, 1000);
        assertEquals(1, obs.size());
        Observed r = obs.get(0);
        assertEquals(ExecutionEventType.ORDER_REJECTED, r.eventType());
        assertEquals(999, r.orderId());
    }

    @Test
    void domainInvalidNewOrder_emitsRejected() {
        OrderEvent bad = newOrder(1, Side.BUY, -5, 50);   // price <= 0 -> Order ctor throws

        submit(bad, 0);

        List<Observed> obs = captured.awaitAtLeast(1, 1000);
        assertEquals(1, obs.size());
        Observed r = obs.get(0);
        assertEquals(ExecutionEventType.ORDER_REJECTED, r.eventType());
        assertEquals(1, r.orderId());
    }
}