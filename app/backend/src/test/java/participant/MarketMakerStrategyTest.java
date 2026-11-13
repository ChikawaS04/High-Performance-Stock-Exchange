package participant;

import model.Prices;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.cancels;
import static participant.Fixtures.emptyView;
import static participant.Fixtures.fill;
import static participant.Fixtures.news;
import static participant.Fixtures.print;
import static participant.Fixtures.side;
import static participant.Fixtures.ticks;
import static participant.Fixtures.view;

class MarketMakerStrategyTest {

    private static MarketMakerStrategy maker(MarketMakerStrategy.Config cfg) {
        return new MarketMakerStrategy(cfg, new Bots.IdSequence(Bots.Owner.MAKER));
    }

    private static MarketMakerStrategy.Config cfg(RiskLimits risk, long skewClip) {
        MarketMakerStrategy.Config d = MarketMakerStrategy.Config.defaults(OPEN);
        return new MarketMakerStrategy.Config(OPEN, d.baseHalfSpreadTicks(), d.levelQtys(), d.volMultiplier(),
                d.requoteMoveTicks(), d.requoteIntervalMillis(), skewClip, d.maxSkewTicks(), risk);
    }

    @Test
    void theFirstTickLaddersThreeLevelsEachSideOfTheOpen() {
        MarketMakerStrategy m = maker(MarketMakerStrategy.Config.defaults(OPEN));
        List<Action> out = m.onTick(emptyView(), 1_000);

        assertEquals(List.of(OPEN - ticks(2), OPEN - ticks(3), OPEN - ticks(4)),
                side(out, Side.BUY).stream().map(Action::priceUnits).toList());
        assertEquals(List.of(OPEN + ticks(2), OPEN + ticks(3), OPEN + ticks(4)),
                side(out, Side.SELL).stream().map(Action::priceUnits).toList());
        assertEquals(List.of(100L, 200L, 300L), side(out, Side.BUY).stream().map(Action::qty).toList());
        for (Action a : out) {
            assertEquals(TimeInForce.GTC, a.tif());
            assertTrue(Prices.isOnTick(a.priceUnits()));
            assertEquals(Bots.Owner.MAKER, Bots.owner(a.clOrdId()));
        }
    }

    @Test
    void nothingMovedWithinTheIntervalMeansNoRequote() {
        MarketMakerStrategy m = maker(MarketMakerStrategy.Config.defaults(OPEN));
        m.onTick(emptyView(), 1_000);
        assertTrue(m.onTick(view(OPEN - ticks(2), OPEN + ticks(2), -1), 2_000).isEmpty());
    }

    @Test
    void aMoveInTheLastTradeCancelsTheLadderAndReCentresOnIt() {
        MarketMakerStrategy m = maker(MarketMakerStrategy.Config.defaults(OPEN));
        m.onTick(emptyView(), 1_000);
        long last = OPEN + ticks(5);
        m.onExec(print(last));
        List<Action> out = m.onTick(view(OPEN - ticks(2), OPEN + ticks(2), last), 2_000);

        assertEquals(6, cancels(out).size(), "every quote from the first ladder");
        assertEquals(last - ticks(2), side(out, Side.BUY).get(0).priceUnits());
        assertEquals(last + ticks(2), side(out, Side.SELL).get(0).priceUnits());
    }

    @Test
    void anOffTickPegPrintIsNeverUsedAsTheCentre() {
        MarketMakerStrategy m = maker(MarketMakerStrategy.Config.defaults(OPEN));
        List<Action> out = m.onTick(view(OPEN - ticks(1), OPEN + ticks(1), OPEN + 50), 1_000);
        for (Action a : news(out)) assertTrue(Prices.isOnTick(a.priceUnits()), "price " + a.priceUnits());
        assertEquals(OPEN - ticks(2), side(out, Side.BUY).get(0).priceUnits(), "still centred on the open");
    }

    @Test
    void inventoryShiftsTheCentreAgainstThePosition() {
        MarketMakerStrategy m = maker(cfg(new RiskLimits(OPEN, 20, 3_000), 100));
        List<Action> first = m.onTick(emptyView(), 1_000);
        long innerBid = side(first, Side.BUY).get(0).clOrdId();
        m.onExec(fill(9L, innerBid, Side.SELL, OPEN - ticks(2), 100, 0));   // bought 100 at the inner bid
        assertEquals(100, m.ownForTest().position());

        List<Action> out = m.onTick(emptyView(), 20_000);   // stale: refresh
        assertEquals(OPEN - ticks(1) - ticks(2), side(out, Side.BUY).get(0).priceUnits(), "one tick lower when long 100");
        assertEquals(OPEN - ticks(1) + ticks(2), side(out, Side.SELL).get(0).priceUnits());
    }

    @Test
    void quotesOutsideTheBandAreNotSent() {
        RiskLimits narrow = new RiskLimits(OPEN, 1, 3_000);   // 150 ticks either side
        MarketMakerStrategy m = maker(cfg(narrow, 300));
        List<Action> out = m.onTick(view(-1, -1, narrow.highest()), 1_000);
        assertTrue(side(out, Side.SELL).isEmpty(), "every offer would sit above the band");
        assertEquals(3, side(out, Side.BUY).size());
    }

    @Test
    void thePositionLimitTrimsTheLadder() {
        MarketMakerStrategy m = maker(cfg(new RiskLimits(OPEN, 20, 150), 300));
        List<Action> out = m.onTick(emptyView(), 1_000);
        assertEquals(1, side(out, Side.BUY).size(), "100 fits under 150; 100 + 200 does not");
        assertEquals(1, side(out, Side.SELL).size());
    }

    @Test
    void sessionCloseCancelsEveryQuote() {
        MarketMakerStrategy m = maker(MarketMakerStrategy.Config.defaults(OPEN));
        List<Action> out = m.onTick(emptyView(), 1_000);
        List<Action> close = m.onSessionClose();
        assertEquals(6, close.size());
        assertEquals(news(out).stream().map(Action::clOrdId).toList(),
                close.stream().map(Action::origClOrdId).toList());
    }
}
