package engine;

import event.CapturingExecutionHandler;
import event.CapturingSnapshotHandler;
import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import model.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replay mode and the publish floor (P15-5, D13, SRS §3.10): replayed commands regenerate the
 * same seqs, events at or below the floor claim no slot, no BOOK is published per replayed
 * command, and leaving replay publishes exactly one BOOK of the recovered book. P15-5 replays
 * with the floor at Long.MAX_VALUE; a finite floor is what P15-6 passes, tested here.
 */
class MatchingEngineHandlerReplayTest {

    private static final long PRICE = 1_500_000L;

    private MatchingEngine engine;
    private OutboundPipeline outbound;
    private SnapshotPipeline snapshots;
    private CapturingExecutionHandler execs;
    private CapturingSnapshotHandler books;
    private MatchingEngineHandler handler;

    private final java.util.List<Long> commandSeqs = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        outbound = new OutboundPipeline();
        execs = new CapturingExecutionHandler();
        outbound.handleEventsWith(execs, (event, sequence, endOfBatch) -> commandSeqs.add(event.commandSeq));
        outbound.start();
        snapshots = new SnapshotPipeline();
        books = new CapturingSnapshotHandler();
        snapshots.handleEventsWith(books);
        snapshots.start();
        handler = new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
        engine.setExecutionListener(handler);
        Sessions.open(handler);   // P15-8: NEW is matched only in an open session
    }

    @AfterEach
    void tearDown() {
        outbound.shutdown();
        snapshots.shutdown();
    }

    private static OrderEvent order(long id, Side side, long qty, long ts) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.NEW_ORDER;
        e.orderId = id;
        e.side = side;
        e.price = PRICE;
        e.quantity = qty;
        e.timestamp = ts;
        e.originalOrderId = -1L;
        return e;
    }

    /** Four events: ACCEPTED 1, ACCEPTED 2, then a SELL that fills both (two fills). */
    private void fourEvents() {
        handler.onCommand(order(1, Side.BUY, 10, 100));    // seq 1
        handler.onCommand(order(2, Side.BUY, 10, 200));    // seq 2
        handler.onCommand(order(3, Side.SELL, 15, 300));   // seq 3 and 4
    }

    private void drained() {
        await(() -> outbound.isDrained() && snapshots.isDrained(), "rings drained");
    }

    @Test
    void eventsAtOrBelowTheFloorAreSuppressedAndSeqsStillAdvance() {
        handler.beginReplay(2);
        fourEvents();
        handler.endReplay(true);
        drained();

        List<CapturingExecutionHandler.Observed> seen = execs.snapshot();
        assertEquals(List.of(3L, 4L), seen.stream().map(CapturingExecutionHandler.Observed::seq).toList(),
                "only events above the floor are published, with their original seqs");
        assertEquals(4L, handler.getLastSeq());
        assertEquals(1, books.snapshot().size(), "one BOOK, from endReplay, none per replayed command");
        CapturingSnapshotHandler.Observed book = books.snapshot().get(0);
        assertEquals(4L, book.lastSeq());
        assertEquals(300L, book.timestamp(), "stamped with the last replayed command's timestamp");
        assertEquals(1, book.bidLevelCount());
        assertEquals(5L, book.bidQtys()[0], "the recovered book: order 2 has 5 left");
    }

    @Test
    void theP155FloorSuppressesEveryReplayedEventAndLiveEventsFlowAfterward() {
        handler.beginReplay(Long.MAX_VALUE);
        fourEvents();
        handler.endReplay(true);
        handler.onCommand(order(4, Side.SELL, 5, 400));     // live: fill, seq 5
        drained();

        assertEquals(List.of(5L), execs.snapshot().stream().map(CapturingExecutionHandler.Observed::seq).toList());
        assertEquals(2, books.snapshot().size(), "the recovered BOOK, then the live command's BOOK");
    }

    @Test
    void replayProducesTheSameSeqsAsALiveRun() {
        handler.beginReplay(Long.MAX_VALUE);
        fourEvents();
        handler.endReplay(false);

        MatchingEngine liveEngine = new MatchingEngine();
        MatchingEngineHandler live = new MatchingEngineHandler(liveEngine, new OutboundPipeline().getRingBuffer());
        liveEngine.setExecutionListener(live);
        Sessions.open(live);
        live.onCommand(order(1, Side.BUY, 10, 100));
        live.onCommand(order(2, Side.BUY, 10, 200));
        live.onCommand(order(3, Side.SELL, 15, 300));
        assertEquals(live.getLastSeq(), handler.getLastSeq());
        assertEquals(liveEngine.getNextTradeId(), engine.getNextTradeId());
        assertEquals(live.getCommandTimestamp(), handler.getCommandTimestamp());
    }

    @Test
    void leavingReplayWithoutABookPublishesNothing() {
        handler.beginReplay(Long.MAX_VALUE);
        handler.endReplay(false);
        drained();
        assertTrue(books.snapshot().isEmpty(), "a venue with no history publishes no BOOK, as before P15-5");
    }

    /** The recorded fill at seq 4 of {@link #fourEvents()}: order 3 sells 5 more against order 2. */
    private static ExecutionEvent recordedSeq4() {
        ExecutionEvent e = new ExecutionEvent();
        e.seq = 4;
        e.eventType = ExecutionEventType.ORDER_FILLED;
        e.orderId = 3;
        e.tradeId = 2;
        e.price = PRICE;
        e.filledQuantity = 5;
        e.remainingQuantity = 0;
        e.aggressorOrderId = 3;
        e.passiveOrderId = 2;
        e.aggressorSide = Side.SELL;
        e.aggressorParticipantId = 1;
        e.passiveParticipantId = 1;
        e.businessDate = -1;
        e.timestamp = 300;
        return e;
    }

    @Test
    void theRegeneratedEventAtTheFloorMatchingTheRecordingPasses() {
        handler.beginReplay(4, recordedSeq4());
        fourEvents();
        handler.endReplay(false);
        drained();
        assertTrue(execs.snapshot().isEmpty(), "everything at or below the floor suppressed");
        assertEquals(4L, handler.getLastSeq());
    }

    /** The recorded ACCEPTED at seq 1 of {@link #fourEvents()}: order 1, a BUY of 10, rests. */
    private static ExecutionEvent recordedAcceptAtSeq1(Side side) {
        ExecutionEvent e = new ExecutionEvent();
        e.seq = 1;
        e.eventType = ExecutionEventType.ORDER_ACCEPTED;
        e.orderId = 1;
        e.tradeId = -1;
        e.price = PRICE;
        e.filledQuantity = -1;
        e.remainingQuantity = 10;
        e.aggressorOrderId = -1;
        e.passiveOrderId = -1;
        e.aggressorSide = side;
        e.aggressorParticipantId = -1;
        e.passiveParticipantId = -1;
        e.businessDate = -1;
        e.timestamp = 100;
        return e;
    }

    @Test
    void anAcceptRecordedWithTheOrdersSideMatches() {
        handler.beginReplay(1, recordedAcceptAtSeq1(Side.BUY));
        handler.onCommand(order(1, Side.BUY, 10, 100));
        assertEquals(1L, handler.getLastSeq());
    }

    @Test
    void anAcceptRecordedBeforeAcceptsCarriedASideHasNoSideToCompare() {
        handler.beginReplay(1, recordedAcceptAtSeq1(null));   // an older outbound recording
        handler.onCommand(order(1, Side.BUY, 10, 100));
        assertEquals(1L, handler.getLastSeq(), "no fail-stop on restart over an older journal");
    }

    @Test
    void anAcceptRecordedWithTheOtherSideStillDiverges() {
        handler.beginReplay(1, recordedAcceptAtSeq1(Side.SELL));
        IllegalStateException diverged = assertThrows(IllegalStateException.class,
                () -> handler.onCommand(order(1, Side.BUY, 10, 100)));
        assertTrue(diverged.getMessage().contains("side=SELL"), diverged.getMessage());
        assertTrue(diverged.getMessage().contains("side=BUY"), diverged.getMessage());
    }

    @Test
    void aFillRecordedWithNoSideStillDiverges() {
        ExecutionEvent recorded = recordedSeq4();
        recorded.aggressorSide = null;   // the null tolerance is for accepts only
        handler.beginReplay(4, recorded);
        handler.onCommand(order(1, Side.BUY, 10, 100));
        handler.onCommand(order(2, Side.BUY, 10, 200));
        assertThrows(IllegalStateException.class, () -> handler.onCommand(order(3, Side.SELL, 15, 300)));
    }

    @Test
    void aRecordedCommandSeqIsComparedAtTheFloor() {
        ExecutionEvent recorded = recordedSeq4();
        recorded.commandSeq = 9L;
        handler.beginReplay(4, recorded);
        handler.onCommand(order(1, Side.BUY, 10, 100), 7L);
        handler.onCommand(order(2, Side.BUY, 10, 200), 8L);
        IllegalStateException diverged = assertThrows(IllegalStateException.class,
                () -> handler.onCommand(order(3, Side.SELL, 15, 300), 10L));
        assertTrue(diverged.getMessage().contains("commandSeq=9"), diverged.getMessage());
        assertTrue(diverged.getMessage().contains("commandSeq=10"), diverged.getMessage());
    }

    @Test
    void aVersion0RecordingAtTheFloorHasNoCommandSeqToCompare() {
        ExecutionEvent recorded = recordedSeq4();   // commandSeq -1, as decoded from a version 0 message
        handler.beginReplay(4, recorded);
        handler.onCommand(order(1, Side.BUY, 10, 100), 7L);
        handler.onCommand(order(2, Side.BUY, 10, 200), 8L);
        handler.onCommand(order(3, Side.SELL, 15, 300), 10L);
        assertEquals(4L, handler.getLastSeq());
    }

    @Test
    void everyEventACommandCausesCarriesItsSeq() {
        handler.onCommand(order(1, Side.BUY, 10, 100), 41L);
        handler.onCommand(order(2, Side.SELL, 10, 200), 42L);
        drained();
        int size = commandSeqs.size();
        assertEquals(java.util.List.of(41L, 42L), commandSeqs.subList(size - 2, size));
    }

    @Test
    void aSessionEventIsCheckedAgainstTheRecordingIncludingItsBusinessDate() {
        ExecutionEvent recorded = new ExecutionEvent();
        recorded.seq = 1;
        recorded.eventType = ExecutionEventType.SESSION_OPENED;
        recorded.orderId = -1;
        recorded.tradeId = -1;
        recorded.price = -1;
        recorded.filledQuantity = -1;
        recorded.remainingQuantity = -1;
        recorded.aggressorOrderId = -1;
        recorded.passiveOrderId = -1;
        recorded.aggressorParticipantId = -1;
        recorded.passiveParticipantId = -1;
        recorded.businessDate = Sessions.BUSINESS_DATE;
        recorded.timestamp = 50;

        MatchingEngine same = new MatchingEngine();
        MatchingEngineHandler matching = new MatchingEngineHandler(same, new OutboundPipeline().getRingBuffer());
        same.setExecutionListener(matching);
        matching.beginReplay(1, recorded);
        matching.onCommand(Sessions.openCommand(Sessions.BUSINESS_DATE, 50));   // regenerates the recorded event
        assertEquals(1L, matching.getLastSeq());

        recorded.businessDate = 20261103;
        MatchingEngine other = new MatchingEngine();
        MatchingEngineHandler diverging = new MatchingEngineHandler(other, new OutboundPipeline().getRingBuffer());
        other.setExecutionListener(diverging);
        diverging.beginReplay(1, recorded);
        IllegalStateException diverged = assertThrows(IllegalStateException.class,
                () -> diverging.onCommand(Sessions.openCommand(Sessions.BUSINESS_DATE, 50)));
        assertTrue(diverged.getMessage().contains("businessDate=20261103"), diverged.getMessage());
        assertTrue(diverged.getMessage().contains("businessDate=" + Sessions.BUSINESS_DATE), diverged.getMessage());
    }

    @Test
    void anyFieldThatDiffersFromTheRecordingAtTheFloorThrows() {
        ExecutionEvent recorded = recordedSeq4();
        recorded.filledQuantity = 6;
        handler.beginReplay(4, recorded);
        handler.onCommand(order(1, Side.BUY, 10, 100));
        handler.onCommand(order(2, Side.BUY, 10, 200));
        IllegalStateException diverged = assertThrows(IllegalStateException.class,
                () -> handler.onCommand(order(3, Side.SELL, 15, 300)));
        assertTrue(diverged.getMessage().contains("different event at outbound seq 4"), diverged.getMessage());
        assertTrue(diverged.getMessage().contains("filled=6"), diverged.getMessage());

        ExecutionEvent wrongTimestamp = recordedSeq4();
        wrongTimestamp.timestamp = 301;
        MatchingEngine second = new MatchingEngine();
        MatchingEngineHandler replaying = new MatchingEngineHandler(second, new OutboundPipeline().getRingBuffer());
        second.setExecutionListener(replaying);
        Sessions.open(replaying);
        replaying.beginReplay(4, wrongTimestamp);
        replaying.onCommand(order(1, Side.BUY, 10, 100));
        replaying.onCommand(order(2, Side.BUY, 10, 200));
        assertThrows(IllegalStateException.class, () -> replaying.onCommand(order(3, Side.SELL, 15, 300)));

        assertThrows(IllegalArgumentException.class, () -> handler.beginReplay(5, recordedSeq4()),
                "the expected event must carry the floor seq");
    }

    @Test
    void aNegativeFloorIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> handler.beginReplay(-1));
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
