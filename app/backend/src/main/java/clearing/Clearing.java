package clearing;

import config.ExchangeConfig;
import db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.ClearingStatus;
import util.ReporterStatus;
import util.VenueStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * End-of-day clearing (SRS 3.12): one daemon thread named {@value #THREAD_NAME} that clears each closed
 * business date once the Reporter has applied every execution up to its close.
 *
 * <p><b>Trigger.</b> The Reporter records a PENDING run in the transaction that consumes
 * SESSION_CLOSED, so a run exists exactly when its date's executions are all in the database, whichever
 * path closed the day (the calendar roll, the stale-day close at startup, or a test). The Reporter wakes
 * this thread after that commit; a bounded poll covers a missed wake and a database that comes back.
 *
 * <p><b>Order.</b> Pending dates clear in ascending order, each in its own transaction
 * ({@link ClearingStore}), and a cycle stops at the first date that cannot complete, because each roll
 * forward starts from the previous day's result.
 *
 * <p><b>Failure.</b> Like the Reporter: a transient failure backs off (doubling to a bound) and retries
 * from the database's state; SQLState class 22 or 23, or a broken invariant, stops clearing with one
 * ERROR (state FAILED). No date is ever skipped, and the venue keeps trading either way.
 *
 * <p><b>Held.</b> Clearing cannot see a close the Reporter has not recorded. A Reporter that is not
 * RUNNING is reported in {@link #heldBy()}; a closed day with no run while the Reporter is not running
 * makes the state HELD, logged once per transition.
 *
 * <p>Never on the engine thread, a Netty event loop, the session scheduler or the Reporter thread; it
 * never reads the journal or a ring.
 */
public final class Clearing implements ClearingStatus, AutoCloseable {

    /** Where connections come from; a seam for tests of a database that comes and goes. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection connect() throws SQLException;
    }

    public static final String THREAD_NAME = "clearing";

    private static final Logger log = LoggerFactory.getLogger(Clearing.class);

    private static final long BACKOFF_SLICE_MS = 50L;

    private final ConnectionSource connections;
    private final ReporterStatus reporter;
    private final LongSupplier sessionWord;
    private final LongSupplier epochNanos;
    private final ClearingTiming timing;
    private final Thread thread;

    private volatile boolean stopping;
    private volatile String state = "STARTING";
    private volatile int lastClearedDate = -1;
    private volatile int pendingDate = -1;
    private volatile String heldBy;
    private volatile String failure;

    // Clearing thread only.
    private boolean started;
    private int failuresInARow;
    private long lastErrorNanos;
    private long suppressedErrors;

    /**
     * @param reporter    the Reporter whose checkpoint clearing waits for; its state is reported, never changed
     * @param sessionWord the engine's published session state ({@link VenueStatus#session()})
     * @param epochNanos  the wall clock for a run's start and completion stamps
     */
    public Clearing(ConnectionSource connections, ReporterStatus reporter, LongSupplier sessionWord,
                    LongSupplier epochNanos, ClearingTiming timing) {
        this.connections = connections;
        this.reporter = reporter;
        this.sessionWord = sessionWord;
        this.epochNanos = epochNanos;
        this.timing = timing;
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
    }

    /** The clearing of a venue whose {@code DB_URL} is set: Main's call. */
    public static Clearing forVenue(ExchangeConfig config, VenueStatus venue, ReporterStatus reporter,
                                    LongSupplier epochNanos) {
        Database database = Database.from(config);
        return new Clearing(database::connect, reporter, venue::session, epochNanos, ClearingTiming.PRODUCTION);
    }

    public void start() {
        started = true;
        thread.start();
    }

    /** Asks for a check now. Any thread; never blocks. The Reporter calls it after recording a close. */
    public void wake() {
        LockSupport.unpark(thread);
    }

    /**
     * Stops, bounded. A run in progress either commits or rolls back with its connection.
     *
     * @return whether the thread finished in time; if not it is inside JDBC, as a daemon
     */
    public boolean stop() {
        stopping = true;
        if (!started) {
            return true;
        }
        LockSupport.unpark(thread);
        try {
            thread.join(timing.stopTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean stopped = !thread.isAlive();
        if (!stopped) {
            log.warn("Clearing did not stop within {} ms; it is waiting on the database", timing.stopTimeoutMs());
        }
        return stopped;
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public String state() {
        return state;
    }

    @Override
    public int lastClearedDate() {
        return lastClearedDate;
    }

    @Override
    public int pendingDate() {
        return pendingDate;
    }

    @Override
    public String heldBy() {
        return heldBy;
    }

    /** Why clearing stopped itself, or null. */
    public String failure() {
        return failure;
    }

    private void run() {
        while (!stopping) {
            try (Connection connection = connections.connect()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                while (!stopping) {
                    cycle(connection);
                    if (failuresInARow > 0) {
                        log.info("Clearing is working again after {} failure(s)", failuresInARow);
                        failuresInARow = 0;
                        suppressedErrors = 0;
                    }
                    LockSupport.parkNanos(timing.pollIntervalMs() * 1_000_000L);
                }
            } catch (ClearingInvariantException e) {
                fail(e.getMessage(), e);
                return;
            } catch (Exception e) {
                if (Database.isDataFailure(e)) {
                    fail("The database refused a clearing row: " + e.getMessage() + ". Nothing was skipped.", e);
                    return;
                }
                if (!stopping) {
                    backOff(e);
                }
            } catch (Error e) {
                fail("Clearing failed: " + e, e);
                return;
            }
        }
        state = "STOPPED";
    }

    /** Clears every pending date that can be cleared, in order, then publishes the state. */
    private void cycle(Connection connection) throws SQLException {
        String held = held();
        heldBy = held;
        lastClearedDate = ClearingStore.lastCompleteDate(connection);
        List<Integer> pending = ClearingStore.pendingDates(connection);
        for (int date : pending) {
            if (stopping) return;
            ClearingStore.Attempt attempt = ClearingStore.clear(connection, date, epochNanos);
            switch (attempt.kind()) {
                case COMPLETED -> {
                    ClearingResult r = attempt.result();
                    log.info("Cleared business date {}: {} fill(s), volume {}, {} obligation(s); balances and positions"
                            + " rolled to {}", date, r.fillCount(), r.volume(), r.obligations().size(), r.nextBusinessDate());
                    lastClearedDate = date;
                }
                case ALREADY_COMPLETE -> lastClearedDate = Math.max(lastClearedDate, date);
                case WAITING_FOR_REPORTER -> {
                    publish(held == null ? "WAITING" : "HELD", date, held);
                    return;
                }
                case EARLIER_PENDING, NO_RUN -> {
                    // Another writer changed the runs between the read and the lock; the next cycle sees it.
                    publish("WAITING", date, held);
                    return;
                }
            }
        }
        // Nothing pending can complete. A closed day without a run means the Reporter has not recorded it.
        long word = sessionWord.getAsLong();
        int closed = VenueStatus.isSessionOpen(word) ? -1 : VenueStatus.sessionDate(word);
        if (closed > 0 && closed > lastClearedDate && !ClearingStore.runExists(connection, closed)) {
            publish(held == null ? "WAITING" : "HELD", closed, held);
        } else {
            publish("IDLE", -1, held);
        }
    }

    private void publish(String newState, int date, String held) {
        if ("HELD".equals(newState) && !"HELD".equals(state)) {
            log.warn("Clearing is held: business date {} closed but cannot clear while the {}; it resumes once the"
                    + " Reporter records the close", date, held);
        }
        pendingDate = date;
        state = newState;
    }

    /** What holds clearing back: a Reporter that is not running cannot record the closes clearing waits for. */
    private String held() {
        String reporterState = reporter == null ? null : reporter.state();
        return (reporterState == null || "RUNNING".equals(reporterState)) ? null : "reporter " + reporterState;
    }

    private void fail(String message, Throwable cause) {
        failure = message;
        state = "FAILED";
        log.error("Clearing stopped after business date {}; no later date is cleared, and matching is unaffected: {}",
                lastClearedDate, message, cause);
    }

    private void backOff(Exception cause) {
        state = "BACKING_OFF";
        failuresInARow++;
        long now = System.nanoTime();
        if (failuresInARow == 1 || now - lastErrorNanos >= timing.errorLogIntervalMs() * 1_000_000L) {
            log.error("Clearing failed and will retry ({} failure(s) in a row, {} not logged); matching is unaffected: {}",
                    failuresInARow, suppressedErrors, cause.toString());
            lastErrorNanos = now;
            suppressedErrors = 0;
        } else {
            suppressedErrors++;
        }
        int shift = Math.min(failuresInARow - 1, 20);
        long delayMs = Math.min(timing.backoffMaxMs(), timing.backoffMinMs() << shift);
        long deadline = now + delayMs * 1_000_000L;
        while (!stopping && System.nanoTime() - deadline < 0) {
            LockSupport.parkNanos(Math.min(BACKOFF_SLICE_MS, delayMs) * 1_000_000L);
        }
    }
}
