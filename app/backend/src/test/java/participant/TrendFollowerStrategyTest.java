package participant;

import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.fill;
import static participant.Fixtures.print;
import static participant.Fixtures.ticks;
import static participant.Fixtures.view;

class TrendFollowerStrategyTest {

    private static TrendFollowerStrategy trend(long maxPosition) {
        TrendFollowerStrategy.Config d = TrendFollowerStrategy.Config.defaults(OPEN);
        TrendFollowerStrategy.Config c = new TrendFollowerStrategy.Config(OPEN, d.fastHalfLife(), d.slowHalfLife(),
                d.signalTicks(), d.warmupTrades(), d.qty(), d.throughTicks(), d.minIntervalMillis(),
                d.maxIntervalMillis(), new RiskLimits(OPEN, 20, maxPosition));
        return new TrendFollowerStrategy(c, new Bots.IdSequence(Bots.Owner.TREND), new SplittableRandom(7));
    }

    private static void tape(TrendFollowerStrategy t, int from, int to) {
        int step = from <= to ? 1 : -1;
        for (int i = from; i != to + step; i += step) t.onExec(print(OPEN + ticks(i)));
    }

    @Test
    void itDoesNothingBeforeTheMarketHasTraded() {
        TrendFollowerStrategy t = trend(1_500);
        assertEquals(TrendFollowerStrategy.Regime.FLAT, t.regime());
        assertTrue(t.onTick(view(OPEN - ticks(1), OPEN + ticks(1), -1), 1_000).isEmpty());
    }

    @Test
    void aRisingTapeBuysWithAnIocThroughTheOffer() {
        TrendFollowerStrategy t = trend(1_500);
        tape(t, 1, 20);
        assertEquals(TrendFollowerStrategy.Regime.UP, t.regime());

        long ask = OPEN + ticks(21);
        List<Action> out = t.onTick(view(OPEN + ticks(19), ask, OPEN + ticks(20)), 1_000);
        assertEquals(1, out.size());
        Action a = out.get(0);
        assertEquals(Side.BUY, a.side());
        assertEquals(TimeInForce.IOC, a.tif());
        assertEquals(ask + ticks(2), a.priceUnits());
        assertEquals(50, a.qty());

        assertTrue(t.onTick(view(OPEN + ticks(19), ask, OPEN + ticks(20)), 1_100).isEmpty(), "waits its interval");
    }

    @Test
    void aFallingTapeSellsThroughTheBid() {
        TrendFollowerStrategy t = trend(1_500);
        tape(t, -1, -20);
        assertEquals(TrendFollowerStrategy.Regime.DOWN, t.regime());
        long bid = OPEN - ticks(21);
        Action a = t.onTick(view(bid, OPEN - ticks(19), OPEN - ticks(20)), 1_000).get(0);
        assertEquals(Side.SELL, a.side());
        assertEquals(bid - ticks(2), a.priceUnits());
    }

    @Test
    void whenTheTrendFadesItUnwindsItsPosition() {
        TrendFollowerStrategy t = trend(1_500);
        tape(t, 1, 20);
        Action buy = t.onTick(view(OPEN + ticks(19), OPEN + ticks(21), OPEN + ticks(20)), 1_000).get(0);
        t.onExec(fill(buy.clOrdId(), 9L, Side.BUY, OPEN + ticks(21), 50, 0));
        assertEquals(50, t.ownForTest().position());

        for (int i = 0; i < 300; i++) t.onExec(print(OPEN + ticks(21)));   // the averages converge
        assertEquals(TrendFollowerStrategy.Regime.FLAT, t.regime());
        Action sell = t.onTick(view(OPEN + ticks(20), OPEN + ticks(22), OPEN + ticks(21)), 10_000).get(0);
        assertEquals(Side.SELL, sell.side());
        assertEquals(50, sell.qty());
    }

    @Test
    void atThePositionLimitItStopsAdding() {
        TrendFollowerStrategy t = trend(50);
        tape(t, 1, 20);
        Action buy = t.onTick(view(OPEN + ticks(19), OPEN + ticks(21), OPEN + ticks(20)), 1_000).get(0);
        t.onExec(fill(buy.clOrdId(), 9L, Side.BUY, OPEN + ticks(21), 50, 0));
        assertTrue(t.onTick(view(OPEN + ticks(19), OPEN + ticks(21), OPEN + ticks(20)), 10_000).isEmpty());
    }

    @Test
    void itNeverPricesAboveTheBand() {
        TrendFollowerStrategy t = trend(1_500);
        tape(t, 1, 20);
        RiskLimits r = new RiskLimits(OPEN, 20, 1_500);
        Action a = t.onTick(view(r.highest() - ticks(2), r.highest(), r.highest() - ticks(1)), 1_000).get(0);
        assertEquals(r.highest(), a.priceUnits(), "capped at the band edge");
    }
}
