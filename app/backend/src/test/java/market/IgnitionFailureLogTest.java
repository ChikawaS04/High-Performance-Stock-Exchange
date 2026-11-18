package market;

import org.junit.jupiter.api.Test;

import static market.IgnitionFailureLog.WARN_INTERVAL_MS;
import static org.junit.jupiter.api.Assertions.*;

/** Pure tests of the ignition fetch's log rate limit (P16-3), driven by controlled clock values. */
class IgnitionFailureLogTest {

    @Test
    void theFirstFailureWarnsAtOnceWithItsReason() {
        IgnitionFailureLog log = new IgnitionFailureLog();
        String line = log.onFailure(1_000L, "HTTP 500");
        assertNotNull(line);
        assertTrue(line.contains("HTTP 500"), line);
        assertTrue(line.contains("1 failure(s) in a row, 0 not logged"), line);
    }

    @Test
    void laterFailuresWarnAtMostOncePerTenMinutesWithTheSuppressedCount() {
        IgnitionFailureLog log = new IgnitionFailureLog();
        long t0 = 1_000_000L;
        assertNotNull(log.onFailure(t0, "HTTP 500"));
        for (int i = 1; i <= 9; i++) {
            assertNull(log.onFailure(t0 + i * 60_000L, "HTTP 500"), "minute " + i);
        }
        assertNull(log.onFailure(t0 + WARN_INTERVAL_MS - 1L, "HTTP 500"));
        String line = log.onFailure(t0 + WARN_INTERVAL_MS, "ConnectException");
        assertNotNull(line);
        assertTrue(line.contains("12 failure(s) in a row, 10 not logged"), line);
        assertTrue(line.contains("ConnectException"), line);
        assertNull(log.onFailure(t0 + WARN_INTERVAL_MS + 60_000L, "HTTP 500"), "the interval restarts");
    }

    @Test
    void oneInfoOnRecoveryAndNoneWithoutAFailure() {
        IgnitionFailureLog log = new IgnitionFailureLog();
        assertNull(log.onSuccess(), "nothing to recover from");
        log.onFailure(0L, "HTTP 500");
        log.onFailure(60_000L, "HTTP 500");
        String line = log.onSuccess();
        assertNotNull(line);
        assertTrue(line.contains("after 2 failure(s)"), line);
        assertNull(log.onSuccess(), "only once");
        assertNotNull(log.onFailure(120_000L, "HTTP 500"), "a new run warns at once again");
    }
}
