package engine;

import event.CapturingExecutionHandler;
import event.CapturingExecutionHandler.Observed;
import event.CapturingSnapshotHandler;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import model.Prices;
import model.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import util.VenueStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine's session state (P15-8, SRS §3.3, §3.12): NEW only while open, CANCEL in both states,
 * every SESSION_OPEN and SESSION_CLOSE edge case, the events and BOOKs they publish, the state other
 * threads read, the close hook, and the snapshot restore. The handler starts closed here; nothing
 * restores a session behind the test's back.
 *
 * A refused session command publishes no execution event, which the handler's synchronous
 * {@link MatchingEngineHandler#getLastSeq()} shows directly, and still publishes one BOOK.
 */
class MatchingEngineHandlerSessionTest {

    private static final long TS = 77L;
    private static final int DAY = Sessions.BUSINESS_DATE;
    private static final int NEXT_DAY = 20261103;

    private MatchingEngine engine;
    private OutboundPipeline outbound;
    private SnapshotPipeline snapshots;
    private CapturingExecutionHandler captured;
    private CapturingSnapshotHandler books;
    private MatchingEngineHandler handler;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        captured = new CapturingExecutionHandler();
        books = new CapturingSnapshotHandler();
        outbound = new OutboundPipeline();
        outbound.handleEventsWith(captured);
        outbound.start();
        snapshots = new SnapshotPipeline();
        snapshots.handleEventsWith(books);
        snapshots.start();
        handler = new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
        engine.setExecutionListener(handler);
    }

    @AfterEach
    void tearDown() {
        snapshots.shutdown();
        outbound.shutdown();
    }

    private static OrderEvent buy(long orderId, long qty) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.NEW_ORDER;
        e.orderId = orderId;
        e.side = Side.BUY;
        e.price = 1_000_000L;
        e.quantity = qty;
        e.maxFloor = 0L;
        e.originalOrderId = -1L;
        e.timestamp = TS;
        e.clearSessionFields();
        return e;
    }

    private static OrderEvent cancel(long clOrdId, long origId) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.CANCEL_ORDER;
        e.orderId = clOrdId;
        e.price = -1L;
        e.quantity = -1L;
        e.originalOrderId = origId;
        e.timestamp = TS;
        e.clearSessionFields();
        return e;
    }

    private void open(int date) {
        handler.onCommand(Sessions.openCommand(date, TS));
    }

    private void close(int date) {
        handler.onCommand(Sessions.closeCommand(date, TS));
    }

    /** Applies {@code command} and asserts it changed neither the outbound seq nor the session state. */
    private void assertRefused(OrderEvent command) {
        long seq = handler.getLastSeq();
        long word = handler.sessionWord();
        handler.onCommand(command);
        assertEquals(seq, handler.getLastSeq(), "a refused session command publishes no execution event");
        assertEquals(word, handler.sessionWord(), "a refused session command changes no state");
    }

    @Test
    void theHandlerStartsClosedAndNeverOpened() {
        assertFalse(handler.isSessionOpen());
        assertEquals(-1, handler.getBusinessDate());
        assertEquals(-1, handler.getLastClosedBusinessDate());
        assertEquals(0L, handler.sessionWord());
    }

    @Test
    void aNewWhileClosedIsRejectedAndNeverReachesTheBook() {
        handler.onCommand(buy(1, 10));
        List<Observed> obs = captured.awaitAtLeast(1, 1000);
        assertEquals(1, obs.size());
        assertEquals(ExecutionEventType.ORDER_REJECTED, obs.get(0).eventType());
        assertEquals(1, obs.get(0).orderId());
        assertFalse(engine.hasOpenOrder(1));
        assertEquals(-1, engine.getBestBid());
    }

    @Test
    void aNewAfterOpenIsAcceptedAndAfterCloseIsRejectedAgain() {
        open(DAY);
        handler.onCommand(buy(1, 10));
        close(DAY);
        handler.onCommand(buy(2, 10));

        List<Observed> obs = captured.awaitAtLeast(4, 1000);
        assertEquals(List.of(ExecutionEventType.SESSION_OPENED, ExecutionEventType.ORDER_ACCEPTED,
                        ExecutionEventType.SESSION_CLOSED, ExecutionEventType.ORDER_REJECTED),
                obs.stream().map(Observed::eventType).toList());
        assertTrue(engine.hasOpenOrder(1), "the GTC order rests across the close");
        assertFalse(engine.hasOpenOrder(2));
    }

    @Test
    void aCancelIsAcceptedWhileOpenAndWhileClosed() {
        open(DAY);
        handler.onCommand(buy(1, 10));
        handler.onCommand(buy(2, 10));
        handler.onCommand(cancel(3, 1));            // open
        close(DAY);
        handler.onCommand(cancel(4, 2));            // closed: the resting GTC order can still be withdrawn

        List<Observed> obs = captured.awaitAtLeast(6, 1000);
        assertEquals(ExecutionEventType.ORDER_CANCELLED, obs.get(3).eventType());
        assertEquals(1, obs.get(3).orderId());
        assertEquals(ExecutionEventType.ORDER_CANCELLED, obs.get(5).eventType());
        assertEquals(2, obs.get(5).orderId());
        assertFalse(engine.hasOpenOrder(1));
        assertFalse(engine.hasOpenOrder(2));
    }

    @Test
    void sessionEventsCarryTheBusinessDateTheCommandTimestampAndNothingElse() {
        open(DAY);
        close(DAY);
        List<Observed> obs = captured.awaitAtLeast(2, 1000);
        for (int i = 0; i < 2; i++) {
            Observed o = obs.get(i);
            assertEquals(i + 1, o.seq(), "session events take outbound seqs like any event");
            assertEquals(DAY, o.businessDate());
            assertEquals(TS, o.timestamp());
            assertEquals(-1, o.orderId());
            assertEquals(-1, o.tradeId());
            assertEquals(-1, o.price());
            assertEquals(-1, o.filledQuantity());
            assertEquals(-1, o.remainingQuantity());
            assertEquals(-1, o.aggressorOrderId());
            assertEquals(-1, o.passiveOrderId());
            assertNull(o.aggressorSide());
            assertEquals(-1, o.aggressorParticipantId());
            assertEquals(-1, o.passiveParticipantId());
        }
        assertEquals(ExecutionEventType.SESSION_OPENED, obs.get(0).eventType());
        assertEquals(ExecutionEventType.SESSION_CLOSED, obs.get(1).eventType());
    }

    @Test
    void orderEventsStillCarryTheMinusOneBusinessDate() {
        open(DAY);
        handler.onCommand(buy(1, 10));
        assertEquals(-1, captured.awaitAtLeast(2, 1000).get(1).businessDate());
    }

    @Test
    void anOpenWhileOpenIsRefusedForAnyDate() {
        open(DAY);
        assertRefused(Sessions.openCommand(DAY, TS));
        assertRefused(Sessions.openCommand(NEXT_DAY, TS));
        assertEquals(DAY, handler.getBusinessDate());
    }

    @Test
    void aCloseWhileClosedIsRefused() {
        assertRefused(Sessions.closeCommand(DAY, TS));
        open(DAY);
        close(DAY);
        assertRefused(Sessions.closeCommand(DAY, TS));
    }

    @Test
    void aCloseForAnotherDateIsRefusedAndTheSessionStaysOpen() {
        open(DAY);
        assertRefused(Sessions.closeCommand(NEXT_DAY, TS));
        assertRefused(Sessions.closeCommand(20261101, TS));
        assertTrue(handler.isSessionOpen());
    }

    @Test
    void aClosedDayIsNeverReopenedAndAnEarlierDayNeverOpens() {
        open(DAY);
        close(DAY);
        assertRefused(Sessions.openCommand(DAY, TS));
        assertRefused(Sessions.openCommand(20261030, TS));
        open(NEXT_DAY);
        assertTrue(handler.isSessionOpen());
        assertEquals(NEXT_DAY, handler.getBusinessDate());
        assertEquals(DAY, handler.getLastClosedBusinessDate());
    }

    @Test
    void anOpenWithReferenceDataTheEngineDoesNotHonourIsRefused() {
        byte[] symbol = Sessions.symbol();
        assertRefused(Sessions.openCommand(DAY, symbol, Prices.TICK * 2, 1L, Prices.SCALE, TS));
        assertRefused(Sessions.openCommand(DAY, symbol, Prices.TICK, 100L, Prices.SCALE, TS));
        assertRefused(Sessions.openCommand(DAY, symbol, Prices.TICK, 1L, 100L, TS));
        assertRefused(Sessions.openCommand(DAY, new byte[8], Prices.TICK, 1L, Prices.SCALE, TS));
        assertRefused(Sessions.openCommand(DAY, Sessions.symbol("asml"), Prices.TICK, 1L, Prices.SCALE, TS));
        assertRefused(Sessions.openCommand(20260230, TS));   // not a date
        assertRefused(Sessions.openCommand(-1, TS));
        assertFalse(handler.isSessionOpen());
    }

    @Test
    void everySessionCommandPublishesOneBookAcceptedOrRefused() {
        open(DAY);                                    // accepted
        handler.onCommand(Sessions.openCommand(DAY, TS));   // refused
        close(NEXT_DAY);                              // refused
        close(DAY);                                   // accepted
        assertEquals(4, books.awaitAtLeast(4, 1000).size());
        books.snapshot().forEach(b -> assertEquals(TS, b.timestamp()));
    }

    @Test
    void theSessionWordFollowsEveryTransition() {
        open(DAY);
        assertEquals(VenueStatus.sessionWord(true, DAY, -1), handler.sessionWord());
        assertTrue(VenueStatus.isSessionOpen(handler.sessionWord()));
        assertEquals(DAY, VenueStatus.sessionDate(handler.sessionWord()));
        close(DAY);
        assertFalse(VenueStatus.isSessionOpen(handler.sessionWord()));
        assertEquals(DAY, VenueStatus.sessionDate(handler.sessionWord()));
        open(NEXT_DAY);
        assertEquals(NEXT_DAY, VenueStatus.sessionDate(handler.sessionWord()));
    }

    @Test
    void theCloseHookRunsOncePerAcceptedClose() {
        AtomicInteger closes = new AtomicInteger();
        handler.setSessionCloseHook(closes::incrementAndGet);
        open(DAY);
        close(NEXT_DAY);   // refused
        assertEquals(0, closes.get());
        close(DAY);
        assertEquals(1, closes.get());
        close(DAY);        // refused: already closed
        assertEquals(1, closes.get());
    }

    @Test
    void restoreSessionPublishesNoEventAndBehavesAsTheStateItRestores() {
        handler.restoreSession(true, DAY, 20261030, Sessions.symbol());
        assertEquals(0, handler.getLastSeq());
        assertEquals(VenueStatus.sessionWord(true, DAY, 20261030), handler.sessionWord());
        byte[] symbol = new byte[8];
        handler.getSessionSymbol(symbol);
        assertArrayEquals(Sessions.symbol(), symbol);
        handler.onCommand(buy(1, 10));
        assertTrue(engine.hasOpenOrder(1), "orders match in a restored open session");

        assertThrows(IllegalArgumentException.class, () -> handler.restoreSession(true, 20261332, -1, symbol));
        assertThrows(IllegalArgumentException.class, () -> handler.restoreSession(false, -1, 7, symbol));
        assertThrows(IllegalArgumentException.class, () -> handler.restoreSession(false, -1, -1, new byte[4]));
    }

    @Test
    void aCommandWithNoTypeFailsInsteadOfBeingTreatedAsANewOrder() {
        OrderEvent unknown = buy(1, 10);
        unknown.eventType = null;
        assertThrows(NullPointerException.class, () -> handler.onCommand(unknown));
        assertFalse(engine.hasOpenOrder(1));
    }
}
