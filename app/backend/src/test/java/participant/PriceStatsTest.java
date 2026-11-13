package participant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.ticks;

class PriceStatsTest {

    @Test
    void theAveragesStartAtTheOpen() {
        PriceStats s = new PriceStats(OPEN, 8, 40, 20);
        assertEquals(OPEN, s.fast(), 0.0);
        assertEquals(OPEN, s.slow(), 0.0);
        assertEquals(0.0, s.volTicks(), 0.0);
        assertEquals(-1L, s.lastTrade());
    }

    @Test
    void aHalfLifeHalvesTheWeightOfOldPrices() {
        PriceStats s = new PriceStats(OPEN, 1, 2, 20);
        s.onTrade(OPEN + ticks(10));
        assertEquals(OPEN + ticks(5), s.fast(), 1e-6, "half-life 1: one sample moves it halfway");
        s.onTrade(OPEN + ticks(10));
        assertEquals(OPEN + ticks(10) - ticks(10) * 0.5, s.slow(), 1e-6, "half-life 2: two samples move it halfway");
    }

    @Test
    void aRisingTapePutsTheFastAverageAboveTheSlow() {
        PriceStats s = new PriceStats(OPEN, 8, 40, 20);
        for (int i = 1; i <= 20; i++) s.onTrade(OPEN + ticks(i));
        assertTrue(s.fast() > s.slow());
        assertEquals(20, s.trades());
        assertTrue(s.volTicks() > 0.4, "one-tick steps register as volatility, saw " + s.volTicks());
    }

    @Test
    void nonFillReportsAndNonPositivePricesAreIgnored() {
        PriceStats s = new PriceStats(OPEN, 8, 40, 20);
        s.onExec(Fixtures.accepted(1, model.Side.BUY, OPEN + ticks(50), 10));
        s.onTrade(-1);
        assertEquals(0, s.trades());
    }
}
