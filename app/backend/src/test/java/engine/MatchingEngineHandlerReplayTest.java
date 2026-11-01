package engine;

import event.CapturingExecutionHandler;
import event.CapturingSnapshotHandler;
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

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        outbound = new OutboundPipeline();
        execs = new CapturingExecutionHandler();
        outbound.handleEventsWith(execs);
        outbound.start();
        snapshots = new SnapshotPipeline();
        books = new CapturingSnapshotHandler();
        snapshots.handleEventsWith(books);
        snapshots.start();
        handler = new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
        engine.setExecutionListener(handler);
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
