package market;

import event.BookSnapshotEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataServiceTest {

    private static BookSnapshotEvent snap(long bestBid, long bestAsk, long ts) {
        BookSnapshotEvent e = new BookSnapshotEvent();
        e.bestBid = bestBid;
        e.bestAsk = bestAsk;
        e.timestamp = ts;
        return e;
    }

    @Test
    void emptyBeforeAnySnapshot() {
        MarketDataService svc = new MarketDataService();
        assertEquals(-1L, svc.getBestBid());
        assertEquals(-1L, svc.getBestAsk());
        assertEquals(-1L, svc.getSpread());
        assertEquals(-1L, svc.getMidpoint());
    }

    @Test
    void twoSidedEvenMidpoint() {
        MarketDataService svc = new MarketDataService();
        svc.onEvent(snap(1_500_000L, 1_501_000L, 111L), 0L, true);

        assertEquals(1_500_000L, svc.getBestBid());
        assertEquals(1_501_000L, svc.getBestAsk());
        assertEquals(1_000L, svc.getSpread());
        assertEquals(1_500_500L, svc.getMidpoint());      // exact
        assertEquals(111L, svc.getQuote().timestamp());
    }

    @Test
    void twoSidedSubPennyMidpointIsExact() {
        MarketDataService svc = new MarketDataService();
        svc.onEvent(snap(1_500_000L, 1_500_500L, 1L), 0L, true);   // $150.00 / $150.05

        assertEquals(500L, svc.getSpread());
        assertEquals(1_500_250L, svc.getMidpoint());      // $150.025, exact in units
    }

    @Test
    void oneCentWideSpreadMidpointIsExact() {
        MarketDataService svc = new MarketDataService();
        svc.onEvent(snap(1_000_000L, 1_000_100L, 1L), 0L, true);   // $100.00 / $100.01

        assertEquals(100L, svc.getSpread());
        assertEquals(1_000_050L, svc.getMidpoint());      // $100.005, exact in units
    }

    @Test
    void bidOnly() {
        MarketDataService svc = new MarketDataService();
        svc.onEvent(snap(1_500_000L, -1L, 5L), 0L, true);

        assertEquals(1_500_000L, svc.getBestBid());
        assertEquals(-1L, svc.getBestAsk());
        assertEquals(-1L, svc.getSpread());
        assertEquals(-1L, svc.getMidpoint());
    }

    @Test
    void askOnly() {
        MarketDataService svc = new MarketDataService();
        svc.onEvent(snap(-1L, 1_501_000L, 5L), 0L, true);

        assertEquals(-1L, svc.getBestBid());
        assertEquals(1_501_000L, svc.getBestAsk());
        assertEquals(-1L, svc.getSpread());
        assertEquals(-1L, svc.getMidpoint());
    }

    @Test
    void latestSnapshotWinsAndEmptyingResetsMetrics() {
        MarketDataService svc = new MarketDataService();

        svc.onEvent(snap(1_500_000L, 1_501_000L, 1L), 0L, true);
        assertEquals(1_000L, svc.getSpread());

        svc.onEvent(snap(1_502_000L, 1_503_000L, 2L), 1L, true);
        assertEquals(1_502_000L, svc.getBestBid());
        assertEquals(1_503_000L, svc.getBestAsk());
        assertEquals(1_000L, svc.getSpread());
        assertEquals(2L, svc.getQuote().timestamp());

        // book drains to empty -> metrics must reset, not linger
        svc.onEvent(snap(-1L, -1L, 3L), 2L, true);
        assertEquals(-1L, svc.getBestBid());
        assertEquals(-1L, svc.getBestAsk());
        assertEquals(-1L, svc.getSpread());
        assertEquals(-1L, svc.getMidpoint());
    }

    @Test
    void quoteTupleIsSelfConsistent() {
        MarketDataService svc = new MarketDataService();
        svc.onEvent(snap(1_500_000L, 1_501_000L, 42L), 0L, true);

        MarketDataService.Quote q = svc.getQuote();
        assertEquals(1_500_000L, q.bestBid());
        assertEquals(1_501_000L, q.bestAsk());
        assertEquals(1_000L, q.spread());
        assertEquals(1_500_500L, q.midpoint());
        assertEquals(42L, q.timestamp());
    }
}