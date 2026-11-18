package market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;

import static market.IgnitionSchedule.MIN_GAP_MS;
import static market.IgnitionSchedule.NEVER;
import static market.IgnitionSchedule.NO_DATE;
import static market.IgnitionSchedule.STALE_REFETCH_MS;
import static market.IgnitionSchedule.nextAttemptAt;
import static org.junit.jupiter.api.Assertions.*;

/** Pure tests of {@link IgnitionSchedule} (P16-3, D9 as amended). */
class IgnitionScheduleTest {

    /** A New York wall-clock time in November (EST, -05:00) as epoch ms. */
    private static long ny(String local) {
        return ZonedDateTime.parse(local + "-05:00[America/New_York]").toInstant().toEpochMilli();
    }

    @Test
    void theFirstAttemptIsNow() {
        long now = ny("2026-11-18T09:00:00");
        assertEquals(now, nextAttemptAt(now, NEVER, false, NO_DATE));
    }

    @Test
    void aFailureRetriesAfterSixtySeconds() {
        long last = ny("2026-11-18T09:00:00");
        assertEquals(last + MIN_GAP_MS, nextAttemptAt(last + 5_000L, last, true, NO_DATE));
        assertEquals(last + MIN_GAP_MS, nextAttemptAt(last + 5_000L, last, true, 20261118),
                "a failed refetch with today's value cached still waits only the minimum gap");
    }

    @Test
    void todaysBarHoldsUntilTheNextNewYorkDate() {
        long last = ny("2026-11-18T09:31:00");
        assertEquals(ny("2026-11-19T00:00:00"), nextAttemptAt(last + 1_000L, last, false, 20261118));
    }

    @Test
    void aBarFromAnEarlierDateRefetchesEveryFifteenMinutes() {
        long last = ny("2026-11-18T07:00:00");
        assertEquals(last + STALE_REFETCH_MS, nextAttemptAt(last + 1_000L, last, false, 20261117));
    }

    @Test
    void todaysValueBecomesStaleAtNewYorkMidnight() {
        long last = ny("2026-11-18T09:31:00");
        long now = ny("2026-11-19T00:00:30");
        // The cached bar (the 18th) is now yesterday's: the 15-minute rule applies, already in the past.
        assertEquals(last + STALE_REFETCH_MS, nextAttemptAt(now, last, false, 20261118));
        assertTrue(nextAttemptAt(now, last, false, 20261118) <= now);
    }

    @Test
    void theSixtySecondFloorAlwaysHolds() {
        long last = ny("2026-11-18T23:59:50");
        // Midnight is 10 s away, but no call may start sooner than 60 s after the last.
        assertEquals(last + MIN_GAP_MS, nextAttemptAt(last + 1_000L, last, false, 20261118));
    }

    @Test
    void aBarDatedInTheFutureHoldsLikeToday() {
        long last = ny("2026-11-18T09:31:00");
        assertEquals(ny("2026-11-19T00:00:00"), nextAttemptAt(last, last, false, 20261119));
    }

    @Test
    void theNextDateRespectsDaylightSaving() {
        // 2026-11-01 falls back: 00:30 that day is still EDT, and midnight on the 2nd is at -05:00.
        long fall = Instant.parse("2026-11-01T04:30:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-11-02T05:00:00Z").toEpochMilli(), nextAttemptAt(fall, fall, false, 20261101));
        // 2026-03-08 springs forward: midnight on the 9th is at -04:00.
        long spring = Instant.parse("2026-03-08T05:30:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(),
                nextAttemptAt(spring, spring, false, 20260308));
    }
}
