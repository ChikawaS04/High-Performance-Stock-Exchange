package clearing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Database retention's SQL (P16-8, D14). Every write is one short transaction that first re-checks, in
 * its own snapshot, that the date may still be purged, then deletes at most one batch from one table,
 * then commits; any failed check throws {@link Skip} and nothing of that batch is written. The caller's
 * connection has autocommit off and READ COMMITTED isolation.
 *
 * <p>Per date, in order: {@link #markPurged} (the {@code daily_summary} rows and {@code purged_ts}, so the
 * date answers 410 before any row goes), then the {@link Step}s in declaration order (audit, executions,
 * the rows outside any session, orders), then {@link #markCompleted}. Nothing references
 * {@code executions} or {@code order_audit}, so they go first; an {@code orders} row goes only when it is
 * terminal, ended at or before the date's close, lies below the Reporter's checkpoint, and no kept row
 * references it.
 */
final class RetentionStore {

    /** A COMPLETE date that is eligible and not yet fully purged. */
    record Run(int businessDate, long closeOutboundSeq, long closedTs, boolean purgeStarted) {
    }

    /** A check that failed: the pass deletes nothing more. */
    static final class Skip extends RuntimeException {
        final String reason;

        Skip(String reason, String detail) {
            super(detail, null, false, false);
            this.reason = reason;
        }
    }

    static final String REPORTER_NOT_RUNNING = "REPORTER_NOT_RUNNING";
    static final String CLEARING_FAILED = "CLEARING_FAILED";
    static final String CHECKPOINT_BEHIND = "CHECKPOINT_BEHIND";
    static final String PENDING_EARLIER = "PENDING_EARLIER";
    static final String INCONSISTENT = "INCONSISTENT";
    static final String DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE";
    static final String UNEXPECTED = "UNEXPECTED";

    private static final String TERMINAL = "('FILLED', 'CANCELLED', 'EXPIRED', 'REJECTED')";
    /**
     * The reference checks of an orders candidate. {@code OFFSET 0} keeps each one a correlated subplan, so it
     * is one index probe per candidate ({@code executions_order_inbound_seq},
     * {@code executions_passive_order_inbound_seq}, {@code order_audit_order_inbound_seq}); without it the
     * planner may turn them into hash anti joins that read the whole of both tables on every batch.
     */
    private static final String NOT_REFERENCED =
            " AND NOT EXISTS (SELECT 1 FROM executions e WHERE e.order_inbound_seq = o.inbound_seq OFFSET 0)"
            + " AND NOT EXISTS (SELECT 1 FROM executions e WHERE e.passive_order_inbound_seq = o.inbound_seq OFFSET 0)"
            + " AND NOT EXISTS (SELECT 1 FROM order_audit a WHERE a.order_inbound_seq = o.inbound_seq OFFSET 0)";

    /**
     * One kind of batch, in the order a date's purge runs them. Each picks at most {@code limit} rows by an
     * index, once ({@code ARRAY(...)} is evaluated before the delete), and deletes them by row address or
     * key, so a batch never scans a table. The audit and execution deletes repeat their condition, so a
     * row address can never name another row. {@code params} names each placeholder in order: D the date,
     * T the close's timestamp, S the close's outbound seq, C the Reporter's inbound seq, L the limit.
     */
    enum Step {
        /** {@code order_audit} rows dated the business date. */
        AUDIT("order_audit", "DLD", "DELETE FROM order_audit WHERE ctid = ANY (ARRAY(SELECT ctid FROM order_audit"
                + " WHERE business_date = ? LIMIT ?)) AND business_date = ?"),
        /** {@code executions} rows dated the business date. */
        EXECUTIONS("executions", "DLD", "DELETE FROM executions WHERE ctid = ANY (ARRAY(SELECT ctid FROM executions"
                + " WHERE business_date = ? LIMIT ?)) AND business_date = ?"),
        /** Audit rows outside any session, stamped before the close. */
        UNDATED_AUDIT("order_audit", "TLT", "DELETE FROM order_audit WHERE ctid = ANY (ARRAY(SELECT ctid FROM order_audit"
                + " WHERE business_date IS NULL AND event_ts < ? LIMIT ?)) AND business_date IS NULL AND event_ts < ?"),
        /** Executions outside any session, before the close. */
        UNDATED_EXECUTIONS("executions", "TSLTS", "DELETE FROM executions WHERE ctid = ANY (ARRAY(SELECT ctid"
                + " FROM executions WHERE business_date IS NULL AND event_ts < ? AND outbound_seq < ? LIMIT ?))"
                + " AND business_date IS NULL AND event_ts < ? AND outbound_seq < ?"),
        /**
         * Orders dated the business date or earlier (an earlier date's leftovers whose last reference has
         * just gone).
         */
        ORDERS("orders", "DSCL", "DELETE FROM orders WHERE inbound_seq = ANY (ARRAY(SELECT o.inbound_seq FROM orders o"
                + " WHERE o.business_date <= ? AND o.status IN " + TERMINAL + " AND o.last_outbound_seq <= ?"
                + " AND o.inbound_seq < ?" + NOT_REFERENCED + " LIMIT ?))"),
        /** Orders outside any session, received before the close. */
        UNDATED_ORDERS("orders", "TSCL", "DELETE FROM orders WHERE inbound_seq = ANY (ARRAY(SELECT o.inbound_seq"
                + " FROM orders o WHERE o.business_date IS NULL AND o.received_ts < ? AND o.status IN " + TERMINAL
                + " AND o.last_outbound_seq <= ? AND o.inbound_seq < ?" + NOT_REFERENCED + " LIMIT ?))");

        final String table;
        final String params;
        final String sql;

        Step(String table, String params, String sql) {
            this.table = table;
            this.params = params;
            this.sql = sql;
        }
    }

    /** The tables a pass deletes from, in the order it VACUUMs them. */
    static final List<String> TABLES = List.of("order_audit", "executions", "orders");

    private static final String COMPLETE_RUNS = "SELECT business_date, close_outbound_seq, closed_ts, purged_ts,"
            + " purge_completed_ts FROM clearing_run WHERE status = 'COMPLETE' ORDER BY business_date";
    private static final String RUN_STATE = "SELECT status, close_outbound_seq, purged_ts FROM clearing_run"
            + " WHERE business_date = ?";
    private static final String NEWER_COMPLETE = "SELECT count(*) FROM clearing_run WHERE status = 'COMPLETE'"
            + " AND business_date > ?";
    private static final String PENDING_AT_OR_BEFORE = "SELECT min(business_date) FROM clearing_run"
            + " WHERE status = 'PENDING' AND business_date <= ?";
    private static final String CHECKPOINT = "SELECT last_inbound_seq, last_outbound_seq FROM reporter_checkpoint"
            + " WHERE id = 1";
    private static final String UNSYMBOLLED_FILLS = "SELECT min(e.outbound_seq) FROM executions e"
            + " LEFT JOIN orders o ON o.inbound_seq = e.order_inbound_seq"
            + " WHERE e.business_date = ? AND e.trade_id IS NOT NULL AND o.symbol IS NULL";
    // Exactly the aggregate /api/reports/daily computes (ReportStore.DAILY_SYMBOLS), so a purged date reads the same.
    private static final String WRITE_SUMMARY = "INSERT INTO daily_summary (business_date, symbol, trade_count, volume,"
            + " notional, open, high, low, close, first_trade_ts, last_trade_ts)"
            + " SELECT ?, o.symbol, count(*), sum(e.filled_quantity)::bigint,"
            + " sum(e.price::numeric * e.filled_quantity)::bigint,"
            + " (array_agg(e.price ORDER BY e.outbound_seq))[1], max(e.price), min(e.price),"
            + " (array_agg(e.price ORDER BY e.outbound_seq DESC))[1], min(e.event_ts), max(e.event_ts)"
            + " FROM executions e LEFT JOIN orders o ON o.inbound_seq = e.order_inbound_seq"
            + " WHERE e.business_date = ? AND e.trade_id IS NOT NULL GROUP BY o.symbol";
    private static final String MARK_PURGED = "UPDATE clearing_run SET purged_ts = ? WHERE business_date = ?"
            + " AND status = 'COMPLETE' AND purged_ts IS NULL";
    private static final String MARK_COMPLETED = "UPDATE clearing_run SET purge_completed_ts = ? WHERE business_date = ?"
            + " AND purged_ts IS NOT NULL AND purge_completed_ts IS NULL";
    private static final String PURGED_THROUGH = "SELECT max(business_date) FROM clearing_run"
            + " WHERE purge_completed_ts IS NOT NULL";

    private RetentionStore() {
    }

    /** Session settings for the retention connection: bounded statements and lock waits. */
    static void configure(Connection c, long statementTimeoutMs, long lockTimeoutMs) throws SQLException {
        c.setAutoCommit(true);
        try (Statement s = c.createStatement()) {
            s.execute("SET statement_timeout = " + statementTimeoutMs);
            s.execute("SET lock_timeout = " + lockTimeoutMs);
        }
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    /**
     * The eligible dates whose purge has not completed, oldest first: a date already started comes first
     * by its date like any other. Ends the read's transaction.
     */
    static List<Run> workList(Connection c, int days) throws SQLException {
        List<Integer> complete = new ArrayList<>();
        List<Run> all = new ArrayList<>();
        List<Boolean> completed = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(COMPLETE_RUNS); ResultSet r = s.executeQuery()) {
            while (r.next()) {
                int date = r.getInt(1);
                long close = r.getLong(2);
                long closedTs = r.getLong(3);
                r.getLong(4);
                boolean started = !r.wasNull();
                r.getLong(5);
                boolean done = !r.wasNull();
                complete.add(date);
                all.add(new Run(date, close, closedTs, started));
                completed.add(done);
            }
        }
        c.commit();
        int eligible = RetentionWindow.eligible(complete, days).size();
        List<Run> work = new ArrayList<>();
        for (int i = 0; i < eligible; i++) {
            if (!completed.get(i)) work.add(all.get(i));
        }
        return work;
    }

    /**
     * Re-checks, in the current transaction, that {@code run} may be purged; returns the Reporter's
     * committed inbound seq (orders at or above it are never deleted).
     *
     * @param started whether the date must already be marked purged
     */
    static long check(Connection c, Run run, int days, boolean started) throws SQLException {
        int date = run.businessDate();
        try (PreparedStatement s = c.prepareStatement(RUN_STATE)) {
            s.setInt(1, date);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new Skip(INCONSISTENT, "the clearing run for " + date + " is gone");
                if (!"COMPLETE".equals(r.getString(1))) {
                    throw new Skip(INCONSISTENT, "the clearing run for " + date + " is " + r.getString(1));
                }
                if (r.getLong(2) != run.closeOutboundSeq()) {
                    throw new Skip(INCONSISTENT, "the clearing run for " + date + " changed its close seq");
                }
                r.getLong(3);
                if (started && r.wasNull()) {
                    throw new Skip(INCONSISTENT, date + " is not marked purged");
                }
            }
        }
        try (PreparedStatement s = c.prepareStatement(NEWER_COMPLETE)) {
            s.setInt(1, date);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                long newer = r.getLong(1);
                if (newer < days) {
                    throw new Skip(INCONSISTENT, date + " has " + newer + " newer COMPLETE date(s), fewer than " + days);
                }
            }
        }
        try (PreparedStatement s = c.prepareStatement(PENDING_AT_OR_BEFORE)) {
            s.setInt(1, date);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                int pending = r.getInt(1);
                if (!r.wasNull()) {
                    throw new Skip(PENDING_EARLIER, "business date " + pending + " is still PENDING, at or before " + date);
                }
            }
        }
        try (PreparedStatement s = c.prepareStatement(CHECKPOINT); ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new Skip(INCONSISTENT, "reporter_checkpoint has no row");
            long inbound = r.getLong(1);
            long outbound = r.getLong(2);
            if (outbound < run.closeOutboundSeq()) {
                throw new Skip(CHECKPOINT_BEHIND, "the Reporter's checkpoint (outbound seq " + outbound
                        + ") is below the close of " + date + " (outbound seq " + run.closeOutboundSeq() + ")");
            }
            return inbound;
        }
    }

    /**
     * Writes the date's {@code daily_summary} rows from its executions and sets {@code purged_ts}, in one
     * transaction: from its commit the date answers 410 where detail rows are read, and its daily and
     * volume figures come from the summary.
     */
    static void markPurged(Connection c, Run run, int days, long nowTs) throws SQLException {
        try {
            check(c, run, days, false);
            int date = run.businessDate();
            try (PreparedStatement s = c.prepareStatement(UNSYMBOLLED_FILLS)) {
                s.setInt(1, date);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    long seq = r.getLong(1);
                    if (!r.wasNull()) {
                        throw new Skip(INCONSISTENT, "the fill at outbound seq " + seq + " of " + date
                                + " has no order symbol, so its summary cannot be kept");
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement(WRITE_SUMMARY)) {
                s.setInt(1, date);
                s.setInt(2, date);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement(MARK_PURGED)) {
                s.setLong(1, nowTs);
                s.setInt(2, date);
                if (s.executeUpdate() != 1) {
                    throw new Skip(INCONSISTENT, date + " was marked purged by another writer");
                }
            }
            c.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c, e);
            throw e;
        }
    }

    /** Deletes at most {@code limit} rows of {@code step} for {@code run}, in one transaction; returns how many. */
    static int deleteBatch(Connection c, Step step, Run run, int days, int limit) throws SQLException {
        try {
            long checkpointInbound = check(c, run, days, true);
            int deleted;
            try (PreparedStatement s = c.prepareStatement(step.sql)) {
                for (int i = 0; i < step.params.length(); i++) {
                    switch (step.params.charAt(i)) {
                        case 'D' -> s.setInt(i + 1, run.businessDate());
                        case 'T' -> s.setLong(i + 1, run.closedTs());
                        case 'S' -> s.setLong(i + 1, run.closeOutboundSeq());
                        case 'C' -> s.setLong(i + 1, checkpointInbound);
                        case 'L' -> s.setInt(i + 1, limit);
                        default -> throw new IllegalStateException("unmapped parameter in " + step);
                    }
                }
                deleted = s.executeUpdate();
            }
            c.commit();   // the deferred foreign keys are checked here: a reference left behind fails the batch
            return deleted;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c, e);
            throw e;
        }
    }

    /** Records that every batch of {@code run} has committed. */
    static void markCompleted(Connection c, Run run, int days, long nowTs) throws SQLException {
        try {
            check(c, run, days, true);
            try (PreparedStatement s = c.prepareStatement(MARK_COMPLETED)) {
                s.setLong(1, nowTs);
                s.setInt(2, run.businessDate());
                if (s.executeUpdate() != 1) {
                    throw new Skip(INCONSISTENT, run.businessDate() + " was marked complete by another writer");
                }
            }
            c.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c, e);
            throw e;
        }
    }

    /** The newest date whose purge completed, or -1. Ends the read's transaction. */
    static int purgedThrough(Connection c) throws SQLException {
        int date;
        try (PreparedStatement s = c.prepareStatement(PURGED_THROUGH); ResultSet r = s.executeQuery()) {
            r.next();
            date = r.getInt(1);
            if (r.wasNull()) date = -1;
        }
        c.commit();
        return date;
    }

    /**
     * {@code VACUUM (ANALYZE)} of {@code table}, never FULL: it blocks neither reads nor the Reporter's
     * writes, and makes the deleted rows' space reusable. It cannot run in a transaction and can outlast any
     * statement timeout, so it runs in autocommit with the timeout off for this statement only, and is
     * stopped by aborting the connection. {@code table} is one of {@link #TABLES}, never input.
     */
    static void vacuum(Connection c, String table, long statementTimeoutMs) throws SQLException {
        if (!TABLES.contains(table)) throw new IllegalArgumentException("not a retention table: " + table);
        c.commit();
        c.setAutoCommit(true);
        try (Statement s = c.createStatement()) {
            s.execute("SET statement_timeout = 0");
            try {
                s.execute("VACUUM (ANALYZE) " + table);
            } finally {
                s.execute("SET statement_timeout = " + statementTimeoutMs);
            }
        } finally {
            c.setAutoCommit(false);
        }
    }

    private static void rollbackQuietly(Connection c, Throwable cause) {
        try {
            c.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }
}
