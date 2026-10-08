package engine;

import event.BookSnapshotEvent;
import event.CapturingExecutionHandler;
import event.CapturingSnapshotHandler;
import event.CapturingSnapshotHandler.Observed;
import event.OrderEvent;
import event.OrderEventType;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import model.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4-2: the handler publishes one book snapshot per inbound event on the second
 * single-producer ring, reflecting book state after that event.
 */
class MatchingEngineHandlerSnapshotTest {

    private static final long TS = 42L;   // fixed clock -> deterministic timestamps

    private MatchingEngine engine;
    private OutboundPipeline outbound;
    private SnapshotPipeline snapshotPipeline;
    private CapturingSnapshotHandler snaps;
    private MatchingEngineHandler handler;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();

        outbound = new OutboundPipeline();
        outbound.handleEventsWith(new CapturingExecutionHandler());
        outbound.start();

        snaps = new CapturingSnapshotHandler();
        snapshotPipeline = new SnapshotPipeline();
        snapshotPipeline.handleEventsWith(snaps);
        snapshotPipeline.start();

        handler = new MatchingEngineHandler(
                engine, outbound.getRingBuffer(), snapshotPipeline.getRingBuffer(), () -> TS);
        engine.setExecutionListener(handler);
    }

    @AfterEach
    void tearDown() {
        snapshotPipeline.shutdown();
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
    void restingOrder_snapshotShowsBidLevel() {
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);

        List<Observed> obs = snaps.awaitAtLeast(1, 1000);
        assertEquals(1, obs.size());
        Observed s = obs.get(0);
        assertEquals(1, s.bidLevelCount());
        assertEquals(1_000_000L, s.bidPrices()[0]);
        assertEquals(50L, s.bidQtys()[0]);
        assertEquals(1_000_000L, s.bestBid());
        assertEquals(0, s.askLevelCount());
        assertEquals(-1L, s.bestAsk());
        assertEquals(TS, s.timestamp());     // handler clock, not System.nanoTime
    }

    @Test
    void oneSnapshotPerInboundEvent() {
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);
        submit(newOrder(2, Side.SELL, 1_010_000, 50), 1);
        submit(cancel(3, 1), 2);

        List<Observed> obs = snaps.awaitAtLeast(3, 1000);
        assertEquals(3, obs.size());
    }

    @Test
    void exactCross_finalSnapshotShowsEmptyBook() {
        submit(newOrder(1, Side.SELL, 1_000_000, 50), 0);   // rests
        submit(newOrder(2, Side.BUY, 1_000_000, 50), 1);    // fully fills it

        List<Observed> obs = snaps.awaitAtLeast(2, 1000);
        assertEquals(2, obs.size());

        Observed afterRest = obs.get(0);
        assertEquals(1, afterRest.askLevelCount());
        assertEquals(1_000_000L, afterRest.bestAsk());

        Observed afterFill = obs.get(1);
        assertEquals(0, afterFill.bidLevelCount());
        assertEquals(0, afterFill.askLevelCount());
        assertEquals(-1L, afterFill.bestBid());
        assertEquals(-1L, afterFill.bestAsk());
    }

    @Test
    void partialCross_snapshotShowsRestedRemainder() {
        submit(newOrder(1, Side.SELL, 1_000_000, 50), 0);   // rests 50 @ 1_000_000
        submit(newOrder(2, Side.BUY, 1_000_000, 80), 1);    // fills 50, rests 30 on the bid

        List<Observed> obs = snaps.awaitAtLeast(2, 1000);
        Observed s = obs.get(1);

        assertEquals(0, s.askLevelCount());             // ask consumed
        assertEquals(-1L, s.bestAsk());
        assertEquals(1, s.bidLevelCount());
        assertEquals(1_000_000L, s.bidPrices()[0]);
        assertEquals(30L, s.bidQtys()[0]);              // remainder rested
        assertEquals(1_000_000L, s.bestBid());
    }

    @Test
    void cancel_snapshotShowsLevelRemoved() {
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);
        submit(cancel(2, 1), 1);

        List<Observed> obs = snaps.awaitAtLeast(2, 1000);
        Observed s = obs.get(1);

        assertEquals(0, s.bidLevelCount());
        assertEquals(-1L, s.bestBid());
    }

    @Test
    void rejectStillPublishesSnapshot_bookUnchanged() {
        submit(newOrder(1, Side.BUY, 1_000_000, 50), 0);
        submit(newOrder(2, Side.BUY, -5, 50), 1);       // domain-invalid -> rejected
        submit(cancel(3, 999), 2);                      // unknown cancel -> rejected

        List<Observed> obs = snaps.awaitAtLeast(3, 1000);
        assertEquals(3, obs.size());                    // rejects publish too

        for (Observed s : obs) {                        // book never changed after the first
            assertEquals(1, s.bidLevelCount());
            assertEquals(1_000_000L, s.bidPrices()[0]);
            assertEquals(50L, s.bidQtys()[0]);
        }
    }

    @Test
    void aggregatesMultipleOrdersAtOneLevel() {
        submit(newOrder(1, Side.BUY, 1_000_000, 30), 0);
        submit(newOrder(2, Side.BUY, 1_000_000, 20), 1);

        List<Observed> obs = snaps.awaitAtLeast(2, 1000);
        Observed s = obs.get(1);

        assertEquals(1, s.bidLevelCount());
        assertEquals(50L, s.bidQtys()[0]);
    }

    @Test
    void depthTruncatesAtMaxLevels() {
        // MAX_DEPTH_LEVELS + 2 distinct bid levels, so the final snapshot must truncate to the
        // top MAX_DEPTH_LEVELS and leave the top of book unaffected.
        final int levels = BookSnapshotEvent.MAX_DEPTH_LEVELS + 2;
        for (int i = 0; i < levels; i++) {
            submit(newOrder(i + 1, Side.BUY, 1_000_000 + i * 100, 5), i);
        }

        List<Observed> obs = snaps.awaitAtLeast(levels, 1000);
        Observed s = obs.get(levels - 1);

        assertEquals(BookSnapshotEvent.MAX_DEPTH_LEVELS, s.bidLevelCount());
        assertEquals(1_000_000L + (levels - 1) * 100L, s.bidPrices()[0]);   // best kept (highest price)
        assertEquals(1_000_000L + (levels - 1) * 100L, s.bestBid());        // top of book unaffected by truncation
    }

    @Test
    void handlerWithoutSnapshotRing_stillProcesses() {
        MatchingEngine e2 = new MatchingEngine();
        MatchingEngineHandler noDepth =
                new MatchingEngineHandler(e2, outbound.getRingBuffer(), () -> TS);
        e2.setExecutionListener(noDepth);

        noDepth.onEvent(newOrder(1, Side.BUY, 1_000_000, 50), 0, true);   // must not NPE

        assertEquals(1_000_000L, e2.getBestBid());
    }
}