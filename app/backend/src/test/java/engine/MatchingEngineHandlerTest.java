package engine;

import event.CapturingExecutionHandler;
import event.CapturingExecutionHandler.Observed;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import event.OutboundPipeline;
import model.OrdType;
import model.Side;
import model.TimeInForce;
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

    private static OrderEvent newOrder(long orderId, Side side, long price, long qty, TimeInForce tif) {
        OrderEvent e = newOrder(orderId, side, price, qty);
        e.tif = tif;
        return e;
    }

    private static OrderEvent newOrder(long orderId, Side side, long price, long qty,
                                       TimeInForce tif, long maxFloor) {
        OrderEvent e = newOrder(orderId, side, price, qty, tif);
        e.maxFloor = maxFloor;
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

    @Test
    void iocPartialFill_publishesPartialThenExpiredForRemainder() {
        submit(newOrder(1, Side.SELL, 1_000_000, 50), 0);              // rests -> ACCEPTED
        submit(newOrder(2, Side.BUY, 1_000_000, 80, TimeInForce.IOC), 1);

        List<Observed> obs = captured.awaitAtLeast(3, 1000);
        assertEquals(3, obs.size());

        assertEquals(ExecutionEventType.ORDER_ACCEPTED, obs.get(0).eventType());

        Observed partial = obs.get(1);
        assertEquals(ExecutionEventType.ORDER_PARTIALLY_FILLED, partial.eventType());
        assertEquals(2, partial.orderId());
        assertEquals(50, partial.filledQuantity());
        assertEquals(30, partial.remainingQuantity());

        Observed expired = obs.get(2);
        assertEquals(ExecutionEventType.ORDER_EXPIRED, expired.eventType());
        assertEquals(2, expired.orderId());
        assertEquals(30, expired.remainingQuantity());   // D5: expired quantity carried here
        assertEquals(-1, expired.tradeId());
        assertEquals(-1, expired.price());
        assertEquals(-1, expired.filledQuantity());
        assertEquals(-1, expired.aggressorOrderId());
        assertEquals(-1, expired.passiveOrderId());
    }

    @Test
    void fokInsufficientLiquidity_publishesExpiredForFullQuantity() {
        submit(newOrder(1, Side.SELL, 1_000_000, 50), 0);              // rests -> ACCEPTED
        submit(newOrder(2, Side.BUY, 1_000_000, 100, TimeInForce.FOK), 1);

        List<Observed> obs = captured.awaitAtLeast(2, 1000);
        assertEquals(2, obs.size());

        assertEquals(ExecutionEventType.ORDER_ACCEPTED, obs.get(0).eventType());

        Observed expired = obs.get(1);
        assertEquals(ExecutionEventType.ORDER_EXPIRED, expired.eventType());
        assertEquals(2, expired.orderId());
        assertEquals(100, expired.remainingQuantity());  // full quantity expired
        assertEquals(-1, expired.tradeId());
        assertEquals(-1, expired.price());
        assertEquals(-1, expired.filledQuantity());
        assertEquals(-1, expired.aggressorOrderId());
        assertEquals(-1, expired.passiveOrderId());
    }

    @Test
    void icebergWithIoc_isRejectedByTheDomainConstructor() {
        // peak > 0 with a non-GTC tif violates the Order constructor's iceberg rule, so the
        // handler's IllegalArgumentException path reports ORDER_REJECTED (D8).
        submit(newOrder(1, Side.BUY, 1_000_000, 1000, TimeInForce.IOC, 100), 0);

        List<Observed> obs = captured.awaitAtLeast(1, 1000);
        assertEquals(1, obs.size());
        assertEquals(ExecutionEventType.ORDER_REJECTED, obs.get(0).eventType());
        assertEquals(1, obs.get(0).orderId());
    }

    private static OrderEvent peg(long orderId, Side side, long qty, TimeInForce tif, long maxFloor) {
        OrderEvent e = newOrder(orderId, side, -1L, qty, tif, maxFloor);
        e.ordType = OrdType.PEG_MID;
        return e;
    }

    @Test
    void pegWithDisplaySize_isRejectedByTheDomainConstructor() {
        // A midpoint peg with a display size (tag 111 > 0) violates the Order constructor rule that
        // a non-displayed order cannot be an iceberg, so the handler reports ORDER_REJECTED (E).
        submit(peg(1, Side.BUY, 1000, TimeInForce.GTC, 100), 0);

        List<Observed> obs = captured.awaitAtLeast(1, 1000);
        assertEquals(1, obs.size());
        assertEquals(ExecutionEventType.ORDER_REJECTED, obs.get(0).eventType());
        assertEquals(1, obs.get(0).orderId());
    }
}