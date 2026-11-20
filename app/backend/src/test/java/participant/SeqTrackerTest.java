package participant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeqTrackerTest {

    @Test
    void theFirstFrameSetsTheBaselineThenFramesApplyInOrder() {
        SeqTracker<String> t = new SeqTracker<>();
        assertFalse(t.hasBaseline());
        assertEquals(-1, t.lastApplied());
        assertEquals(List.of("a"), t.offer(41, "a"));
        assertEquals(41, t.lastApplied());
        assertEquals(List.of("b"), t.offer(42, "b"));
        assertEquals(43, t.next());
    }

    @Test
    void duplicatesAreDropped() {
        SeqTracker<String> t = new SeqTracker<>();
        t.offer(1, "a");
        t.offer(2, "b");
        assertTrue(t.offer(2, "b").isEmpty());
        assertTrue(t.offer(1, "a").isEmpty());
        assertEquals(3, t.next());
    }

    @Test
    void framesPastAGapWaitAndDrainInSeqOrderOnceItIsFilled() {
        SeqTracker<String> t = new SeqTracker<>();
        t.offer(10, "j");
        assertTrue(t.offer(13, "m").isEmpty());
        assertTrue(t.offer(12, "l").isEmpty());
        assertTrue(t.offer(13, "m").isEmpty(), "a duplicate of a buffered frame");
        assertTrue(t.hasGap());
        assertEquals(2, t.buffered());
        assertEquals(List.of("k", "l", "m"), t.offer(11, "k"));
        assertFalse(t.hasGap());
        assertEquals(14, t.next());
    }

    @Test
    void interleavedReplayAndLiveApplyEachSeqOnce() {
        SeqTracker<Long> t = new SeqTracker<>();
        t.offer(100, 100L);   // last applied before the disconnect
        // live 104..106 arrive first, then the replay 101..106, then live 107
        long[] arrival = {104, 105, 101, 102, 106, 103, 104, 105, 106, 107};
        List<Long> applied = new java.util.ArrayList<>();
        for (long s : arrival) applied.addAll(t.offer(s, s));
        assertEquals(List.of(101L, 102L, 103L, 104L, 105L, 106L, 107L), applied);
        assertFalse(t.hasGap());
    }

    @Test
    void resetStartsANewBaseline() {
        SeqTracker<String> t = new SeqTracker<>();
        t.offer(500, "x");
        t.offer(502, "z");
        t.reset();
        assertFalse(t.hasBaseline());
        assertFalse(t.hasGap());
        assertEquals(List.of("a"), t.offer(3, "a"));
    }

    @Test
    void aSeqBelowOneIsIgnored() {
        SeqTracker<String> t = new SeqTracker<>();
        assertTrue(t.offer(0, "x").isEmpty());
        assertFalse(t.hasBaseline());
    }
}
