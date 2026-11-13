package participant;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static participant.Fixtures.OPEN;

class ParticipantSimMainTest {

    private static final long NOW = Bots.ID_EPOCH_MILLIS + 1_000L;

    @Test
    void allFiveBotsAreBuiltInOrderEachInItsOwnBlock() {
        List<Strategy> s = ParticipantSimMain.strategies(
                new LinkedHashSet<>(List.of("maker", "trend", "revert", "noise", "inst")), OPEN, 42, 1.0, NOW);
        assertEquals(List.of("MAKER", "TREND", "REVERT", "NOISE", "INST"), s.stream().map(Strategy::name).toList());
        assertEquals(List.of(Bots.Owner.MAKER, Bots.Owner.TREND, Bots.Owner.REVERT, Bots.Owner.NOISE, Bots.Owner.INST),
                s.stream().map(Strategy::owner).toList());
    }

    @Test
    void aSubsetCanBeChosenAndAnUnknownNameIsRefused() {
        assertEquals(List.of("NOISE"),
                ParticipantSimMain.strategies(Set.of("noise"), OPEN, 1, 1.0, NOW).stream().map(Strategy::name).toList());
        assertThrows(IllegalArgumentException.class,
                () -> ParticipantSimMain.strategies(Set.of("whale"), OPEN, 1, 1.0, NOW));
    }

    @Test
    void speedScalesEveryInterval() {
        assertEquals(500L, ParticipantSimMain.scaled(1_000, 2.0));
        assertEquals(2_000L, ParticipantSimMain.scaled(1_000, 0.5));
        assertEquals(1L, ParticipantSimMain.scaled(1, 100.0), "never zero");
    }
}
