package participant;

import model.Prices;
import model.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.cancels;
import static participant.Fixtures.emptyView;
import static participant.Fixtures.fill;
import static participant.Fixtures.news;
import static participant.Fixtures.side;
import static participant.Fixtures.ticks;
import static participant.Fixtures.view;

/** The maker's P16-5 settings: anchor pull, requote gap, a shorter ladder, behaviour at the limit. */
class MarketMakerProductionTest {

    private static MarketMakerStrategy.Config cfg(int levels, long maxPos, long gap, int pullMax, int divisor) {
        MarketMakerStrategy.Config d = MarketMakerStrategy.Config.defaults(OPEN);
        return new MarketMakerStrategy.Config(OPEN, d.baseHalfSpreadTicks(), MarketMakerStrategy.Config.levelQtys(levels),
                d.volMultiplier(), d.requoteMoveTicks(), d.requoteIntervalMillis(), d.skewClipQty(), d.maxSkewTicks(),
                new RiskLimits(OPEN, 20, maxPos), gap, pullMax, divisor);
    }

    private static MarketMakerStrategy maker(MarketMakerStrategy.Config c) {
        return new MarketMakerStrategy(c, new Bots.IdSequence(Bots.Owner.MAKER));
    }

    private static MarketView lastAt(long px) {
        return view(Prices.NA, Prices.NA, px);
    }

    @Test
    void thePullIsOffByDefault() {
        MarketMakerStrategy m = maker(MarketMakerStrategy.Config.defaults(OPEN));
        m.onTick(lastAt(OPEN + ticks(400)), 1_000);
        assertEquals(0, m.pullTicks());
    }

    @Test
    void thePullLeansTowardTheAnchorAndIsBounded() {
        MarketMakerStrategy m = maker(cfg(10, 8_500, 0, 5, 20));
        m.onTick(lastAt(OPEN), 1_000);
        assertEquals(0, m.pullTicks(), "no pull at the anchor");

        m.onTick(lastAt(OPEN - ticks(40)), 2_000);
        assertEquals(2, m.pullTicks(), "40 ticks below: 2 ticks up");

        m.onTick(lastAt(OPEN - ticks(19)), 3_000);
        assertEquals(0, m.pullTicks(), "less than one divisor away: none");

        List<Action> out = m.onTick(lastAt(OPEN + ticks(400)), 4_000);
        assertEquals(-5, m.pullTicks(), "400 ticks above: clamped at 5 ticks down");
        long ref = OPEN + ticks(400);
        assertEquals(ref - ticks(5) - ticks(2), side(out, Side.BUY).get(0).priceUnits());
        assertEquals(ref - ticks(5) + ticks(2), side(out, Side.SELL).get(0).priceUnits());
        for (Action a : news(out)) {
            assertTrue(Prices.isOnTick(a.priceUnits()));
        }

        m.onTick(lastAt(OPEN - ticks(1_000)), 5_000);
        assertEquals(5, m.pullTicks(), "clamped the other way too");
    }

    @Test
    void theMinimumGapDefersARequoteWithoutDroppingIt() {
        MarketMakerStrategy m = maker(cfg(10, 8_500, 1_000, 0, 20));
        assertEquals(20, news(m.onTick(emptyView(), 1_000)).size());
        assertTrue(m.onTick(lastAt(OPEN + ticks(10)), 1_500).isEmpty(), "moved, but inside the gap");
        List<Action> later = m.onTick(lastAt(OPEN + ticks(10)), 2_000);
        assertEquals(20, cancels(later).size());
        assertEquals(20, news(later).size());
        assertEquals(OPEN + ticks(10) - ticks(2), side(later, Side.BUY).get(0).priceUnits());
    }

    @Test
    void aTenLevelLadderRestsTenASide() {
        long[] q = MarketMakerStrategy.Config.levelQtys(10);
        assertEquals(10, q.length);
        long sum = 0;
        for (long x : q) sum += x;
        assertEquals(5_500, sum);
        List<Action> out = maker(cfg(10, 8_500, 0, 0, 20)).onTick(emptyView(), 1_000);
        assertEquals(10, side(out, Side.BUY).size());
        assertEquals(10, side(out, Side.SELL).size());
    }

    @Test
    void atTheLimitOnlyTheReducingSideQuotesAndTheCentreSkews() {
        MarketMakerStrategy m = maker(cfg(10, 8_500, 0, 0, 20));
        List<Action> first = m.onTick(emptyView(), 1_000);
        long innerBid = side(first, Side.BUY).get(0).clOrdId();
        m.onExec(fill(9L, innerBid, Side.SELL, OPEN - ticks(2), 8_500, 0));
        assertEquals(8_500, m.ownForTest().position());

        List<Action> out = m.onTick(emptyView(), 20_000);
        assertTrue(side(out, Side.BUY).isEmpty(), "no bid adds to a full long");
        assertEquals(10, side(out, Side.SELL).size(), "the reducing side keeps its whole ladder");
        assertEquals(OPEN - ticks(10) + ticks(2), side(out, Side.SELL).get(0).priceUnits(), "skewed down 10 ticks");
    }

    @Test
    void aNewOpenMovesOnlyTheAnchor() {
        MarketMakerStrategy.Config c = cfg(10, 8_500, 0, 5, 20);
        MarketMakerStrategy m = maker(c);
        RiskLimits before = c.risk();
        long low = before.lowest();
        long high = before.highest();
        m.onTick(lastAt(OPEN), 1_000);
        assertEquals(0, m.pullTicks());

        m.onAnchor(OPEN + ticks(200));
        assertEquals(OPEN + ticks(200), m.anchor());
        m.onTick(lastAt(OPEN), 2_000);
        assertEquals(5, m.pullTicks(), "the new anchor pulls the centre up");
        assertSame(before, c.risk());
        assertEquals(low, c.risk().lowest(), "the band is unchanged");
        assertEquals(high, c.risk().highest());
        assertEquals(8_500, c.risk().maxPosition());

        m.onAnchor(OPEN + 1);   // off the tick: ignored
        assertEquals(OPEN + ticks(200), m.anchor());
    }
}
