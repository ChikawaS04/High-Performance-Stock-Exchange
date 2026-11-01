package engine;

import event.CapturingExecutionHandler;
import event.CapturingSnapshotHandler;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P15-2 determinism (SRS §3.3, §5.8): two engines fed the same command sequence produce identical
 * execution streams, field for field, and identical book snapshots. The second run starts after
 * the first has finished in the same JVM, so any surviving static (a shared trade id counter) or
 * clock read would make the streams diverge.
 *
 * The script exercises every path that publishes: rests, partial and full crosses, a multi-level
 * walk, iceberg reloads, an incoming peg against the pool, a lit order crossing the mid into the
 * pool, the post-event pool cross, IOC expiry, FOK expiry and FOK fill, cancel, unknown-cancel
 * reject, duplicate-id reject and validation reject.
 */
class MatchingEngineDeterminismTest {

    private static final long TIMEOUT_MS = 2_000L;

    private static final long P99     =   990_000L;  // $99.00
    private static final long P100    = 1_000_000L;  // $100.00
    private static final long P100_04 = 1_000_400L;  // $100.04
    private static final long P100_05 = 1_000_500L;  // $100.05

    /** Result of one run: the captured EXEC and BOOK streams plus the engine-owned counters. */
    private record Run(List<CapturingExecutionHandler.Observed> execs,
                       List<CapturingSnapshotHandler.Observed> books,
                       long nextTradeId,
                       long lastSeq) { }

    // --- command factories (each call returns a fresh event; slots are never shared) ---

