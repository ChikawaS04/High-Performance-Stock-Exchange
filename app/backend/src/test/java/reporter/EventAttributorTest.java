package reporter;

import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Attribution of events to orders (pure logic, no database, no journal). */
class EventAttributorTest {

    private static final int DAY = 20261107;
    private static final long PRICE = 1_000_000L;

    private final ReporterBatch batch = new ReporterBatch();
    private EventAttributor attributor = new EventAttributor(EventAttributor.Resume.empty());
    private long nextInbound = 1L;
    private long nextOutbound = 1L;

    // --- commands ---

    private long command(OrderEvent c) {
        long seq = nextInbound++;
        attributor.onCommand(seq, c, batch);
        return seq;
    }

    private static OrderEvent base(OrderEventType type) {
        OrderEvent c = new OrderEvent();
        c.eventType = type;
        c.orderId = -1L;
        c.price = -1L;
        c.quantity = -1L;
        c.originalOrderId = -1L;
        c.timestamp = 1_000L;
        c.clearSessionFields();
        return c;
    }

    private long open() {
        OrderEvent c = base(OrderEventType.SESSION_OPEN);
        c.businessDate = DAY;
        byte[] symbol = "ASML".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(symbol, 0, c.symbol, 0, symbol.length);
        long seq = command(c);
        event(ExecutionEventType.SESSION_OPENED, seq, e -> e.businessDate = DAY);
        return seq;
    }

    private long close() {
        OrderEvent c = base(OrderEventType.SESSION_CLOSE);
        c.businessDate = DAY;
        long seq = command(c);
        event(ExecutionEventType.SESSION_CLOSED, seq, e -> e.businessDate = DAY);
        return seq;
    }

    private long newOrder(long id, long qty) {
        OrderEvent c = base(OrderEventType.NEW_ORDER);
        c.orderId = id;
        c.side = Side.BUY;
        c.ordType = OrdType.LIMIT;
        c.tif = TimeInForce.GTC;
        c.price = PRICE;
        c.quantity = qty;
        return command(c);
    }

    private long cancel(long id) {
        OrderEvent c = base(OrderEventType.CANCEL_ORDER);
        c.orderId = 9_000L + id;
        c.originalOrderId = id;
        return command(c);
    }

    // --- events ---

    private interface Fields {
        void set(ExecutionEvent e);
    }

    private ExecutionEvent event(ExecutionEventType type, long commandSeq, Fields fields) {
        ExecutionEvent e = new ExecutionEvent();
        e.seq = nextOutbound++;
        e.eventType = type;
        e.commandSeq = commandSeq;
        e.orderId = -1L;
        e.tradeId = -1L;
        e.price = -1L;
        e.filledQuantity = -1L;
        e.remainingQuantity = -1L;
        e.aggressorOrderId = -1L;
        e.passiveOrderId = -1L;
        e.aggressorParticipantId = -1L;
        e.passiveParticipantId = -1L;
        e.businessDate = -1;
        e.timestamp = 2_000L;
        fields.set(e);
        attributor.onEvent(e, batch, 0L);
        return e;
    }

    private void accepted(long commandSeq, long id, long remaining) {
        event(ExecutionEventType.ORDER_ACCEPTED, commandSeq, e -> {
            e.orderId = id;
            e.price = PRICE;
            e.remainingQuantity = remaining;
        });
    }

    private void fill(long commandSeq, long aggressor, long passive, long qty, long remaining) {
        event(remaining == 0 ? ExecutionEventType.ORDER_FILLED : ExecutionEventType.ORDER_PARTIALLY_FILLED, commandSeq, e -> {
            e.orderId = aggressor;
            e.tradeId = e.seq;
            e.price = PRICE;
            e.filledQuantity = qty;
            e.remainingQuantity = remaining;
            e.aggressorOrderId = aggressor;
            e.passiveOrderId = passive;
            e.aggressorSide = Side.BUY;
            e.aggressorParticipantId = 1L;
            e.passiveParticipantId = 1L;
        });
    }

    private void cancelled(long commandSeq, long id) {
        event(ExecutionEventType.ORDER_CANCELLED, commandSeq, e -> e.orderId = id);
    }

