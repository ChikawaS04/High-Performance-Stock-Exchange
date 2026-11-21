package clearing;

import config.ExchangeConfig;
import db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.ClearingStatus;
import util.DatabaseRetentionStatus;
import util.ReporterStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Database retention (P16-8, D14): one daemon thread named {@value #THREAD_NAME} that deletes the detail
 * rows ({@code orders}, {@code executions}, {@code order_audit}) of COMPLETE business dates outside the
 * window ({@link RetentionWindow}), in batches of one transaction each ({@link RetentionStore}).
 *
 * <p><b>When.</b> After each COMPLETE clearing run (clearing wakes it), and once a day, the first time
 * shortly after start. Never on the engine thread, a Netty event loop, the Reporter or clearing.
 *
 * <p><b>Doubt.</b> A Reporter that is not RUNNING, a FAILED clearing, or any check that fails ends the pass
 * with nothing more deleted (the P16-7 R7 pattern, {@link PurgeSkipLog}). A FAILED Reporter or clearing
 * means the database disagrees with the journal, so its rows are kept as evidence.
 *
 * <p><b>Stop.</b> {@link #stop()} sets a flag read before every batch and waits; if the thread is still
 * inside JDBC after the bound, the connection is aborted, so PostgreSQL rolls the batch back. Every batch
 * is atomic and a date is marked purged before its first batch, so any stop leaves a consistent database,
 * and the next pass resumes the date.
 */
public final class DatabaseRetention implements AutoCloseable {

    /** Where connections come from; a seam for tests. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection connect() throws SQLException;
    }

    public static final String THREAD_NAME = "db-retention";

    private static final Logger log = LoggerFactory.getLogger(DatabaseRetention.class);

    private final ConnectionSource connections;
    private final Supplier<ReporterStatus> reporter;
    private final Supplier<ClearingStatus> clearing;
    private final boolean enabled;
    private final int days;
    private final int batchRows;
    private final boolean vacuum;
    private final LongSupplier epochNanos;
    private final DatabaseRetentionTiming timing;
    private final PurgeSkipLog skipLog = new PurgeSkipLog();
    private final Thread thread;

    private volatile DatabaseRetentionStatus status;
    private volatile boolean stopping;
    private volatile boolean runRequested;
    private volatile long completedRuns;
    private volatile Connection current;
    private volatile Runnable afterBatch;   // test seam: runs on this thread after each committed batch

    // Retention thread only.
    private boolean started;
    private long lastRunTs = -1L;
    private String outcome;
    private String skipReason;
    private int purgedThrough = -1;
    private long lastPurgeTs = -1L;
    private long rowsDeleted;

    /**
     * @param reporter the Reporter's status (a pass runs only while it is RUNNING)
     * @param clearing clearing's status (a pass never runs while it is FAILED)
     */
    public DatabaseRetention(ConnectionSource connections, Supplier<ReporterStatus> reporter,
                             Supplier<ClearingStatus> clearing, boolean enabled, int days, int batchRows,
                             boolean vacuum, LongSupplier epochNanos, DatabaseRetentionTiming timing) {
        this.connections = connections;
        this.reporter = reporter;
        this.clearing = clearing;
        this.enabled = enabled;
        this.days = days;
        this.batchRows = batchRows;
        this.vacuum = vacuum;
        this.epochNanos = epochNanos;
        this.timing = timing;
        this.outcome = enabled ? DatabaseRetentionStatus.NOT_RUN : DatabaseRetentionStatus.DISABLED;
        this.status = buildStatus();
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
    }

    /** The retention of a venue whose {@code DB_URL} is set: Main's call. */
    public static DatabaseRetention forVenue(ExchangeConfig config, ReporterStatus reporter, ClearingStatus clearing,
                                             LongSupplier epochNanos) {
        Database database = new Database(config.dbUrl(), config.dbUser(), config.dbPassword(), Database.Timing.RETENTION);
        if (config.dbRetentionDays() == 0 && config.dbRetentionEnabled()) {
            log.warn("{}=0: each business date's trades, orders and audit trail are deleted as soon as it is cleared",
                    ExchangeConfig.DB_RETENTION_DAYS);
        }
        return new DatabaseRetention(database::connect, () -> reporter, () -> clearing, config.dbRetentionEnabled(),
                config.dbRetentionDays(), config.dbRetentionBatchRows(), config.dbRetentionVacuum(), epochNanos,
                DatabaseRetentionTiming.PRODUCTION);
    }

    public void start() {
        started = true;
        thread.start();
    }

    /**
     * Runs exactly one pass on the calling thread and returns its status (P16-12: the {@code retention-once}
     * measurement command). Only for an instance whose thread was never started, so a pass never overlaps
     * another.
     *
     * @throws IllegalStateException if {@link #start()} was called
     */
    public DatabaseRetentionStatus runOnce() {
        if (started) throw new IllegalStateException("runOnce is for a retention whose thread was never started");
        runSafely();
        completedRuns++;
        return status;
    }

    /** Asks for a pass as soon as the thread is free. Any thread; never blocks (clearing's completion hook). */
    public void requestRun() {
        runRequested = true;
        LockSupport.unpark(thread);
    }

    /** What {@code /health} reports. Any thread: one volatile read. */
    public DatabaseRetentionStatus status() {
        return status;
    }

    /** Passes completed so far, skipped and interrupted ones included. Any thread. */
    public long completedRuns() {
        return completedRuns;
    }

    /** Test seam: asks the pass to stop before its next batch, without waiting (callable from the thread itself). */
    void stopRequested() {
        stopping = true;
    }

    /** Test seam: runs on the retention thread after each committed batch. */
    void afterBatch(Runnable hook) {
        this.afterBatch = hook;
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Stops, bounded: waits for the current batch, then aborts the connection if the thread is still in JDBC.
     *
     * @return whether the thread finished
     */
    public boolean stop() {
        stopping = true;
        if (!started) return true;
        LockSupport.unpark(thread);
        join(timing.stopTimeoutMs());
        if (thread.isAlive()) {
            Connection c = current;
            if (c != null) {
                log.warn("Database retention did not stop within {} ms; aborting its connection, so the batch in"
                        + " progress rolls back", timing.stopTimeoutMs());
                try {
                    c.abort(Runnable::run);
                } catch (SQLException e) {
                    log.warn("Aborting the database retention connection failed: {}", e.toString());
                }
            }
            join(timing.abortJoinMs());
        }
        boolean stopped = !thread.isAlive();
        if (!stopped) {
            log.warn("Database retention is still running after its connection was aborted");
        }
        return stopped;
    }

    private void join(long ms) {
        try {
            thread.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        long now = System.currentTimeMillis();
        long nextPeriodic = saturatedAdd(now, timing.firstRunDelayMs());
        while (!stopping) {
            now = System.currentTimeMillis();
            boolean periodic = now >= nextPeriodic;
            if (runRequested || periodic) {
                runRequested = false;
                runSafely();
                completedRuns++;
                if (periodic) nextPeriodic = saturatedAdd(System.currentTimeMillis(), timing.intervalMs());
                continue;
            }
            long waitMs = Math.max(1L, Math.min(nextPeriodic - now, 1_000L));
            LockSupport.parkNanos(this, waitMs * 1_000_000L);
        }
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return (sum < a) ? Long.MAX_VALUE : sum;
    }

    /** One pass; never throws. */
    private void runSafely() {
        RetentionStore.Skip skip = null;
        rowsDeleted = 0;
        try {
            outcome = pass();
        } catch (RetentionStore.Skip s) {
            skip = s;
        } catch (SQLException | RuntimeException e) {
            if (stopping) {
                outcome = DatabaseRetentionStatus.INTERRUPTED;
            } else if (Database.isUnavailable(e)) {
                skip = new RetentionStore.Skip(RetentionStore.DATABASE_UNAVAILABLE, e.toString());
            } else if (Database.isDataFailure(e)) {
                skip = new RetentionStore.Skip(RetentionStore.INCONSISTENT, e.toString());
            } else {
                log.error("Database retention pass failed; nothing more is deleted until the next pass", e);
                skip = new RetentionStore.Skip(RetentionStore.UNEXPECTED, e.toString());
            }
        } catch (Error e) {
            log.error("Database retention pass failed", e);
            skip = new RetentionStore.Skip(RetentionStore.UNEXPECTED, e.toString());
        }
        lastRunTs = epochNanos.getAsLong();
        if (skip != null) {
            outcome = DatabaseRetentionStatus.SKIPPED;
            skipReason = skip.reason + ": " + skip.getMessage();
            PurgeSkipLog.Line line = skipLog.onSkip(skip.reason, skip.getMessage(), System.currentTimeMillis());
            if (line != null) {
                if (line.warn()) log.warn(line.text()); else log.info(line.text());
            }
        } else {
            skipReason = null;
            if (!DatabaseRetentionStatus.INTERRUPTED.equals(outcome)) {
                PurgeSkipLog.Line line = skipLog.onRun();
                if (line != null) log.info(line.text());
            }
        }
        status = buildStatus();
    }

    /** Returns the outcome, or throws a {@link RetentionStore.Skip}. */
    private String pass() throws SQLException {
        if (!enabled) return DatabaseRetentionStatus.DISABLED;
        ReporterStatus r = reporter.get();
        if (r == null || !"RUNNING".equals(r.state())) {
            throw new RetentionStore.Skip(RetentionStore.REPORTER_NOT_RUNNING, r == null ? "no Reporter" : r.state());
        }
        ClearingStatus cl = clearing.get();
        if (cl != null && "FAILED".equals(cl.state())) {
            throw new RetentionStore.Skip(RetentionStore.CLEARING_FAILED, "clearing is FAILED");
        }
        try (Connection c = connections.connect()) {
            current = c;
            try {
                RetentionStore.configure(c, timing.statementTimeoutMs(), timing.lockTimeoutMs());
                List<RetentionStore.Run> work = RetentionStore.workList(c, days);
                if (work.isEmpty()) {
                    purgedThrough = RetentionStore.purgedThrough(c);
                    return DatabaseRetentionStatus.NOTHING_TO_PURGE;
                }
                Map<String, Long> touched = new LinkedHashMap<>();
                for (RetentionStore.Run run : work) {
                    if (stopping) return DatabaseRetentionStatus.INTERRUPTED;
                    if (!purgeDate(c, run, touched)) return DatabaseRetentionStatus.INTERRUPTED;
                }
                if (vacuum) {
                    for (String table : RetentionStore.TABLES) {
                        if (stopping) return DatabaseRetentionStatus.INTERRUPTED;
                        if (touched.getOrDefault(table, 0L) == 0L) continue;
                        long t0 = System.nanoTime();
                        RetentionStore.vacuum(c, table, timing.statementTimeoutMs());
                        log.info("Database retention: VACUUM (ANALYZE) {} took {} ms", table,
                                (System.nanoTime() - t0) / 1_000_000L);
                    }
                }
                purgedThrough = RetentionStore.purgedThrough(c);
                return DatabaseRetentionStatus.PURGED;
            } finally {
                current = null;
            }
        }
    }

    /** Purges one date; false if stopped part way (the next pass resumes it). */
    private boolean purgeDate(Connection c, RetentionStore.Run run, Map<String, Long> touched) throws SQLException {
        int date = run.businessDate();
        long t0 = System.nanoTime();
        if (!run.purgeStarted()) {
            RetentionStore.markPurged(c, run, days, epochNanos.getAsLong());
            log.info("Database retention: business date {} is marked purged (/api answers 410 for its trades and"
                    + " statements, its daily and volume figures come from daily_summary); deleting its detail rows",
                    date);
        } else {
            log.info("Database retention: resuming the purge of business date {}", date);
        }
        Map<RetentionStore.Step, long[]> perStep = new EnumMap<>(RetentionStore.Step.class);   // rows, nanos
        for (RetentionStore.Step step : RetentionStore.Step.values()) {
            long[] acc = perStep.computeIfAbsent(step, k -> new long[2]);
            while (true) {
                if (stopping) return false;
                long s0 = System.nanoTime();
                int n = RetentionStore.deleteBatch(c, step, run, days, batchRows);
                acc[1] += System.nanoTime() - s0;
                acc[0] += n;
                rowsDeleted += n;
                touched.merge(step.table, (long) n, Long::sum);
                Runnable hook = afterBatch;
                if (hook != null) hook.run();
                if (n < batchRows) break;
                if (timing.batchPauseMs() > 0) LockSupport.parkNanos(timing.batchPauseMs() * 1_000_000L);
            }
        }
        if (stopping) return false;
        RetentionStore.markCompleted(c, run, days, epochNanos.getAsLong());
        lastPurgeTs = epochNanos.getAsLong();
        StringBuilder rates = new StringBuilder();
        for (Map.Entry<RetentionStore.Step, long[]> e : perStep.entrySet()) {
            long rows = e.getValue()[0];
            long nanos = e.getValue()[1];
            if (rate(rates, e.getKey(), rows, nanos)) rates.append("; ");
        }
        log.info("Database retention purged business date {} in {} ms: {}", date, (System.nanoTime() - t0) / 1_000_000L,
                rates.length() == 0 ? "no rows" : rates.substring(0, rates.length() - 2));
        return true;
    }

    /** One step of the per-date line: {@code <step> N rows in T ms (R/s)} (P16-12: rows, time and rate per table). */
    static boolean rate(StringBuilder out, RetentionStore.Step step, long rows, long nanos) {
        if (rows == 0) return false;
        long perSecond = nanos <= 0 ? rows : rows * 1_000_000_000L / nanos;
        out.append(step.name().toLowerCase()).append(' ').append(rows).append(" rows in ").append(nanos / 1_000_000L)
                .append(" ms (").append(perSecond).append("/s)");
        return true;
    }

    private DatabaseRetentionStatus buildStatus() {
        return new DatabaseRetentionStatus(enabled, days, lastRunTs, outcome, skipReason, purgedThrough, lastPurgeTs,
                rowsDeleted, epochNanos.getAsLong());
    }
}
