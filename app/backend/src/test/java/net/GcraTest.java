package net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** One token bucket (P16-9): sustained rate, burst, refill, and a negative clock origin. */
class GcraTest {

    private static final long SECOND = 1_000_000_000L;

    /** Frames admitted out of {@code n} arriving at {@code now} at once, spending as it goes. */
    private static int admitBurst(Gcra g, long[] tat, long now, int n) {
        int admitted = 0;
        for (int i = 0; i < n; i++) {
            if (g.conforms(tat[0], now)) {
                tat[0] = g.spend(tat[0], now);
                admitted++;
            }
        }
        return admitted;
    }

    @Test
    void aFullBucketAdmitsExactlyTheBurstAtOnce() {
        Gcra g = new Gcra(10, 30);
        long[] tat = {0};
        assertEquals(30, admitBurst(g, tat, 0, 100));
    }

    @Test
    void aSenderWithinTheRateIsNeverRefused() {
        Gcra g = new Gcra(10, 30);
        long[] tat = {0};
        for (int i = 0; i < 1_000; i++) {
            long now = i * (SECOND / 10);
            assertTrue(g.conforms(tat[0], now), "frame " + i);
            tat[0] = g.spend(tat[0], now);
        }
    }

    @Test
    void anEmptyBucketRefillsAtTheRate() {
        Gcra g = new Gcra(10, 30);
        long[] tat = {0};
        admitBurst(g, tat, 0, 30);
        assertEquals(0, admitBurst(g, tat, 0, 1));
        assertEquals(1, admitBurst(g, tat, SECOND / 10, 5), "one token after 100 ms");
        assertEquals(10, admitBurst(g, tat, SECOND / 10 + SECOND, 50), "ten more a second later");
        assertEquals(30, admitBurst(g, tat, 100 * SECOND, 100), "full again after a long idle, never more");
    }

    @Test
    void aNegativeClockOriginIsHarmless() {
        Gcra g = new Gcra(20, 100);
        long origin = Long.MIN_VALUE + 5 * SECOND;
        long[] tat = {origin};
        assertEquals(100, admitBurst(g, tat, origin, 500));
        assertEquals(20, admitBurst(g, tat, origin + SECOND, 500));
    }

    @Test
    void parametersAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new Gcra(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Gcra(10, 0));
    }
}