    private void rejected(long commandSeq, long id) {
        event(ExecutionEventType.ORDER_REJECTED, commandSeq, e -> e.orderId = id);
    }

    private void expired(long commandSeq, long id, long qty) {
        event(ExecutionEventType.ORDER_EXPIRED, commandSeq, e -> {
            e.orderId = id;
            e.remainingQuantity = qty;
        });
    }

    private ReporterBatch.ExecutionRow lastExecution() {
        return batch.executions.get(batch.executions.size() - 1);
    }

    private ReporterBatch.OrderUpdate lastUpdateFor(long inboundSeq) {
        for (int i = batch.orderUpdates.size() - 1; i >= 0; i--) {
            if (batch.orderUpdates.get(i).inboundSeq() == inboundSeq) return batch.orderUpdates.get(i);
        }
        return fail("no update for command " + inboundSeq);
    }

    private void assertStatus(long inboundSeq, String status, long cum) {
        ReporterBatch.OrderUpdate update = lastUpdateFor(inboundSeq);
        assertEquals(status, update.status());
        assertEquals(cum, update.cumQuantity());
    }

    private void assertInconsistent(org.junit.jupiter.api.function.Executable action, String fragment) {
        ReporterInconsistencyException e = assertThrows(ReporterInconsistencyException.class, action);
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    // --- rows ---

    @Test
    void aNewWritesItsOrderRowWithTheSessionsDateAndSymbol() {
        open();
        long seq = newOrder(7L, 10L);
        ReporterBatch.OrderRow row = batch.orders.get(0);
        assertEquals(seq, row.inboundSeq());
        assertEquals(7L, row.orderId());
        assertEquals(1L, row.participantId());
        assertEquals(DAY, row.businessDate());
        assertEquals("ASML", row.symbol());
        assertEquals("BUY", row.side());
        assertEquals("LIMIT", row.ordType());
        assertEquals("GTC", row.timeInForce());
        assertEquals(PRICE, row.limitPrice());
        assertEquals(10L, row.quantity());
        assertNull(row.displayQuantity());
    }

    @Test
    void aPeggedOrderHasNoLimitPriceAndAnIcebergHasADisplayQuantity() {
        open();
        OrderEvent peg = base(OrderEventType.NEW_ORDER);
        peg.orderId = 8L;
        peg.side = Side.SELL;
        peg.ordType = OrdType.PEG_MID;
        peg.tif = TimeInForce.GTC;
        peg.quantity = 5L;
        long seq = command(peg);
        assertNull(batch.orders.get(0).limitPrice());
        accepted(seq, 8L, 5L);

        OrderEvent iceberg = base(OrderEventType.NEW_ORDER);
        iceberg.orderId = 9L;
        iceberg.side = Side.SELL;
        iceberg.ordType = OrdType.LIMIT;
        iceberg.tif = TimeInForce.GTC;
        iceberg.price = PRICE;
        iceberg.quantity = 10L;
        iceberg.maxFloor = 3L;
        command(iceberg);
        assertEquals(3L, batch.orders.get(1).displayQuantity());
    }

    @Test
    void ordersAndEventsOutsideASessionHaveNoDateOrSymbol() {
        long seq = newOrder(7L, 10L);
        rejected(seq, 7L);
        assertNull(batch.orders.get(0).businessDate());
        assertNull(batch.orders.get(0).symbol());
        assertNull(lastExecution().businessDate());
        assertStatus(seq, "REJECTED", 0L);
    }

    @Test
    void theSessionEventsWriteNoRowButAdvanceTheCheckpoint() {
        open();
        assertTrue(batch.executions.isEmpty());
        assertTrue(batch.audit.isEmpty());
        assertEquals(1, batch.events());
        assertEquals(1L, attributor.outboundSeq());
        assertEquals(DAY, attributor.sessionDate());
        assertEquals("ASML", attributor.sessionSymbol());
        close();
        assertEquals(2L, attributor.outboundSeq());
        assertNull(attributor.sessionDate());
        assertNull(attributor.sessionSymbol());
    }

    @Test
    void aSessionCloseRecordsAPendingClearingRunAtItsSeq() {
        open();
        assertTrue(batch.pendingRuns.isEmpty(), "an open records no run");
        close();
        assertEquals(1, batch.pendingRuns.size());
        ReporterBatch.PendingRun run = batch.pendingRuns.get(0);
        assertEquals(DAY, run.businessDate());
        assertEquals(2L, run.closeOutboundSeq());
        batch.clear();
        assertTrue(batch.pendingRuns.isEmpty());
    }

    @Test
    void aRefusedSessionCommandIsConsumedWithNoEffect() {
        open();
        OrderEvent refused = base(OrderEventType.SESSION_OPEN);
        refused.businessDate = 20261108;
        command(refused);                     // no event: the engine refused a second open
        long seq = newOrder(7L, 10L);
        accepted(seq, 7L, 10L);
        assertEquals(DAY, batch.orders.get(0).businessDate());
    }

    // --- attribution ---

    @Test
    void acceptedAndExpiredBelongToTheirOwnNew() {
        open();
        long resting = newOrder(7L, 10L);
        accepted(resting, 7L, 10L);
        assertEquals(resting, lastExecution().orderInboundSeq());
        assertStatus(resting, "OPEN", 0L);

        long ioc = newOrder(8L, 5L);
        expired(ioc, 8L, 5L);
        assertEquals(ioc, lastExecution().orderInboundSeq());
        assertStatus(ioc, "EXPIRED", 0L);
    }

    @Test
    void anAcceptedOrdersSideIsNeverWrittenAsAnAggressorSide() {
        open();
        long resting = newOrder(7L, 10L);
        event(ExecutionEventType.ORDER_ACCEPTED, resting, e -> {
            e.orderId = 7L;
            e.price = PRICE;
            e.remainingQuantity = 10L;
            e.aggressorSide = Side.SELL;   // the resting order's side, as the engine now reports it
        });
        assertNull(lastExecution().aggressorSide(), "aggressor_side means the aggressor; an accept has none");

        long buyer = newOrder(8L, 10L);
        fill(buyer, 8L, 7L, 10L, 0L);
        assertEquals("BUY", lastExecution().aggressorSide());
    }

    @Test
    void aFillNamesTheAggressorsNewAndTheRestingPassiveOrder() {
        open();
        long passive = newOrder(7L, 10L);
        accepted(passive, 7L, 10L);
        long aggressor = newOrder(8L, 4L);
        fill(aggressor, 8L, 7L, 4L, 0L);

        assertEquals(aggressor, lastExecution().orderInboundSeq());
        assertEquals(passive, lastExecution().passiveOrderInboundSeq());
        assertStatus(aggressor, "FILLED", 4L);
        assertStatus(passive, "PARTIALLY_FILLED", 4L);
    }

    @Test
    void aPassiveOrderFilledAcrossCommandsIsTrackedToFilledAndLeavesTheMap() {
        open();
        long passive = newOrder(7L, 10L);
        accepted(passive, 7L, 10L);
        long first = newOrder(8L, 4L);
        fill(first, 8L, 7L, 4L, 0L);
        long second = newOrder(9L, 6L);
        fill(second, 9L, 7L, 6L, 0L);
        assertStatus(passive, "FILLED", 10L);
        assertEquals(0, attributor.restingCount());
    }

    @Test
    void anIcebergReloadIsInvisibleSoThePassiveTotalIsWhatCounts() {
        open();
        long iceberg = newOrder(7L, 10L);
        accepted(iceberg, 7L, 10L);
        long aggressor = newOrder(8L, 5L);
        fill(aggressor, 8L, 7L, 3L, 2L);      // tip of 3, then the reload's slice
        fill(aggressor, 8L, 7L, 2L, 0L);
        assertStatus(iceberg, "PARTIALLY_FILLED", 5L);
        assertEquals(1, attributor.restingCount());
    }

    @Test
    void aPartiallyFilledNewThatRestsIsPartiallyFilledAndAPartialThenExpiryKeepsItsQuantity() {
        open();
        long passive = newOrder(7L, 4L);
        accepted(passive, 7L, 4L);
        long resting = newOrder(8L, 10L);
        fill(resting, 8L, 7L, 4L, 6L);
        accepted(resting, 8L, 6L);
        assertStatus(resting, "PARTIALLY_FILLED", 4L);

        long ioc = newOrder(9L, 10L);
        fill(ioc, 9L, 8L, 6L, 4L);
        expired(ioc, 9L, 4L);
        assertStatus(ioc, "EXPIRED", 6L);
        assertStatus(resting, "FILLED", 10L);
    }

    @Test
    void aPegCrossUnderALitNewNamesTwoRestingOrders() {
        open();
        long pegBid = newOrder(1L, 5L);
        accepted(pegBid, 1L, 5L);
        long pegAsk = newOrder(2L, 5L);
        accepted(pegAsk, 2L, 5L);
        long lit = newOrder(3L, 1L);
        accepted(lit, 3L, 1L);                // a lit order rests, creating a mid
        fill(lit, 2L, 1L, 5L, 0L);            // the later peg is the aggressor, not the command's order

        assertEquals(pegAsk, lastExecution().orderInboundSeq());
        assertEquals(pegBid, lastExecution().passiveOrderInboundSeq());
        assertStatus(pegAsk, "FILLED", 5L);
        assertStatus(pegBid, "FILLED", 5L);
        assertEquals(1, attributor.restingCount());
    }

    @Test
    void aFillGoesIntoACandleBucketAtItsExactPrice() {
        open();
        long passive = newOrder(7L, 5L);
        accepted(passive, 7L, 5L);
        long aggressor = newOrder(8L, 5L);
        event(ExecutionEventType.ORDER_FILLED, aggressor, e -> {
            e.orderId = 8L;
            e.tradeId = 1L;
            e.price = 1_000_050L;             // a midpoint: sub-penny, never rounded
            e.filledQuantity = 5L;
            e.remainingQuantity = 0L;
            e.aggressorOrderId = 8L;
            e.passiveOrderId = 7L;
            e.aggressorSide = Side.BUY;
            e.aggressorParticipantId = 1L;
            e.passiveParticipantId = 1L;
            e.timestamp = Candles.BUCKET_NANOS * 5 + 7;
        });
        ReporterBatch.CandleFill candle = batch.candleFills.get(0);
        assertEquals("ASML", candle.symbol());
        assertEquals(DAY, candle.businessDate());
        assertEquals(Candles.BUCKET_NANOS * 5, candle.bucketStartTs());
        assertEquals(1_000_050L, candle.price());
        assertEquals(5L, candle.quantity());
    }

    @Test
    void onlyFillsMakeCandles() {
        open();
        long seq = newOrder(7L, 5L);
        accepted(seq, 7L, 5L);
        long cancel = cancel(7L);
        cancelled(cancel, 7L);
        assertTrue(batch.candleFills.isEmpty());
    }

    @Test
    void aDuplicateIdRejectBelongsToTheRejectedNewAndLeavesTheRestingOrderAlone() {
        open();
        long resting = newOrder(7L, 10L);
        accepted(resting, 7L, 10L);
        long duplicate = newOrder(7L, 3L);
        rejected(duplicate, 7L);

        assertEquals(duplicate, lastExecution().orderInboundSeq());
        assertStatus(duplicate, "REJECTED", 0L);

        long cancel = cancel(7L);
        cancelled(cancel, 7L);
        assertEquals(resting, lastExecution().orderInboundSeq(), "the resting order is what the cancel took");
        assertStatus(resting, "CANCELLED", 0L);
    }

    @Test
    void aRejectedCancelForAnUnknownOrderHasNoOrder() {
        open();
        long cancel = cancel(99L);
        rejected(cancel, 99L);

        assertTrue(batch.orders.isEmpty());
        assertTrue(batch.orderUpdates.isEmpty());
        assertNull(lastExecution().orderInboundSeq());
        assertEquals(99L, lastExecution().orderId());
        assertEquals(DAY, lastExecution().businessDate());
        assertEquals(2, batch.audit.size());
        for (ReporterBatch.AuditRow audit : batch.audit) {
            assertNull(audit.orderInboundSeq());
            assertEquals(99L, audit.orderId());
            assertNull(audit.price());
            assertNull(audit.quantity());
        }
        assertEquals("IN", batch.audit.get(0).stream());
        assertEquals("CANCEL_ORDER", batch.audit.get(0).event());
        assertEquals("OUT", batch.audit.get(1).stream());
        assertEquals("ORDER_REJECTED", batch.audit.get(1).event());
    }

    @Test
    void aCancelsAuditRowNamesTheOrderItCancels() {
        open();
        long resting = newOrder(7L, 10L);
        accepted(resting, 7L, 10L);
        long cancel = cancel(7L);
        ReporterBatch.AuditRow in = batch.audit.get(batch.audit.size() - 1);
        assertEquals(cancel, in.seq());
        assertEquals(resting, in.orderInboundSeq());
        assertEquals(7L, in.orderId());
    }

    @Test
    void anIdReusedAfterAFullFillIsANewOrder() {
        open();
        long first = newOrder(7L, 5L);
        accepted(first, 7L, 5L);
        long taker = newOrder(8L, 5L);
        fill(taker, 8L, 7L, 5L, 0L);

        long reused = newOrder(7L, 2L);
        accepted(reused, 7L, 2L);
        long cancel = cancel(7L);
        cancelled(cancel, 7L);
        assertEquals(reused, lastExecution().orderInboundSeq());
        assertStatus(first, "FILLED", 5L);
    }

    @Test
    void anIdReusedAfterACancelIsANewOrder() {
        open();
        long first = newOrder(7L, 5L);
        accepted(first, 7L, 5L);
        cancelled(cancel(7L), 7L);

        long reused = newOrder(7L, 2L);
        accepted(reused, 7L, 2L);
        long taker = newOrder(8L, 2L);
        fill(taker, 8L, 7L, 2L, 0L);
        assertEquals(reused, lastExecution().passiveOrderInboundSeq());
    }

    @Test
    void aCancelOfAGtcOrderAfterTheCloseHasNoDateAndTheOrderKeepsItsOwn() {
        open();
        long resting = newOrder(7L, 5L);
        accepted(resting, 7L, 5L);
        close();
        cancelled(cancel(7L), 7L);
        assertEquals(DAY, batch.orders.get(0).businessDate());
        assertNull(lastExecution().businessDate());
    }

    // --- resume ---

    @Test
    void aResumeKnowsTheRestingOrdersAndTheSession() {
        attributor = new EventAttributor(new EventAttributor.Resume(10L, 20L, DAY, "ASML",
                List.of(new EventAttributor.OpenOrder(4L, 7L, 10L, 3L)), null));
        nextInbound = 11L;
        nextOutbound = 21L;

        long aggressor = newOrder(8L, 7L);
        assertEquals(DAY, batch.orders.get(0).businessDate());
        fill(aggressor, 8L, 7L, 7L, 0L);
        assertEquals(4L, lastExecution().passiveOrderInboundSeq());
        assertStatus(4L, "FILLED", 10L);
    }

    /** A batch ended between two events of one NEW: the rest arrive with no command read first. */
    @Test
    void aResumeMidCommandContinuesTheNew() {
        attributor = new EventAttributor(new EventAttributor.Resume(10L, 20L, DAY, "ASML",
                List.of(new EventAttributor.OpenOrder(4L, 7L, 10L, 0L), new EventAttributor.OpenOrder(10L, 8L, 12L, 2L)),
                new EventAttributor.OpenOrder(10L, 8L, 12L, 2L)));
        nextInbound = 11L;
        nextOutbound = 21L;

        fill(10L, 8L, 7L, 10L, 0L);
        assertEquals(10L, lastExecution().orderInboundSeq());
        assertEquals(4L, lastExecution().passiveOrderInboundSeq());
        assertStatus(10L, "FILLED", 12L);
        assertEquals(0, attributor.restingCount());
    }

    @Test
    void aResumeAfterACancelHasNoNewToContinue() {
        attributor = new EventAttributor(new EventAttributor.Resume(10L, 20L, DAY, "ASML", List.of(), null));
        nextInbound = 11L;
        nextOutbound = 21L;
        assertInconsistent(() -> rejected(10L, 7L), "not under its own NEW");
    }

    // --- invariants: every breach stops, nothing is skipped ---

    private ExecutionEvent next(ExecutionEventType type, long commandSeq) {
        ExecutionEvent e = new ExecutionEvent();
        e.seq = nextOutbound;
        e.eventType = type;
        e.commandSeq = commandSeq;
        return e;
    }

    @Test
    void aVersion0EventStops() {
        assertInconsistent(() -> attributor.checkNext(next(ExecutionEventType.ORDER_ACCEPTED, -1L)), "no command seq");
    }

    @Test
    void anOutboundGapStops() {
        ExecutionEvent e = next(ExecutionEventType.ORDER_ACCEPTED, 1L);
        e.seq = 5L;
        assertInconsistent(() -> attributor.checkNext(e), "does not match this journal");
    }

    @Test
    void anInboundGapStops() {
        assertInconsistent(() -> attributor.onCommand(5L, base(OrderEventType.CANCEL_ORDER), batch),
                "does not match this journal");
    }

    @Test
    void aCommandSeqGoingBackwardsStops() {
        open();
        long seq = newOrder(7L, 5L);
        accepted(seq, 7L, 5L);
        assertInconsistent(() -> attributor.checkNext(next(ExecutionEventType.ORDER_ACCEPTED, seq - 1)), "after command");
    }

    @Test
    void aNewWithNoEventStops() {
        open();
        newOrder(7L, 5L);
        assertInconsistent(() -> newOrder(8L, 5L), "produced no event");
    }

    @Test
    void aCancelWithNoEventStops() {
        open();
        cancel(7L);
        assertInconsistent(() -> cancel(8L), "produced no event");
    }

    @Test
    void aCancelledForAnOrderNotRestingStops() {
        open();
        long cancel = cancel(7L);
        assertInconsistent(() -> cancelled(cancel, 7L), "not resting");
    }

    @Test
    void aRejectedCancelWhileTheOrderRestsStops() {
        open();
        long resting = newOrder(7L, 5L);
        accepted(resting, 7L, 5L);
        long cancel = cancel(7L);
        assertInconsistent(() -> rejected(cancel, 7L), "is resting");
    }

    @Test
    void aFillNamingAPassiveOrderNotRestingStops() {
        open();
        long seq = newOrder(8L, 5L);
        assertInconsistent(() -> fill(seq, 8L, 7L, 5L, 0L), "not resting");
    }

    @Test
    void aFillOutsideASessionStops() {
        long seq = newOrder(8L, 5L);
        assertInconsistent(() -> fill(seq, 8L, 7L, 5L, 0L), "outside a session");
    }

    @Test
    void aRemainingQuantityThatDisagreesWithTheJournalStops() {
        open();
        long seq = newOrder(7L, 5L);
        assertInconsistent(() -> accepted(seq, 7L, 4L), "remaining");
    }

    @Test
    void anOverfilledPassiveOrderStops() {
        open();
        long passive = newOrder(7L, 2L);
        accepted(passive, 7L, 2L);
        long aggressor = newOrder(8L, 5L);
        assertInconsistent(() -> fill(aggressor, 8L, 7L, 5L, 0L), "overfilled");
    }

    /**
     * A quantity above Integer.MAX_VALUE cannot enter any more (P15-10), but a journal from before that
     * bound can hold one, and the engine narrowed it. The Reporter stores the journaled value, so the
     * disagreement stops it rather than recording a quantity the engine never traded.
     */
    @Test
    void aJournaledQuantityTheEngineNarrowedStops() {
        open();
        long seq = newOrder(7L, (1L << 32) + 5L);
        assertInconsistent(() -> accepted(seq, 7L, 5L), "remaining");
    }

    @Test
    void aSessionOpenedWithNoMatchingCommandStops() {
        long seq = newOrder(7L, 5L);
        assertInconsistent(() -> event(ExecutionEventType.SESSION_OPENED, seq, e -> e.businessDate = DAY),
                "without a matching SESSION_OPEN");
    }

    @Test
    void aSessionClosedOnTheWrongDateStops() {
        open();
        OrderEvent close = base(OrderEventType.SESSION_CLOSE);
        close.businessDate = 20261108;
        long seq = command(close);
        assertInconsistent(() -> event(ExecutionEventType.SESSION_CLOSED, seq, e -> e.businessDate = 20261108),
                "while the session date is");
    }
}
