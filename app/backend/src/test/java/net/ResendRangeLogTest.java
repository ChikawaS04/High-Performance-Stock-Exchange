package net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The R5 WARN is logged once, then at most once a minute with a suppressed count (P16-7, amendment 3). */
class ResendRangeLogTest {

    @Test
    void oneLineThenAtMostOneAMinute() {
        ResendRangeLog log = new ResendRangeLog();
        String first = log.onBelowEarliest(0, 1, 500, "client");
        assertNotNull(first);
        assertTrue(first.contains("earliest available seq 500"), first);
        for (int i = 1; i < 100; i++) {
            assertNull(log.onBelowEarliest(i * 100L, 1, 500, "client"), "request " + i);
        }
        String next = log.onBelowEarliest(ResendRangeLog.WARN_INTERVAL_MS, 1, 500, "client");
        assertNotNull(next);
        assertTrue(next.contains("99 such request(s) not logged"), next);
    }
}
