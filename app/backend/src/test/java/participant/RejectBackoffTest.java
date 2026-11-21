package participant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The back-off after the venue's seq-less refusals (P16-9), as pure state on a caller's clock. */
class RejectBackoffTest {

    private static void refuse(RejectBackoff b, int n) {
        for (int i = 0; i < n; i++) b.onVenueRefusal();
    }

    @Test
    void fewerThanThreeRefusalsInARowDoNotPause() {
        RejectBackoff b = new RejectBackoff(30_000);
        refuse(b, 2);
        assertFalse(b.holdsTick(0));
        b.onAccepted();
        refuse(b, 2);
        assertFalse(b.holdsTick(1), "an accepted order in between resets the run");
    }

    @Test
    void threeInARowPauseAndThePauseDoublesUpToTheCapWhileTheVenueStillRefuses() {
        RejectBackoff b = new RejectBackoff(30_000);
        refuse(b, 3);
        long now = 1_000_000;
        assertTrue(b.holdsTick(now));
        assertTrue(b.takeEpisodeStarted(), "one WARN for the episode");
        assertEquals(1_000, b.delayMillis());
        long[] expected = {2_000, 4_000, 8_000, 16_000, 30_000, 30_000};
        for (long next : expected) {
            assertTrue(b.holdsTick(now + b.delayMillis() - 1));
            assertFalse(b.takeProbe(), "no probe before the pause ends");
            now += b.delayMillis();
            assertTrue(b.holdsTick(now));
            assertTrue(b.takeProbe(), "one probe when it ends");
            assertFalse(b.takeProbe(), "only one");
            assertTrue(b.holdsTick(now + 1), "held while the probe is out");
            assertFalse(b.takeProbe());
            assertFalse(b.onProbe(true, now), "still STOP");
            assertEquals(next, b.delayMillis());
        }
        assertFalse(b.takeEpisodeStarted(), "no second WARN while the episode lasts");
    }

    @Test
    void aProbeShowingTheDiskOutOfStopResumesAtTheSameLevel() {
        RejectBackoff b = new RejectBackoff(30_000);
        refuse(b, 3);
        assertTrue(b.holdsTick(0));
        assertTrue(b.holdsTick(1_000));
        assertTrue(b.takeProbe());
        assertTrue(b.onProbe(false, 1_000), "OK or LOW: resume");
        assertFalse(b.holdsTick(1_001));
        assertEquals(1_000, b.delayMillis(), "the level is kept, so a flapping stop backs off further");
        refuse(b, 3);
        assertTrue(b.holdsTick(1_002));
        assertEquals(2_000, b.delayMillis());
        assertTrue(b.takeEpisodeStarted(), "the first pause's flag, never taken above");
        assertFalse(b.takeEpisodeStarted(), "the second pause belongs to the same episode: no new flag");
    }

    @Test
    void anAcceptedOrderResetsEverything() {
        RejectBackoff b = new RejectBackoff(30_000);
        refuse(b, 3);
        assertTrue(b.holdsTick(0));
        b.takeEpisodeStarted();
        b.onAccepted();
        assertFalse(b.holdsTick(1));
        assertFalse(b.paused());
        refuse(b, 3);
        assertTrue(b.holdsTick(2));
        assertEquals(1_000, b.delayMillis(), "from the start again");
        assertTrue(b.takeEpisodeStarted(), "a new episode");
        assertEquals(6, b.refusals());
    }

    @Test
    void aProbeAnswerAfterAnAcceptIsIgnored() {
        RejectBackoff b = new RejectBackoff(30_000);
        refuse(b, 3);
        b.holdsTick(0);
        b.holdsTick(1_000);
        assertTrue(b.takeProbe());
        b.onAccepted();
        assertFalse(b.onProbe(true, 1_000));
        assertFalse(b.paused(), "the accept won");
    }

    @Test
    void theCapIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> new RejectBackoff(999));
        RejectBackoff b = new RejectBackoff(1_000);
        refuse(b, 3);
        b.holdsTick(0);
        b.holdsTick(1_000);
        b.takeProbe();
        b.onProbe(true, 1_000);
        assertEquals(1_000, b.delayMillis(), "never above the cap");
    }
}
