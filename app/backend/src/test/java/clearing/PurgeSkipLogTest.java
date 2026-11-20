package clearing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Database retention's skip log (P16-8): one WARN per new reason, at most daily repeats, INFO on clearing. Pure. */
class PurgeSkipLogTest {

    @Test
    void aNewReasonWarnsOnceThenStaysQuietForADay() {
        PurgeSkipLog log = new PurgeSkipLog();
        PurgeSkipLog.Line first = log.onSkip("REPORTER_NOT_RUNNING", "FAILED", 0L);
        assertTrue(first.warn());
        assertTrue(first.text().contains("REPORTER_NOT_RUNNING"), first.text());
        assertNull(log.onSkip("REPORTER_NOT_RUNNING", "FAILED", 60_000L));
        assertNull(log.onSkip("REPORTER_NOT_RUNNING", "FAILED", PurgeSkipLog.REPEAT_INTERVAL_MS - 1));
        PurgeSkipLog.Line repeat = log.onSkip("REPORTER_NOT_RUNNING", "FAILED", PurgeSkipLog.REPEAT_INTERVAL_MS);
        assertTrue(repeat.warn());
        assertTrue(repeat.text().contains("2 not logged"), repeat.text());
    }

    @Test
    void aDifferentReasonWarnsAtOnce() {
        PurgeSkipLog log = new PurgeSkipLog();
        log.onSkip("CHECKPOINT_BEHIND", "x", 0L);
        PurgeSkipLog.Line other = log.onSkip("CLEARING_FAILED", "y", 1L);
        assertTrue(other.warn());
        assertTrue(other.text().contains("CLEARING_FAILED"));
    }

    @Test
    void aPassAfterSkipsLogsOneInfo() {
        PurgeSkipLog log = new PurgeSkipLog();
        assertNull(log.onRun());
        log.onSkip("DATABASE_UNAVAILABLE", "down", 0L);
        log.onSkip("DATABASE_UNAVAILABLE", "down", 1L);
        PurgeSkipLog.Line cleared = log.onRun();
        assertFalse(cleared.warn());
        assertTrue(cleared.text().contains("2 skipped"), cleared.text());
        assertNull(log.onRun());
    }
}
