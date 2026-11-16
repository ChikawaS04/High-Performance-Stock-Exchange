package participant;

import model.Prices;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    /** The default ladder, inner first: 100 .. 1000 over ten levels, then 1000 for ten more. */
    private static final List<Long> LADDER = List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1_000L,
            1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L);

    @Test
    void theFirstTickLaddersTwentyLevelsEachSideOfTheOpen() {
        MarketMakerStrategy.Config cfg = MarketMakerStrategy.Config.defaults(OPEN);
        MarketMakerStrategy m = maker(cfg);
        List<Action> out = m.onTick(emptyView(), 1_000);

        List<Long> bids = new ArrayList<>();
        List<Long> asks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            bids.add(OPEN - ticks(2 + i));
            asks.add(OPEN + ticks(2 + i));
        }
        assertEquals(bids, side(out, Side.BUY).stream().map(Action::priceUnits).toList());
        assertEquals(asks, side(out, Side.SELL).stream().map(Action::priceUnits).toList());
        assertEquals(LADDER, side(out, Side.BUY).stream().map(Action::qty).toList());
        assertEquals(LADDER, side(out, Side.SELL).stream().map(Action::qty).toList());
        for (Action a : out) {
            assertEquals(TimeInForce.GTC, a.tif());
            assertTrue(Prices.isOnTick(a.priceUnits()));
            assertTrue(cfg.risk().inBand(a.priceUnits()), "inside the band: " + a.priceUnits());
            assertEquals(Bots.Owner.MAKER, Bots.owner(a.clOrdId()));
        }
    }

    @Test
    void theDefaultLadderMatchesTheBookDepthAndItsLimitCoversAWholeSide() {
        MarketMakerStrategy.Config cfg = MarketMakerStrategy.Config.defaults(OPEN);
        assertEquals(event.BookSnapshotEvent.MAX_DEPTH_LEVELS, cfg.levelQtys().length);
        long side = LADDER.stream().mapToLong(Long::longValue).sum();
        assertEquals(15_500, side);
        assertEquals(side + 3_000, cfg.risk().maxPosition());
    }

    @Test
    void theFullLadderStillRestsOnBothSidesAtThreeThousandLong() {
        MarketMakerStrategy m = maker(MarketMakerStrategy.Config.defaults(OPEN));
        List<Action> first = m.onTick(emptyView(), 1_000);
        long innerBid = side(first, Side.BUY).get(0).clOrdId();
        m.onExec(fill(9L, innerBid, Side.SELL, OPEN - ticks(2), 3_000, 0));
        assertEquals(3_000, m.ownForTest().position());

        List<Action> out = m.onTick(emptyView(), 20_000);
        assertEquals(20, side(out, Side.BUY).size(), "3,000 + 15,500 is exactly the limit");
        assertEquals(20, side(out, Side.SELL).size());
    }

    @Test
    void pastTheInventoryRangeOnlyTheOuterLevelsOnTheAddingSideDrop() {
        MarketMakerStrategy m = maker(MarketMakerStrategy.Config.defaults(OPEN));
        List<Action> first = m.onTick(emptyView(), 1_000);
        long innerBid = side(first, Side.BUY).get(0).clOrdId();
        m.onExec(fill(9L, innerBid, Side.SELL, OPEN - ticks(2), 4_500, 0));

        List<Action> out = m.onTick(emptyView(), 20_000);
        // 4,500 + the first 19 levels (14,500) is 19,000, past 18,500: the outer two drop.
        assertEquals(LADDER.subList(0, 18), side(out, Side.BUY).stream().map(Action::qty).toList());
        assertEquals(20, side(out, Side.SELL).size(), "selling reduces the position, so every offer rests");
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

        assertEquals(40, cancels(out).size(), "every quote from the first ladder");
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
        // 150 ticks either side, with the default position limit so only the band trims.
        RiskLimits narrow = new RiskLimits(OPEN, 1, MarketMakerStrategy.Config.defaults(OPEN).risk().maxPosition());
        MarketMakerStrategy m = maker(cfg(narrow, 300));
        List<Action> out = m.onTick(view(-1, -1, narrow.highest()), 1_000);
        assertTrue(side(out, Side.SELL).isEmpty(), "every offer would sit above the band");
        assertEquals(20, side(out, Side.BUY).size());
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
        assertEquals(40, close.size());
        assertEquals(news(out).stream().map(Action::clOrdId).toList(),
                close.stream().map(Action::origClOrdId).toList());
    }
}
