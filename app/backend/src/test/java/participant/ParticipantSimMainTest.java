package participant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static participant.Fixtures.OPEN;

class ParticipantSimMainTest {

    private static final long NOW = Bots.ID_EPOCH_MILLIS + 1_000L;

    private static BotSettings settings(Map<String, String> env) {
        return BotSettings.from(new String[0], env, () -> 42L);
    }

    @Test
    void allFiveBotsAreBuiltInOrderEachInItsOwnBlock() {
        List<ParticipantSimMain.Bot> b = ParticipantSimMain.bots(settings(Map.of()), OPEN, NOW);
        assertEquals(List.of("MAKER", "TREND", "REVERT", "NOISE", "INST"),
                b.stream().map(x -> x.strategy().name()).toList());
        assertEquals(List.of(Bots.Owner.MAKER, Bots.Owner.TREND, Bots.Owner.REVERT, Bots.Owner.NOISE, Bots.Owner.INST),
                b.stream().map(x -> x.strategy().owner()).toList());
    }

    @Test
    void aSubsetCanBeChosenAndAnUnknownNameIsRefused() {
        assertEquals(List.of("NOISE"), ParticipantSimMain.bots(settings(Map.of("BOTS_ENABLED", "noise")), OPEN, NOW)
                .stream().map(x -> x.strategy().name()).toList());
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of("BOTS_ENABLED", "whale")));
    }

    @Test
    void theDevelopmentDefaultsBuildTheSameLimitsAsBefore() {
        List<ParticipantSimMain.Bot> b = ParticipantSimMain.bots(settings(Map.of()), OPEN, NOW);
        assertEquals(List.of(18_500L, 1_500L, 2_000L, 1_000L, 40_000L),
                b.stream().map(x -> x.risk().maxPosition()).toList());
        for (ParticipantSimMain.Bot x : b) {
            assertEquals(RiskLimits.DEFAULT_BAND_PERCENT, x.risk().bandPercent());
            assertEquals(OPEN, x.risk().openUnits());
        }
        assertArrayEquals(MarketMakerStrategy.Config.defaultLevelQtys(),
                MarketMakerStrategy.Config.levelQtys(settings(Map.of()).makerLevels()));
    }

    @Test
    void productionSettingsReachTheStrategies() {
        List<ParticipantSimMain.Bot> b = ParticipantSimMain.bots(settings(Map.of(
                "BOTS_MAKER_LEVELS", "10", "BOTS_MAKER_MAX_POSITION", "8500", "BOTS_INST_MAX_POSITION", "10000",
                "BOTS_BAND_PERCENT", "15")), OPEN, NOW);
        assertEquals(8_500, b.get(0).risk().maxPosition());
        assertEquals(10_000, b.get(4).risk().maxPosition());
        assertEquals(15, b.get(2).risk().bandPercent());
        // A 10-level ladder: ten bids and ten asks on the first tick.
        assertEquals(20, b.get(0).strategy().onTick(MarketView.empty(), 1_000).size());
    }

    @Test
    void speedScalesEveryInterval() {
        assertEquals(500L, ParticipantSimMain.scaled(1_000, 2.0));
        assertEquals(2_000L, ParticipantSimMain.scaled(1_000, 0.5));
        assertEquals(1L, ParticipantSimMain.scaled(1, 100.0), "never zero");
    }
}
