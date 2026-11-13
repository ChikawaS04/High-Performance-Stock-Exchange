package participant;

import model.Prices;
import model.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskLimitsTest {

    @Test
    void theBandIsWholeTicksEitherSideOfTheOpen() {
        RiskLimits r = new RiskLimits(1_498_700L, 20, 1_000);   // $149.87: 20% is 299,740 units, not on the tick
        assertEquals(2_997, r.bandTicks());
        assertEquals(1_498_700L - 299_700L, r.lowest());
        assertEquals(1_498_700L + 299_700L, r.highest());
        assertTrue(Prices.isOnTick(r.lowest()) && Prices.isOnTick(r.highest()));
        assertTrue(r.inBand(r.lowest()) && r.inBand(r.highest()));
        assertFalse(r.inBand(r.highest() + Prices.TICK));
        assertFalse(r.inBand(r.lowest() - Prices.TICK));
        assertFalse(r.inBand(1_498_750L), "off the tick");
    }

    @Test
    void thePositionLimitAllowsGrowthUpToTheLimitAndAlwaysAllowsReducing() {
        RiskLimits r = new RiskLimits(1_500_000L, 20, 100);
        assertTrue(r.allows(Side.BUY, 100, 0, 0));
        assertFalse(r.allows(Side.BUY, 101, 0, 0));
        assertFalse(r.allows(Side.BUY, 60, 0, 50), "working quantity counts as if filled");
        assertTrue(r.allows(Side.SELL, 50, 150, 0), "over the limit, selling reduces");
        assertFalse(r.allows(Side.BUY, 1, 150, 0));
    }

    @Test
    void invalidLimitsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RiskLimits(1_500_050L, 20, 100));
        assertThrows(IllegalArgumentException.class, () -> new RiskLimits(1_500_000L, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new RiskLimits(1_500_000L, 20, 0));
    }
}
