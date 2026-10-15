package participant;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit/conversion tests for the market-open anchor. No network. */
class OpenPriceSourceTest {

    @Test
    void openUnits_roundsToCentThenScalesToUnits() {
        assertEquals(1_498_000L, OpenPriceSource.openUnits(149.80).getAsLong());
        assertEquals(1_500_000L, OpenPriceSource.openUnits(150.00).getAsLong());
        // Extra precision rounds at the cent, like the frontend's toFixed(2).
        assertEquals(1_498_100L, OpenPriceSource.openUnits(149.805).getAsLong());
    }

    @Test
    void openUnits_rejectsNonPositiveAndNonFinite() {
        assertFalse(OpenPriceSource.openUnits(0.0).isPresent());
        assertFalse(OpenPriceSource.openUnits(-5.0).isPresent());
        assertFalse(OpenPriceSource.openUnits(Double.NaN).isPresent());
        assertFalse(OpenPriceSource.openUnits(Double.POSITIVE_INFINITY).isPresent());
    }

    @Test
    void parseSnapshot_readsDailyBarOpen() throws Exception {
        OpenPriceSource src = new OpenPriceSource();
        OptionalLong u = src.parseSnapshot("{\"dailyBar\":{\"o\":149.8},\"latestTrade\":{\"p\":150.1}}");
        assertTrue(u.isPresent());
        assertEquals(1_498_000L, u.getAsLong());
    }

    @Test
    void parseSnapshot_missingOrUnusableOpen_isEmpty() throws Exception {
        OpenPriceSource src = new OpenPriceSource();
        assertFalse(src.parseSnapshot("{\"latestTrade\":{\"p\":150.1}}").isPresent());
        assertFalse(src.parseSnapshot("{\"dailyBar\":{\"o\":0}}").isPresent());
        assertFalse(src.parseSnapshot("{\"dailyBar\":{}}").isPresent());
    }
}
