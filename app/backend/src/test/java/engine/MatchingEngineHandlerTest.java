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

    private static final long TS = 42L;   // command timestamp; the handler stamps every EXEC with it (P15-2)

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
        handler = new MatchingEngineHandler(engine, outbound.getRingBuffer());
        engine.setExecutionListener(handler);
        Sessions.open(handler);   // P15-8: NEW is matched only in an open session
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
        e.timestamp = TS;
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
        e.timestamp = TS;
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

    private static OrderEvent at(OrderEvent e, long timestamp) {
        e.timestamp = timestamp;
        return e;
    }

    // --- P15-2 D12: outbound seq ---

    @Test
    void seq_isGapFreeFromOne_acrossEveryEventType() {
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);    // ACCEPTED
        submit(newOrder(2, Side.SELL, 1_000_000, 30), 1);   // FILLED
        submit(newOrder(3, Side.SELL, 1_000_000, 40), 2);   // PARTIALLY_FILLED + ACCEPTED (20 rests)
        submit(cancel(4, 3), 3);                             // CANCELLED
        submit(cancel(5, 99), 4);                            // REJECTED
        submit(newOrder(6, Side.BUY, 1_000_000, 10, TimeInForce.IOC), 5);   // EXPIRED

        List<Observed> obs = captured.awaitAtLeast(7, 1000);
        assertEquals(7, obs.size());
        for (int i = 0; i < obs.size(); i++) {
            assertEquals(i + 1, obs.get(i).seq());
        }
        assertEquals(7, handler.getLastSeq());
    }

    @Test
    void seq_continuesFromRestoredValue() {
        handler.restoreLastSeq(41);
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);
        submit(newOrder(2, Side.SELL, 1_000_000, 50), 1);

        List<Observed> obs = captured.awaitAtLeast(2, 1000);
        assertEquals(42, obs.get(0).seq());
        assertEquals(43, obs.get(1).seq());
        assertEquals(43, handler.getLastSeq());
        assertThrows(IllegalArgumentException.class, () -> handler.restoreLastSeq(-1));
    }

    // --- P15-2: timestamps come from the command, never a clock ---

    @Test
    void execTimestamp_isTheCommandsTimestamp_onEveryPath() {
        submit(at(newOrder(1, Side.BUY, 1_000_000, 50), 1_000L), 0);    // ACCEPTED
        submit(at(newOrder(2, Side.SELL, 1_000_000, 50), 2_000L), 1);   // FILLED
        submit(at(cancel(3, 99), 3_000L), 2);                            // cancel REJECTED
        submit(at(newOrder(4, Side.BUY, 1_000_050, 50), 4_000L), 3);    // off-tick: validation REJECTED

        List<Observed> obs = captured.awaitAtLeast(4, 1000);
        assertEquals(4, obs.size());
        assertEquals(1_000L, obs.get(0).timestamp());
        assertEquals(2_000L, obs.get(1).timestamp());
        assertEquals(3_000L, obs.get(2).timestamp());
        assertEquals(ExecutionEventType.ORDER_REJECTED, obs.get(3).eventType());
        assertEquals(4_000L, obs.get(3).timestamp());
    }

    @Test
    void onCommand_drivesTheSamePathAsOnEvent() {
        handler.onCommand(at(newOrder(1, Side.BUY, 1_000_000, 50), 7_000L));

        List<Observed> obs = captured.awaitAtLeast(1, 1000);
        assertEquals(ExecutionEventType.ORDER_ACCEPTED, obs.get(0).eventType());
        assertEquals(1, obs.get(0).seq());
        assertEquals(7_000L, obs.get(0).timestamp());
    }

    // --- P15-2 D3: duplicate order id ---

    @Test
    void duplicateIdOfRestingOrder_isRejected_andRestingOrderStaysFillable() {
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);    // rests
        submit(newOrder(1, Side.SELL, 1_000_000, 10), 1);   // duplicate id; would cross if admitted

        submit(newOrder(2, Side.SELL, 1_000_000, 50), 2);   // must fill the ORIGINAL order 1 in full

        List<Observed> obs = captured.awaitAtLeast(3, 1000);
        assertEquals(3, obs.size());
        assertEquals(ExecutionEventType.ORDER_REJECTED, obs.get(1).eventType());
        assertEquals(1, obs.get(1).orderId());
        assertEquals(-1, obs.get(1).tradeId());

        Observed fill = obs.get(2);
        assertEquals(ExecutionEventType.ORDER_FILLED, fill.eventType());
        assertEquals(1, fill.passiveOrderId());
        assertEquals(50, fill.filledQuantity());            // the resting 50, untouched by the duplicate
        assertEquals(1_000_000, fill.price());
        assertEquals(-1L, engine.getBestBid());
    }

    @Test
    void duplicateIdOfRestingOrder_isRejected_andRestingOrderStaysCancellable() {
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);
        submit(newOrder(1, Side.BUY, 1_010_000, 70), 1);    // duplicate id at a different price
        assertEquals(1_000_000L, engine.getBestBid());      // the duplicate never reached the book

        submit(cancel(2, 1), 2);

        List<Observed> obs = captured.awaitAtLeast(3, 1000);
        assertEquals(ExecutionEventType.ORDER_REJECTED, obs.get(1).eventType());
        assertEquals(ExecutionEventType.ORDER_CANCELLED, obs.get(2).eventType());
        assertEquals(1, obs.get(2).orderId());
        assertEquals(-1L, engine.getBestBid());
    }

    @Test
    void duplicateIdOfRestingPeg_isRejected_andPegStaysFillable() {
        submit(newOrder(901, Side.BUY, 1_000_000, 10), 0);
        submit(newOrder(902, Side.SELL, 1_000_100, 10), 1);           // mid exists
        submit(peg(1, Side.SELL, 100, TimeInForce.GTC, 0), 2);        // resting peg sell
        submit(peg(1, Side.BUY, 100, TimeInForce.GTC, 0), 3);         // duplicate; would cross the pool
        submit(peg(2, Side.BUY, 100, TimeInForce.GTC, 0), 4);

        List<Observed> obs = captured.awaitAtLeast(5, 1000);
        assertEquals(5, obs.size());
        assertEquals(ExecutionEventType.ORDER_REJECTED, obs.get(3).eventType());
        assertEquals(1, obs.get(3).orderId());
        assertEquals(ExecutionEventType.ORDER_FILLED, obs.get(4).eventType());
        assertEquals(1, obs.get(4).passiveOrderId());
        assertEquals(100, obs.get(4).filledQuantity());
    }

    @Test
    void reusingTheIdOfAFullyFilledOrder_isAccepted() {
        submit(newOrder(1, Side.SELL, 1_000_000, 50), 0);
        submit(newOrder(2, Side.BUY, 1_000_000, 50), 1);    // order 1 fully filled, leaves the book
        submit(newOrder(1, Side.BUY, 990_000, 10), 2);      // id 1 no longer rests: a new order

        List<Observed> obs = captured.awaitAtLeast(3, 1000);
        assertEquals(ExecutionEventType.ORDER_ACCEPTED, obs.get(2).eventType());
        assertEquals(1, obs.get(2).orderId());
    }

    // --- P15-2 D4: fill side and participants ---

    @Test
    void fills_carryAggressorSideAndBothParticipants_nonFillsCarrySentinels() {
        submit(newOrder(1, Side.SELL, 1_000_000, 50), 0);   // ACCEPTED
        submit(newOrder(2, Side.BUY, 1_000_000, 20), 1);    // buy aggressor
        submit(newOrder(3, Side.BUY, 990_000, 20), 2);      // ACCEPTED bid
        submit(newOrder(4, Side.SELL, 990_000, 20), 3);     // sell aggressor

        List<Observed> obs = captured.awaitAtLeast(4, 1000);
        assertEquals(4, obs.size());

        Observed accepted = obs.get(0);
        assertNull(accepted.aggressorSide());
        assertEquals(-1L, accepted.aggressorParticipantId());
        assertEquals(-1L, accepted.passiveParticipantId());

        Observed buyFill = obs.get(1);
        assertEquals(ExecutionEventType.ORDER_FILLED, buyFill.eventType());
        assertEquals(Side.BUY, buyFill.aggressorSide());
        assertEquals(1L, buyFill.aggressorParticipantId());   // the gateway's single participant
        assertEquals(1L, buyFill.passiveParticipantId());

        Observed sellFill = obs.get(3);
        assertEquals(ExecutionEventType.ORDER_FILLED, sellFill.eventType());
        assertEquals(Side.SELL, sellFill.aggressorSide());
        assertEquals(1L, sellFill.aggressorParticipantId());
        assertEquals(1L, sellFill.passiveParticipantId());
        assertEquals(-1, sellFill.businessDate());
    }
}