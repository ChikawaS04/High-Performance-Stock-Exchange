package clearing;

import model.Side;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * The clearing run for one business date (SRS 3.12), in one transaction: lock the run, check it may
 * proceed, net the fills, roll balances and positions forward, write the results and mark it COMPLETE.
 * A COMPLETE run is returned as stored and nothing is written, so a re-run changes nothing.
 *
 * <p>The caller's connection has autocommit off and REPEATABLE READ isolation, so every read of a run
 * sees one snapshot. Every return ends the transaction: commit after writing, rollback otherwise.
 *
 * <p>Rows dated D in {@code account_balance}, {@code account_position} and {@code house_position} hold
 * the opening values of D. The run for D reads, per account (and symbol), the row with the greatest
 * date at or before D, or zero, and writes the closing values as the rows of the next business date.
 */
public final class ClearingStore {

    /** What {@link #clear} did. */
    public enum Kind {
        /** The run was PENDING and is now COMPLETE. */
        COMPLETED,
        /** The run was already COMPLETE; nothing was written. */
        ALREADY_COMPLETE,
        /** The Reporter's checkpoint has not reached the close yet; nothing was written. */
        WAITING_FOR_REPORTER,
        /** An earlier date is still PENDING; nothing was written. */
        EARLIER_PENDING,
        /** No run exists for the date: the Reporter has not recorded its close. */
        NO_RUN
    }

    /**
     * @param result         the stored result for COMPLETED and ALREADY_COMPLETE, else null
     * @param checkpointSeq  the Reporter's last outbound seq as read, or -1 if not read
     * @param closeSeq       the run's SESSION_CLOSED seq, or -1 without a run
     */
    public record Attempt(Kind kind, ClearingResult result, long checkpointSeq, long closeSeq) {
    }

    private static final String FILL_TYPES = "('ORDER_FILLED', 'ORDER_PARTIALLY_FILLED')";

    private static final String LOCK_RUN = "SELECT status, close_outbound_seq FROM clearing_run WHERE business_date = ?"
            + " FOR UPDATE";
    private static final String EARLIER_PENDING = "SELECT min(business_date) FROM clearing_run WHERE status = 'PENDING'"
            + " AND business_date < ?";
    private static final String CHECKPOINT = "SELECT last_outbound_seq FROM reporter_checkpoint WHERE id = 1";
    private static final String UNDATED_FILLS = "SELECT min(outbound_seq) FROM executions WHERE event_type IN "
            + FILL_TYPES + " AND business_date IS NULL";
    private static final String AFTER_CLOSE = "SELECT min(outbound_seq) FROM executions WHERE business_date = ?"
            + " AND outbound_seq > ?";
    // Only dates after the last COMPLETE run before D: anything earlier passed this check when that run did.
    private static final String UNCLEARED_EARLIER = "SELECT min(e.business_date) FROM executions e"
            + " WHERE e.business_date < ? AND e.business_date > coalesce((SELECT max(business_date) FROM clearing_run"
            + " WHERE status = 'COMPLETE' AND business_date < ?), 0)"
            + " AND NOT EXISTS (SELECT 1 FROM clearing_run r WHERE r.business_date = e.business_date)";
    private static final String ROWS_AFTER = "SELECT (SELECT count(*) FROM account_balance WHERE business_date > ?)"
            + " + (SELECT count(*) FROM account_position WHERE business_date > ?)"
            + " + (SELECT count(*) FROM house_position WHERE business_date > ?)";
    private static final String PARTICIPANTS = "SELECT p.participant_id, p.participant_type, a.account_id"
            + " FROM participant p LEFT JOIN account a ON a.participant_id = p.participant_id";
    private static final String FILLS = "SELECT e.outbound_seq, e.trade_id, e.price, e.filled_quantity, e.aggressor_side,"
            + " e.aggressor_participant_id, e.passive_participant_id, o.symbol FROM executions e"
            + " LEFT JOIN orders o ON o.inbound_seq = e.order_inbound_seq"
            + " WHERE e.business_date = ? AND e.event_type IN " + FILL_TYPES + " ORDER BY e.outbound_seq";
    private static final String FILL_TOTALS = "SELECT count(*), coalesce(sum(filled_quantity), 0)::bigint,"
            + " count(DISTINCT trade_id) FROM executions WHERE business_date = ? AND event_type IN " + FILL_TYPES;
    private static final String NON_TRADING = "SELECT business_date FROM trading_calendar WHERE business_date > ?"
            + " AND NOT is_trading_day";
    private static final String OPENING_BALANCES = "SELECT DISTINCT ON (account_id) account_id, cash FROM account_balance"
            + " WHERE business_date <= ? ORDER BY account_id, business_date DESC";
    private static final String OPENING_POSITIONS = "SELECT DISTINCT ON (account_id, symbol) account_id, symbol, quantity"
            + " FROM account_position WHERE business_date <= ? ORDER BY account_id, symbol, business_date DESC";
    private static final String OPENING_HOUSE = "SELECT DISTINCT ON (symbol) symbol, quantity FROM house_position"
            + " WHERE business_date <= ? ORDER BY symbol, business_date DESC";
    private static final String INSERT_OBLIGATION = "INSERT INTO clearing_obligation (business_date, participant_id,"
            + " symbol, bought_quantity, sold_quantity, net_quantity, cash_obligation) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_BALANCE = "INSERT INTO account_balance (account_id, business_date, cash)"
            + " VALUES (?, ?, ?)";
    private static final String INSERT_POSITION = "INSERT INTO account_position (account_id, business_date, symbol,"
            + " quantity) VALUES (?, ?, ?, ?)";
    private static final String INSERT_HOUSE = "INSERT INTO house_position (business_date, symbol, quantity)"
            + " VALUES (?, ?, ?)";
    private static final String COMPLETE_RUN = "UPDATE clearing_run SET status = 'COMPLETE', started_ts = ?,"
            + " completed_ts = ?, next_business_date = ?, fill_count = ?, volume = ? WHERE business_date = ?"
            + " AND status = 'PENDING'";
    private static final String LOAD_RUN = "SELECT close_outbound_seq, next_business_date, fill_count, volume"
            + " FROM clearing_run WHERE business_date = ? AND status = 'COMPLETE'";
    private static final String LOAD_OBLIGATIONS = "SELECT participant_id, symbol, bought_quantity, sold_quantity,"
            + " net_quantity, cash_obligation FROM clearing_obligation WHERE business_date = ?"
            + " ORDER BY participant_id, symbol";
    private static final String PENDING_DATES = "SELECT business_date FROM clearing_run WHERE status = 'PENDING'"
            + " ORDER BY business_date";
    private static final String LAST_COMPLETE = "SELECT max(business_date) FROM clearing_run WHERE status = 'COMPLETE'";
    private static final String RUN_EXISTS = "SELECT 1 FROM clearing_run WHERE business_date = ?";

