package net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** At most one log line a minute, carrying the count it held back (P16-9). */
class MinuteLogTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void theFirstIsReportedThenOneAMinuteWithTheSuppressedCount() {
        MinuteLog log = new MinuteLog();
        long t = -5 * SECOND;   // any origin
        assertEquals(0, log.occurrence(t));
        for (int i = 1; i <= 99; i++) assertEquals(-1, log.occurrence(t + i * 100_000_000L));
        assertEquals(99, log.occurrence(t + 60 * SECOND));
        assertEquals(-1, log.occurrence(t + 119 * SECOND));
        assertEquals(1, log.occurrence(t + 120 * SECOND));
        assertEquals(0, log.occurrence(t + 500 * SECOND));
    }
}
