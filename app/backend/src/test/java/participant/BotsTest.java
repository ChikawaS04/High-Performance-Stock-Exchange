package participant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotsTest {

    @Test
    void eachBotOwnsItsOwnBlock() {
        assertEquals(Bots.Owner.MAKER, Bots.owner(100_000_000_000_000L));
        assertEquals(Bots.Owner.TREND, Bots.owner(200_000_000_000_123L));
        assertEquals(Bots.Owner.REVERT, Bots.owner(399_999_999_999_999L));
        assertEquals(Bots.Owner.NOISE, Bots.owner(400_000_000_000_000L));
        assertEquals(Bots.Owner.INST, Bots.owner(599_999_999_999_999L));
    }

    @Test
    void terminalIdsAndAnythingOutsideTheBlocksAreOther() {
        assertEquals(Bots.Owner.OTHER, Bots.owner(1_763_000_000_000L));   // a Date.now() terminal id
        assertEquals(Bots.Owner.OTHER, Bots.owner(-1L));
        assertEquals(Bots.Owner.OTHER, Bots.owner(600_000_000_000_000L));
    }

    @Test
    void everyBotIdIsExactAsAJavaScriptNumber() {
        long largest = Bots.Owner.INST.base() + Bots.BLOCK - 1;
        assertTrue(largest < (1L << 53));
    }

    @Test
    void aLaterRunStartsPastAnEarlierOne() {
        long t0 = Bots.ID_EPOCH_MILLIS + 86_400_000L;
        long first = Bots.runStart(Bots.Owner.NOISE, t0);
        long second = Bots.runStart(Bots.Owner.NOISE, t0 + 60_000);
        assertEquals(Bots.Owner.NOISE.base() + 86_400_000L, first);
        assertEquals(60_000L, second - first, "one id of headroom per millisecond between runs");
        assertEquals(Bots.Owner.NOISE, Bots.owner(second));
    }

    @Test
    void theSequenceWalksUpFromItsStartAndStaysInItsBlock() {
        Bots.IdSequence ids = new Bots.IdSequence(Bots.Owner.MAKER, Bots.Owner.MAKER.base() + Bots.BLOCK - 2);
        assertEquals(Bots.Owner.MAKER.base() + Bots.BLOCK - 2, ids.next());
        assertEquals(Bots.Owner.MAKER.base() + Bots.BLOCK - 1, ids.next());
        assertThrows(IllegalStateException.class, ids::next);
        assertThrows(IllegalArgumentException.class, () -> new Bots.IdSequence(Bots.Owner.MAKER, 5L));
        assertThrows(IllegalArgumentException.class, () -> new Bots.IdSequence(Bots.Owner.OTHER));
    }
}
