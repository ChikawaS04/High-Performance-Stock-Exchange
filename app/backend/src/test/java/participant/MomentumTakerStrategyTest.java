package participant;

import model.Prices;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure decision-logic tests for the taker. No socket, no threads; seeded id sequence. */
class MomentumTakerStrategyTest {

    private static final long BID = 1_499_000L;
    private static final long ASK = 1_500_000L;

    private MomentumTakerStrategy taker(Side start) {
        // take every 1s, qty 20, 3 ticks through, flip every 5s.
        return new MomentumTakerStrategy(
                new MomentumTakerStrategy.Config(1_000, 20, 3, 5_000, start),
                new Bots.IdSequence(Bots.TAKER_BASE));
    }

    private MarketView book() {
        return new MarketView(BID, ASK, (BID + ASK) / 2, Prices.NA);
    }

    @Test
    void buyLeg_crossesThroughTheAskWithIoc() {
        List<Action> out = taker(Side.BUY).onTick(book(), 1_000L);
        assertEquals(1, out.size());
        Action a = out.get(0);
        assertEquals(Side.BUY, a.side());
        assertEquals(TimeInForce.IOC, a.tif());
        assertEquals(ASK + 3 * Prices.TICK, a.priceUnits());   // lifts 3 levels up
        assertEquals(20L, a.qty());
        assertEquals(Bots.Owner.TAKER, Bots.owner(a.clOrdId()));
    }

    @Test
    void withinTakeInterval_doesNotFireAgain() {
        MomentumTakerStrategy t = taker(Side.BUY);
        t.onTick(book(), 1_000L);
        assertTrue(t.onTick(book(), 1_500L).isEmpty());   // 500ms < 1s interval
    }

    @Test
    void afterFlipWindow_switchesToSellAndHitsTheBid() {
        MomentumTakerStrategy t = taker(Side.BUY);
        t.onTick(book(), 1_000L);          // anchors flip window at t=1000, fires a BUY
        t.onTick(book(), 2_000L);          // another BUY leg
        List<Action> out = t.onTick(book(), 6_000L);   // 5s since flip anchor -> flip to SELL
        assertEquals(Side.SELL, t.dirForTest());
        assertEquals(1, out.size());
        Action a = out.get(0);
        assertEquals(Side.SELL, a.side());
        assertEquals(TimeInForce.IOC, a.tif());
        assertEquals(BID - 3 * Prices.TICK, a.priceUnits());   // hits 3 levels down
    }

    @Test
    void noOpposingLiquidity_doesNothing() {
        MarketView askOnlyEmpty = new MarketView(Prices.NA, Prices.NA, Prices.NA, Prices.NA);
        assertTrue(taker(Side.BUY).onTick(askOnlyEmpty, 1_000L).isEmpty());
    }
}
