package clearing;

import db.RequiresTestDatabase;
import db.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import util.ReporterStatus;
import util.VenueStatus;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

import static clearing.ClearingSeed.*;
import static org.junit.jupiter.api.Assertions.*;

/** The clearing thread against {@code exchange_test}: waking, waiting, holding, backing off, stopping loudly. */
@RequiresTestDatabase
class ClearingTest {

    private static final int DAY = 20261106;
    private static final int NEXT = 20261107;
    private static final long PX = 1_000_000L;
    private static final long AWAIT_MS = 15_000L;

    /** A long poll, so a test that needs the wake proves the wake; short back-off for the failure paths. */
    private static final ClearingTiming WAKE_ONLY = new ClearingTiming(60_000L, 10L, 100L, 1_000L, 5_000L);
    private static final ClearingTiming FAST_POLL = new ClearingTiming(20L, 10L, 100L, 1_000L, 5_000L);

    private final ClearingSeed seed = new ClearingSeed();
    private final StubReporter reporter = new StubReporter();
    private final AtomicLong sessionWord = new AtomicLong(VenueStatus.sessionWord(true, NEXT, -1));
    private final AtomicLong wallClock = new AtomicLong(1_000L);
    private Clearing clearing;

    private static final class StubReporter implements ReporterStatus {
        volatile String state = "RUNNING";

        @Override public String state() { return state; }
        @Override public long inboundSeq() { return 0L; }
        @Override public long outboundSeq() { return 0L; }
    }

    @BeforeEach
    void emptySchema() {
        TestDatabase.cleanAndMigrate();
    }

    @AfterEach
    void stop() {
        if (clearing != null) {
            assertTrue(clearing.stop());
        }
    }

    private Clearing start(Clearing.ConnectionSource connections, ClearingTiming timing) {
        clearing = new Clearing(connections, reporter, sessionWord::get, () -> wallClock.addAndGet(10L), timing);
        clearing.start();
        return clearing;
    }

    private Clearing start(ClearingTiming timing) {
        return start(TestDatabase.database()::connect, timing);
    }

