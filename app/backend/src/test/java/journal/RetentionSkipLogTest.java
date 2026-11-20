package journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rate-limited skip lines of journal retention (P16-7, R7, amendment 9). */
class RetentionSkipLogTest {

    private static final long HOUR = 3_600_000L;

    @Test
    void aNewReasonIsOneWarnThenQuietUntilTheRepeatInterval() {
        RetentionSkipLog log = new RetentionSkipLog();
        RetentionSkipLog.Line first = log.onSkip(RetentionPlan.Skip.REPORTER_NOT_RUNNING, "BACKING_OFF", 0);
        assertEquals(RetentionSkipLog.Level.WARN, first.level());
        assertTrue(first.text().contains("REPORTER_NOT_RUNNING"));
        for (int h = 1; h < 24; h++) {
            assertNull(log.onSkip(RetentionPlan.Skip.REPORTER_NOT_RUNNING, "BACKING_OFF", h * HOUR), "hour " + h);
        }
        RetentionSkipLog.Line repeat = log.onSkip(RetentionPlan.Skip.REPORTER_NOT_RUNNING, "BACKING_OFF", 24 * HOUR);
        assertEquals(RetentionSkipLog.Level.WARN, repeat.level());
        assertTrue(repeat.text().contains("23 not logged"), repeat.text());
    }

    @Test
    void aChangedReasonWarnsAtOnce() {
        RetentionSkipLog log = new RetentionSkipLog();
        log.onSkip(RetentionPlan.Skip.REPORTER_NOT_RUNNING, "BACKING_OFF", 0);
        RetentionSkipLog.Line line = log.onSkip(RetentionPlan.Skip.SNAPSHOTS_UNREADABLE, "denied", HOUR);
        assertEquals(RetentionSkipLog.Level.WARN, line.level());
    }

    @Test
    void aReplayRefusalIsInfoUntilThreeInARow() {
        RetentionSkipLog log = new RetentionSkipLog();
        assertEquals(RetentionSkipLog.Level.INFO,
                log.onSkip(RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY, "resend", 0).level());
        assertEquals(RetentionSkipLog.Level.INFO,
                log.onSkip(RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY, "resend", 1).level());
        assertEquals(RetentionSkipLog.Level.WARN,
                log.onSkip(RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY, "resend", 2).level());
        assertNull(log.onSkip(RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY, "resend", 3), "then rate-limited");

        RetentionSkipLog other = new RetentionSkipLog();
        assertEquals(RetentionSkipLog.Level.WARN,
                other.onSkip(RetentionPlan.Skip.ARCHIVE_REFUSED, "another delete", 0).level(),
                "every other refusal is a WARN at once");
    }

    @Test
    void aRunAfterSkipsSaysTheReasonCleared() {
        RetentionSkipLog log = new RetentionSkipLog();
        assertNull(log.onRun(), "nothing to clear");
        log.onSkip(RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY, "resend", 0);
        RetentionSkipLog.Line cleared = log.onRun();
        assertNotNull(cleared);
        assertEquals(RetentionSkipLog.Level.INFO, cleared.level());
        assertEquals(RetentionSkipLog.Level.INFO,
                log.onSkip(RetentionPlan.Skip.ARCHIVE_REFUSED_REPLAY, "resend", 5).level(), "the count restarts");
    }
}