    private static OrderEvent order(long id, Side side, OrdType ordType, TimeInForce tif,
                                    long price, long qty, long maxFloor) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.NEW_ORDER;
        e.orderId = id;
        e.side = side;
        e.ordType = ordType;
        e.tif = tif;
        e.price = price;
        e.quantity = qty;
        e.maxFloor = maxFloor;
        e.originalOrderId = -1L;
        return e;
    }

    private static OrderEvent limit(long id, Side side, TimeInForce tif, long price, long qty) {
        return order(id, side, OrdType.LIMIT, tif, price, qty, 0);
    }

    private static OrderEvent iceberg(long id, Side side, long price, long qty, long maxFloor) {
        return order(id, side, OrdType.LIMIT, TimeInForce.GTC, price, qty, maxFloor);
    }

    private static OrderEvent peg(long id, Side side, TimeInForce tif, long qty) {
        return order(id, side, OrdType.PEG_MID, tif, -1L, qty, 0);
    }

    private static OrderEvent cancel(long clOrdId, long origId) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.CANCEL_ORDER;
        e.orderId = clOrdId;
        e.price = -1L;
        e.quantity = -1L;
        e.originalOrderId = origId;
        return e;
    }

    /** The scripted day. Timestamps are distinct and increasing, as the gateway would stamp them. */
    private static List<OrderEvent> script() {
        List<OrderEvent> s = new ArrayList<>();
        s.add(limit(1, Side.BUY, TimeInForce.GTC, P100, 100));          // rests: best bid $100.00
        s.add(limit(2, Side.SELL, TimeInForce.GTC, P100_05, 100));      // rests: ask $100.05
        s.add(iceberg(3, Side.SELL, P100_04, 300, 50));                 // iceberg ask $100.04, tip 50
        s.add(peg(4, Side.SELL, TimeInForce.GTC, 60));                  // peg ask at mid $100.02
        s.add(limit(5, Side.BUY, TimeInForce.GTC, P100_05, 200));       // crosses mid: pool 60, then iceberg reloads
        s.add(peg(6, Side.BUY, TimeInForce.GTC, 30));                   // rests in peg bid pool
        s.add(peg(7, Side.SELL, TimeInForce.IOC, 20));                  // incoming peg fills the bid pool
        s.add(limit(8, Side.SELL, TimeInForce.IOC, P99, 50));           // crosses mid: rest of peg 6, then bid 1
        s.add(limit(9, Side.BUY, TimeInForce.FOK, P100_04, 500));       // FOK short: expires, book unchanged
        s.add(limit(10, Side.BUY, TimeInForce.FOK, P100_04, 100));      // FOK fills from the iceberg
        s.add(limit(11, Side.SELL, TimeInForce.IOC, P100_05, 10));      // IOC that cannot trade: expires
        s.add(cancel(12, 1));                                           // cancels the rest of bid 1
        s.add(cancel(13, 999));                                         // unknown: rejected
        s.add(limit(2, Side.BUY, TimeInForce.GTC, P99, 10));            // duplicate id of resting ask 2
        s.add(limit(14, Side.BUY, TimeInForce.GTC, 1_000_050L, 10));    // off-tick: validation reject
        s.add(peg(18, Side.BUY, TimeInForce.GTC, 25));                  // no lit bid, no mid: rests inactive
        s.add(peg(15, Side.SELL, TimeInForce.GTC, 40));                 // later arrival, rests inactive
        s.add(limit(16, Side.BUY, TimeInForce.GTC, P99, 20));           // creates the mid: pools cross
        s.add(limit(17, Side.SELL, TimeInForce.GTC, P99, 10));          // lit sell hits bid 16
        long ts = 1_700_000_000_000_000_000L;
        for (OrderEvent e : s) {
            e.timestamp = ts;
            ts += 1_000L;
        }
        return s;
    }

    private static Run run(List<OrderEvent> commands) {
        MatchingEngine engine = new MatchingEngine();
        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        CapturingSnapshotHandler books = new CapturingSnapshotHandler();
        OutboundPipeline outbound = new OutboundPipeline();
        SnapshotPipeline snapshots = new SnapshotPipeline();
        outbound.handleEventsWith(execs);
        snapshots.handleEventsWith(books);
        outbound.start();
        snapshots.start();
        try {
            MatchingEngineHandler handler =
                    new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
            engine.setExecutionListener(handler);
            for (OrderEvent e : commands) {
                handler.onCommand(e);
            }
            int execCount = (int) handler.getLastSeq();
            List<CapturingExecutionHandler.Observed> e = execs.awaitAtLeast(execCount, TIMEOUT_MS);
            List<CapturingSnapshotHandler.Observed> b = books.awaitAtLeast(commands.size(), TIMEOUT_MS);
            assertEquals(execCount, e.size(), "every published EXEC was delivered");
            assertEquals(commands.size(), b.size(), "one BOOK per command");
            return new Run(e, b, engine.getNextTradeId(), handler.getLastSeq());
        } finally {
            snapshots.shutdown();
            outbound.shutdown();
        }
    }

    @Test
    void sameCommands_produceIdenticalExecutionStreamsAndSnapshots() {
        Run a = run(script());
        Run b = run(script());

        // Execution events: record equality covers all fourteen fields, seq to timestamp.
        assertEquals(a.execs().size(), b.execs().size());
        for (int i = 0; i < a.execs().size(); i++) {
            assertEquals(a.execs().get(i), b.execs().get(i), "EXEC " + i);
        }

        // Book snapshots: compared field by field (record equality would compare arrays by reference).
        assertEquals(a.books().size(), b.books().size());
        for (int i = 0; i < a.books().size(); i++) {
            CapturingSnapshotHandler.Observed x = a.books().get(i);
            CapturingSnapshotHandler.Observed y = b.books().get(i);
            assertArrayEquals(x.bidPrices(), y.bidPrices(), "BOOK " + i + " bidPrices");
            assertArrayEquals(x.bidQtys(), y.bidQtys(), "BOOK " + i + " bidQtys");
            assertArrayEquals(x.askPrices(), y.askPrices(), "BOOK " + i + " askPrices");
            assertArrayEquals(x.askQtys(), y.askQtys(), "BOOK " + i + " askQtys");
            assertEquals(x.bidLevelCount(), y.bidLevelCount(), "BOOK " + i + " bidLevelCount");
            assertEquals(x.askLevelCount(), y.askLevelCount(), "BOOK " + i + " askLevelCount");
            assertEquals(x.bestBid(), y.bestBid(), "BOOK " + i + " bestBid");
            assertEquals(x.bestAsk(), y.bestAsk(), "BOOK " + i + " bestAsk");
            assertEquals(x.lastSeq(), y.lastSeq(), "BOOK " + i + " lastSeq");
            assertEquals(x.timestamp(), y.timestamp(), "BOOK " + i + " timestamp");
        }

        assertEquals(a.nextTradeId(), b.nextTradeId());
        assertEquals(a.lastSeq(), b.lastSeq());
    }

    @Test
    void script_coversEveryPublishingPath() {
        Run a = run(script());

        Set<ExecutionEventType> seen = EnumSet.noneOf(ExecutionEventType.class);
        long fills = 0;
        long rejects = 0;
        boolean sellAggressorAtMid = false;
        for (CapturingExecutionHandler.Observed o : a.execs()) {
            seen.add(o.eventType());
            boolean fill = o.eventType() == ExecutionEventType.ORDER_FILLED
                    || o.eventType() == ExecutionEventType.ORDER_PARTIALLY_FILLED;
            if (fill) {
                fills++;
                assertNotNull(o.aggressorSide());
                // the pool cross at mid $99.52 names the later-arriving peg sell 15 as aggressor
                if (o.orderId() == 15 && o.passiveOrderId() == 18) {
                    assertEquals(Side.SELL, o.aggressorSide());
                    sellAggressorAtMid = true;
                }
            }
            if (o.eventType() == ExecutionEventType.ORDER_REJECTED) rejects++;
        }
        assertTrue(seen.containsAll(EnumSet.of(
                ExecutionEventType.ORDER_ACCEPTED, ExecutionEventType.ORDER_FILLED,
                ExecutionEventType.ORDER_PARTIALLY_FILLED, ExecutionEventType.ORDER_CANCELLED,
                ExecutionEventType.ORDER_REJECTED, ExecutionEventType.ORDER_EXPIRED)), seen.toString());
        assertEquals(3, rejects);                 // unknown cancel, duplicate id, off-tick
        assertTrue(fills >= 8, "fills=" + fills);
        assertTrue(sellAggressorAtMid, "the peg-pool cross ran");

        // Every EXEC carries its command's timestamp; seq is gap-free from 1.
        List<OrderEvent> commands = script();
        Set<Long> commandStamps = new java.util.HashSet<>();
        for (OrderEvent e : commands) commandStamps.add(e.timestamp);
        for (int i = 0; i < a.execs().size(); i++) {
            assertEquals(i + 1, a.execs().get(i).seq());
            assertTrue(commandStamps.contains(a.execs().get(i).timestamp()));
        }
        for (int i = 0; i < a.books().size(); i++) {
            assertEquals(commands.get(i).timestamp, a.books().get(i).timestamp());
        }
    }
}