    private static final int FETCH_SIZE = 1_000;

    private ClearingStore() {
    }

    /** The PENDING dates, ascending. Ends the read's transaction. */
    public static List<Integer> pendingDates(Connection connection) throws SQLException {
        List<Integer> dates = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement(PENDING_DATES); ResultSet row = s.executeQuery()) {
            while (row.next()) {
                dates.add(row.getInt(1));
            }
        }
        connection.commit();
        return dates;
    }

    /** The latest COMPLETE date, or -1. Ends the read's transaction. */
    public static int lastCompleteDate(Connection connection) throws SQLException {
        int date;
        try (PreparedStatement s = connection.prepareStatement(LAST_COMPLETE); ResultSet row = s.executeQuery()) {
            row.next();
            date = row.getInt(1);
            if (row.wasNull()) date = -1;
        }
        connection.commit();
        return date;
    }

    /** Whether a run of any status exists for {@code businessDate}. Ends the read's transaction. */
    public static boolean runExists(Connection connection, int businessDate) throws SQLException {
        boolean exists;
        try (PreparedStatement s = connection.prepareStatement(RUN_EXISTS)) {
            s.setInt(1, businessDate);
            try (ResultSet row = s.executeQuery()) {
                exists = row.next();
            }
        }
        connection.commit();
        return exists;
    }

    /**
     * Clears {@code businessDate}, or returns why it cannot yet.
     *
     * @param epochNanos the wall clock for {@code started_ts} and {@code completed_ts}; no result value
     *                   depends on it
     * @throws ClearingInvariantException if a rule clearing relies on is broken; nothing is written
     */
    public static Attempt clear(Connection connection, int businessDate, LongSupplier epochNanos)
            throws SQLException {
        try {
            Attempt attempt = clearInTransaction(connection, businessDate, epochNanos);
            if (attempt.kind() == Kind.COMPLETED) {
                connection.commit();
            } else {
                connection.rollback();
            }
            return attempt;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static Attempt clearInTransaction(Connection connection, int date, LongSupplier epochNanos)
            throws SQLException {
        long startedTs = epochNanos.getAsLong();

        // 1. The run, locked: a second clearing of the same date waits here and then sees COMPLETE.
        String status;
        long closeSeq;
        try (PreparedStatement s = connection.prepareStatement(LOCK_RUN)) {
            s.setInt(1, date);
            try (ResultSet row = s.executeQuery()) {
                if (!row.next()) {
                    return new Attempt(Kind.NO_RUN, null, -1L, -1L);
                }
                status = row.getString(1);
                closeSeq = row.getLong(2);
            }
        }
        if ("COMPLETE".equals(status)) {
            return new Attempt(Kind.ALREADY_COMPLETE, loadResult(connection, date), -1L, closeSeq);
        }

        // 2. Days clear in order: each roll forward starts from the previous day's result.
        Integer earlier = queryInt(connection, EARLIER_PENDING, date);
        if (earlier != null) {
            return new Attempt(Kind.EARLIER_PENDING, null, -1L, closeSeq);
        }

        // 3. The Reporter has applied every execution up to the close (SRS 3.12 step 1).
        long checkpoint = queryLong(connection, CHECKPOINT);
        if (checkpoint < closeSeq) {
            return new Attempt(Kind.WAITING_FOR_REPORTER, null, checkpoint, closeSeq);
        }

        // 4. Invariants that need no netting.
        Long undated = queryLongOrNull(connection, UNDATED_FILLS);
        if (undated != null) {
            throw new ClearingInvariantException("The fill at outbound seq " + undated + " has no business date, so"
                    + " no clearing run can account for it");
        }
        Long afterClose = queryLongOrNull(connection, AFTER_CLOSE, date, closeSeq);
        if (afterClose != null) {
            throw new ClearingInvariantException("Business date " + date + " closed at outbound seq " + closeSeq
                    + " but holds an execution at outbound seq " + afterClose);
        }
        Integer uncleared = queryInt(connection, UNCLEARED_EARLIER, date, date);
        if (uncleared != null) {
            throw new ClearingInvariantException("Business date " + uncleared + " has executions but no clearing run, so "
                    + date + " cannot roll forward from it. Its close was consumed before clearing existed: reset the"
                    + " Reporter's checkpoint to 0 so it re-applies the journal and records every close.");
        }
        long after = queryLong(connection, ROWS_AFTER, date, date, date);
        if (after != 0L) {
            throw new ClearingInvariantException(after + " balance or position row(s) are dated after " + date
                    + ": a run already rolled past it, so the trading calendar or the accounts changed after that run."
                    + " A completed run is never recomputed.");
        }

        Participants participants = loadParticipants(connection);

        // 5. Netting (SRS 3.12 step 2).
        Netting netting = new Netting();
        try (PreparedStatement s = connection.prepareStatement(FILLS)) {
            s.setFetchSize(FETCH_SIZE);
            s.setInt(1, date);
            try (ResultSet row = s.executeQuery()) {
                while (row.next()) {
                    long seq = row.getLong(1);
                    requireNotNull(row, 2, seq, "trade_id");
                    long price = requireLong(row, 3, seq, "price");
                    long quantity = requireLong(row, 4, seq, "filled_quantity");
                    String side = row.getString(5);
                    if (side == null) throw missing(seq, "aggressor_side");
                    long aggressor = requireLong(row, 6, seq, "aggressor_participant_id");
                    long passive = requireLong(row, 7, seq, "passive_participant_id");
                    String symbol = row.getString(8);
                    if (symbol == null) throw missing(seq, "the order's symbol");
                    netting.add(seq, symbol, price, quantity, Side.valueOf(side), aggressor, passive);
                }
            }
        }
        long[] totals = queryLongs(connection, FILL_TOTALS, 3, date);
        if (totals[0] != netting.fills() || totals[1] != netting.volume() || totals[2] != netting.fills()) {
            throw new ClearingInvariantException("Business date " + date + " netted " + netting.fills() + " fills and volume "
                    + netting.volume() + ", but executions holds " + totals[0] + " fills, volume " + totals[1] + " and "
                    + totals[2] + " distinct trade ids");
        }
        List<Obligation> obligations = netting.obligations();

        // 6. Roll forward (SRS 3.12 step 3).
        int next = BusinessCalendar.next(date, queryInts(connection, NON_TRADING, date));
        Map<Long, Long> cash = new TreeMap<>();            // account -> closing cash
        Map<Long, Map<String, Long>> positions = new TreeMap<>();  // account -> symbol -> closing quantity
        Map<String, Long> house = new TreeMap<>();
        for (long account : participants.externalAccounts.values()) {
            cash.put(account, 0L);
            positions.put(account, new TreeMap<>());
        }
        loadOpening(connection, date, participants, cash, positions, house);
        for (Obligation o : obligations) {
            if (o.participantId() == participants.internal) {
                house.merge(o.symbol(), o.netQuantity(), ClearingStore::add);
                continue;
            }
            Long account = participants.externalAccounts.get(o.participantId());
            if (account == null) {
                throw new ClearingInvariantException("Participant " + o.participantId() + " traded on " + date
                        + " but has no account, so its obligation cannot roll forward");
            }
            cash.merge(account, negate(o.cashObligation()), ClearingStore::add);
            positions.get(account).merge(o.symbol(), o.netQuantity(), ClearingStore::add);
        }

        // 7. Results, then COMPLETE (SRS 3.12 step 4).
        try (PreparedStatement s = connection.prepareStatement(INSERT_OBLIGATION)) {
            for (Obligation o : obligations) {
                s.setInt(1, date);
                s.setLong(2, o.participantId());
                s.setString(3, o.symbol());
                s.setLong(4, o.boughtQuantity());
                s.setLong(5, o.soldQuantity());
                s.setLong(6, o.netQuantity());
                s.setLong(7, o.cashObligation());
                s.addBatch();
            }
            s.executeBatch();
        }
        try (PreparedStatement s = connection.prepareStatement(INSERT_BALANCE)) {
            for (Map.Entry<Long, Long> e : cash.entrySet()) {
                s.setLong(1, e.getKey());
                s.setInt(2, next);
                s.setLong(3, e.getValue());
                s.addBatch();
            }
            s.executeBatch();
        }
        try (PreparedStatement s = connection.prepareStatement(INSERT_POSITION)) {
            for (Map.Entry<Long, Map<String, Long>> account : positions.entrySet()) {
                for (Map.Entry<String, Long> p : account.getValue().entrySet()) {
                    s.setLong(1, account.getKey());
                    s.setInt(2, next);
                    s.setString(3, p.getKey());
                    s.setLong(4, p.getValue());
                    s.addBatch();
                }
            }
            s.executeBatch();
        }
        try (PreparedStatement s = connection.prepareStatement(INSERT_HOUSE)) {
            for (Map.Entry<String, Long> p : house.entrySet()) {
                s.setInt(1, next);
                s.setString(2, p.getKey());
                s.setLong(3, p.getValue());
                s.addBatch();
            }
            s.executeBatch();
        }
        try (PreparedStatement s = connection.prepareStatement(COMPLETE_RUN)) {
            s.setLong(1, startedTs);
            s.setLong(2, epochNanos.getAsLong());
            s.setInt(3, next);
            s.setLong(4, netting.fills());
            s.setLong(5, netting.volume());
            s.setInt(6, date);
            if (s.executeUpdate() != 1) {
                throw new ClearingInvariantException("The run for " + date + " stopped being PENDING while locked");
            }
        }
        return new Attempt(Kind.COMPLETED, new ClearingResult(date, closeSeq, next, netting.fills(), netting.volume(),
                obligations), checkpoint, closeSeq);
    }

    /** The single INTERNAL participant, and each EXTERNAL participant's account. */
    private static final class Participants {
        long internal = -1L;
        final Map<Long, Long> externalAccounts = new HashMap<>();
    }

    private static Participants loadParticipants(Connection connection) throws SQLException {
        Participants p = new Participants();
        try (PreparedStatement s = connection.prepareStatement(PARTICIPANTS); ResultSet row = s.executeQuery()) {
            while (row.next()) {
                long id = row.getLong(1);
                String type = row.getString(2);
                long account = row.getLong(3);
                boolean hasAccount = !row.wasNull();
                if ("INTERNAL".equals(type)) {
                    if (hasAccount) {
                        throw new ClearingInvariantException("The internal participant " + id + " has account " + account
                                + ": its position is the house position, rolled without cash, never an account");
                    }
                    p.internal = id;
                } else {
                    if (hasAccount) {
                        p.externalAccounts.put(id, account);
                    }
                }
            }
        }
        if (p.internal < 0L) {
            throw new ClearingInvariantException("No INTERNAL participant exists: V1 seeds participant 1");
        }
        return p;
    }

    /** Opening values: per account (and symbol), the latest row at or before {@code date}; zero otherwise. */
    private static void loadOpening(Connection connection, int date, Participants participants, Map<Long, Long> cash,
                                    Map<Long, Map<String, Long>> positions, Map<String, Long> house) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(OPENING_BALANCES)) {
            s.setInt(1, date);
            try (ResultSet row = s.executeQuery()) {
                while (row.next()) {
                    cash.put(requireExternal(cash, row.getLong(1)), row.getLong(2));
                }
            }
        }
        try (PreparedStatement s = connection.prepareStatement(OPENING_POSITIONS)) {
            s.setInt(1, date);
            try (ResultSet row = s.executeQuery()) {
                while (row.next()) {
                    long account = requireExternal(cash, row.getLong(1));
                    positions.get(account).put(row.getString(2), row.getLong(3));
                }
            }
        }
        try (PreparedStatement s = connection.prepareStatement(OPENING_HOUSE)) {
            s.setInt(1, date);
            try (ResultSet row = s.executeQuery()) {
                while (row.next()) {
                    house.put(row.getString(1), row.getLong(2));
                }
            }
        }
    }

    /** Only external accounts exist (the internal participant has none, checked), so any other is a bug. */
    private static long requireExternal(Map<Long, Long> externalAccounts, long account) {
        if (!externalAccounts.containsKey(account)) {
            throw new ClearingInvariantException("Account " + account + " has rows but belongs to no external participant");
        }
        return account;
    }

    /** The stored result of a COMPLETE run. */
    public static ClearingResult loadResult(Connection connection, int date) throws SQLException {
        long closeSeq;
        int next;
        long fills;
        long volume;
        try (PreparedStatement s = connection.prepareStatement(LOAD_RUN)) {
            s.setInt(1, date);
            try (ResultSet row = s.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                closeSeq = row.getLong(1);
                next = row.getInt(2);
                fills = row.getLong(3);
                volume = row.getLong(4);
            }
        }
        List<Obligation> obligations = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement(LOAD_OBLIGATIONS)) {
            s.setInt(1, date);
            try (ResultSet row = s.executeQuery()) {
                while (row.next()) {
                    obligations.add(new Obligation(row.getLong(1), row.getString(2), row.getLong(3), row.getLong(4),
                            row.getLong(5), row.getLong(6)));
                }
            }
        }
        return new ClearingResult(date, closeSeq, next, fills, volume, obligations);
    }

    private static long add(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new ClearingInvariantException("Rolling forward overflows a long: amounts are never wrapped", e);
        }
    }

    private static long negate(long a) {
        try {
            return Math.negateExact(a);
        } catch (ArithmeticException e) {
            throw new ClearingInvariantException("Rolling forward overflows a long: amounts are never wrapped", e);
        }
    }

    private static ClearingInvariantException missing(long seq, String column) {
        return new ClearingInvariantException("The fill at outbound seq " + seq + " has no " + column);
    }

    private static void requireNotNull(ResultSet row, int column, long seq, String name) throws SQLException {
        row.getLong(column);
        if (row.wasNull()) throw missing(seq, name);
    }

    private static long requireLong(ResultSet row, int column, long seq, String name) throws SQLException {
        long value = row.getLong(column);
        if (row.wasNull()) throw missing(seq, name);
        return value;
    }

    /** Binds every parameter as a bigint; PostgreSQL compares it with the int date columns exactly. */
    private static PreparedStatement bind(Connection connection, String sql, long... params) throws SQLException {
        PreparedStatement s = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            s.setLong(i + 1, params[i]);
        }
        return s;
    }

    private static long[] queryLongs(Connection connection, String sql, int columns, long... params) throws SQLException {
        try (PreparedStatement s = bind(connection, sql, params); ResultSet row = s.executeQuery()) {
            row.next();
            long[] values = new long[columns];
            for (int i = 0; i < columns; i++) {
                values[i] = row.getLong(i + 1);
            }
            return values;
        }
    }

    private static long queryLong(Connection connection, String sql, long... params) throws SQLException {
        Long value = queryLongOrNull(connection, sql, params);
        if (value == null) {
            throw new ClearingInvariantException("No value from: " + sql);
        }
        return value;
    }

    private static Long queryLongOrNull(Connection connection, String sql, long... params) throws SQLException {
        try (PreparedStatement s = bind(connection, sql, params); ResultSet row = s.executeQuery()) {
            if (!row.next()) return null;
            long value = row.getLong(1);
            return row.wasNull() ? null : value;
        }
    }

    private static Integer queryInt(Connection connection, String sql, long... params) throws SQLException {
        Long value = queryLongOrNull(connection, sql, params);
        return value == null ? null : Math.toIntExact(value);
    }

    private static Set<Integer> queryInts(Connection connection, String sql, long... params) throws SQLException {
        Set<Integer> values = new TreeSet<>();
        try (PreparedStatement s = bind(connection, sql, params); ResultSet row = s.executeQuery()) {
            while (row.next()) {
                values.add(row.getInt(1));
            }
        }
        return values;
    }
}
