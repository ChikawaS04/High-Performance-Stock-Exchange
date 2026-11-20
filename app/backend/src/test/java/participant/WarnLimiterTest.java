package participant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarnLimiterTest {

    private long now;

    @Test
    void theFirstLogsThenAtMostOnePerIntervalWithTheCountHeldBack() {
        WarnLimiter w = new WarnLimiter(600_000, () -> now);
        assertEquals(0, w.occurrence());
        now += 1_000;
        assertEquals(-1, w.occurrence());
        now += 1_000;
        assertEquals(-1, w.occurrence());
        now += 598_000;   // exactly the interval after the first
        assertEquals(2, w.occurrence());
        now += 1;
        assertEquals(-1, w.occurrence());
    }

    @Test
    void resetMakesTheNextOneLog() {
        WarnLimiter w = new WarnLimiter(60_000, () -> now);
        w.occurrence();
        w.occurrence();
        w.reset();
        assertEquals(0, w.occurrence());
    }
}