    private static void await(String what, BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for " + what);
            }
            LockSupport.parkNanos(1_000_000L);
        }
    }

    private void awaitState(String state) {
        await("clearing to be " + state + " (is " + clearing.state() + ", failure " + clearing.failure() + ")",
                () -> state.equals(clearing.state()));
    }

    private void awaitCleared(int date) {
        await("clearing of " + date + " (state " + clearing.state() + ", failure " + clearing.failure() + ")",
                () -> clearing.lastClearedDate() >= date);
    }

    private static String status(int date) {
        return TestDatabase.queryOne("SELECT status FROM clearing_run WHERE business_date = " + date);
    }

    @Test
    void withNothingPendingItIsIdle() {
        start(WAKE_ONLY);
        awaitState("IDLE");
        assertEquals(-1, clearing.lastClearedDate());
        assertEquals(-1, clearing.pendingDate());
        assertNull(clearing.heldBy());
    }

    @Test
    void itWaitsForTheReportersCheckpointThenClearsWhenWoken() {
        seed.fill(DAY, PX, 2L, "BUY", HOUSE, HOUSE);
        long close = seed.close(DAY);
        checkpoint(close - 1);
        start(WAKE_ONLY);
        await("WAITING on " + DAY, () -> "WAITING".equals(clearing.state()) && clearing.pendingDate() == DAY);
        assertEquals("PENDING", status(DAY));

        checkpoint(close);
        clearing.wake();
        awaitCleared(DAY);
        awaitState("IDLE");
        assertEquals("COMPLETE", status(DAY));
        assertEquals(DAY + " 1 ASML 2 2 0 0", String.join(" ", rows("SELECT * FROM clearing_obligation")));
    }

    @Test
    void theBoundedPollClearsARunNobodyWokeItFor() {
        start(FAST_POLL);
        awaitState("IDLE");
        seed.close(DAY);
        seed.caughtUp();
        awaitCleared(DAY);
        assertEquals("COMPLETE", status(DAY));
    }

    @Test
    void pendingDaysClearInAscendingOrder() {
        seed.fill(DAY, PX, 1L, "BUY", HOUSE, HOUSE);
        seed.close(DAY);
        seed.fill(NEXT, PX, 1L, "BUY", HOUSE, HOUSE);
        seed.close(NEXT);
        seed.caughtUp();
        start(WAKE_ONLY);
        awaitCleared(NEXT);
        assertEquals(java.util.List.of(DAY + " 1", NEXT + " 2"),
                rows("SELECT business_date, rank() OVER (ORDER BY completed_ts) FROM clearing_run ORDER BY business_date"));
        assertEquals(java.util.List.of(NEXT + " ASML 0", "20261108 ASML 0"),
                rows("SELECT * FROM house_position ORDER BY business_date"));
    }

    @Test
    void aFailedReporterHoldsClearingVisibly() {
        reporter.state = "FAILED";
        sessionWord.set(VenueStatus.sessionWord(false, -1, DAY));      // the venue closed DAY; no run recorded
        start(WAKE_ONLY);
        await("HELD", () -> "HELD".equals(clearing.state()));
        assertEquals("reporter FAILED", clearing.heldBy());
        assertEquals(DAY, clearing.pendingDate());
        assertEquals(-1, clearing.lastClearedDate());

        // A run the Reporter recorded before it failed, with the checkpoint short of it, is held too.
        long close = seed.close(DAY);
        checkpoint(close - 1);
        clearing.wake();
        await("HELD on the recorded run", () -> "HELD".equals(clearing.state()) && clearing.pendingDate() == DAY);
        assertEquals("PENDING", status(DAY));

        // The Reporter restarts and catches up: clearing proceeds and the hold clears.
        reporter.state = "RUNNING";
        checkpoint(close);
        clearing.wake();
        awaitCleared(DAY);
        awaitState("IDLE");
        assertNull(clearing.heldBy());
    }

    @Test
    void aReporterBackingOffIsReportedWhileCleared() {
        reporter.state = "BACKING_OFF";
        seed.close(DAY);
        seed.caughtUp();
        start(WAKE_ONLY);
        awaitCleared(DAY);
        // A run already recorded clears regardless; the Reporter's state is still shown.
        await("heldBy", () -> "reporter BACKING_OFF".equals(clearing.heldBy()));
    }

    @Test
    void aTransientDatabaseFailureBacksOffAndThenCompletes() {
        seed.close(DAY);
        seed.caughtUp();
        AtomicBoolean down = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        start(() -> {
            attempts.incrementAndGet();
            if (down.get()) {
                throw new SQLException("Connection refused", "08001");
            }
            return TestDatabase.database().connect();
        }, WAKE_ONLY);
        awaitState("BACKING_OFF");
        await("a retry", () -> attempts.get() >= 2);
        assertEquals("PENDING", status(DAY));
        down.set(false);
        awaitCleared(DAY);
        assertEquals("COMPLETE", status(DAY));
    }

    @Test
    void anInvariantFailureStopsClearingLoudlyAndSkipsNothing() {
        seed.close(DAY);
        seed.accepted(DAY);                      // an execution after DAY's close
        seed.close(NEXT);
        seed.caughtUp();
        start(WAKE_ONLY);
        awaitState("FAILED");
        assertTrue(clearing.failure().contains("Business date " + DAY + " closed at outbound seq"), clearing.failure());
        assertEquals("PENDING", status(DAY));
        assertEquals("PENDING", status(NEXT), "a later date is never cleared past a failed one");
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM house_position"));
    }

    @Test
    void stoppingIsBoundedAndFinal() {
        start(WAKE_ONLY);
        awaitState("IDLE");
        assertTrue(clearing.stop());
        assertEquals("STOPPED", clearing.state());
        clearing = null;
    }
}
