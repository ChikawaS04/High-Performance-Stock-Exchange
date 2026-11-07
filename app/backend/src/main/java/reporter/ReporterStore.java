package reporter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * The Reporter's SQL (SRS 3.11, 3.13). A batch and its checkpoint commit in one transaction, and every
 * statement is safe to apply twice: inserts ignore a row already there by its own key only, so any
 * other constraint still raises; order updates and candle upserts apply only with a greater outbound
 * seq. Reporter thread only; the connection has autocommit off.
 */
final class ReporterStore {

    /** Which journal a checkpoint belongs to. Null fields: no commit yet. */
    record JournalIdentity(long inboundRecordingId, long inboundRecordingTs, long outboundRecordingId,
                           long outboundRecordingTs) {
    }

    /**
     * The checkpoint row.
     *
     * @param identity null until the first commit
     */
    record Checkpoint(long inboundSeq, long outboundSeq, long inboundPosition, long outboundPosition,
                      Integer sessionDate, String sessionSymbol, JournalIdentity identity) {
    }

    private static final String LOAD_CHECKPOINT = "SELECT last_inbound_seq, last_outbound_seq, inbound_position,"
            + " outbound_position, session_business_date, session_symbol, inbound_recording_id, inbound_recording_ts,"
            + " outbound_recording_id, outbound_recording_ts FROM reporter_checkpoint WHERE id = 1";

    private static final String LOAD_OPEN = "SELECT inbound_seq, order_id, quantity, cum_quantity FROM orders"
            + " WHERE status IN ('OPEN', 'PARTIALLY_FILLED')";

    private static final String LOAD_ORDER = "SELECT inbound_seq, order_id, quantity, cum_quantity FROM orders"
            + " WHERE inbound_seq = ?";

    private static final String MAX_EXECUTION = "SELECT max(outbound_seq) FROM executions";

    private static final String INSERT_ORDER = "INSERT INTO orders (inbound_seq, order_id, participant_id, business_date,"
            + " symbol, side, ord_type, time_in_force, limit_price, quantity, display_quantity, received_ts)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (inbound_seq) DO NOTHING";

    private static final String INSERT_EXECUTION = "INSERT INTO executions (outbound_seq, event_type, order_inbound_seq,"
            + " order_id, trade_id, price, filled_quantity, remaining_quantity, aggressor_order_id, passive_order_id,"
            + " passive_order_inbound_seq, aggressor_side, aggressor_participant_id, passive_participant_id,"
            + " business_date, event_ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (outbound_seq) DO NOTHING";

    private static final String INSERT_AUDIT = "INSERT INTO order_audit (stream, seq, order_inbound_seq, order_id, event,"
            + " business_date, event_ts, price, quantity, remaining_quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (stream, seq) DO NOTHING";

    private static final String UPDATE_ORDER = "UPDATE orders SET status = ?, cum_quantity = ?, last_outbound_seq = ?"
            + " WHERE inbound_seq = ? AND last_outbound_seq < ?";

    private static final String UPSERT_CANDLE = "INSERT INTO candle AS c (symbol, business_date, bucket_start_ts, open, high,"
            + " low, close, volume, trade_count, last_outbound_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)"
            + " ON CONFLICT (symbol, business_date, bucket_start_ts) DO UPDATE SET"
            + " high = greatest(c.high, EXCLUDED.high), low = least(c.low, EXCLUDED.low), close = EXCLUDED.close,"
            + " volume = c.volume + EXCLUDED.volume, trade_count = c.trade_count + 1,"
            + " last_outbound_seq = EXCLUDED.last_outbound_seq"
            + " WHERE c.last_outbound_seq < EXCLUDED.last_outbound_seq";

    private static final String UPDATE_CHECKPOINT = "UPDATE reporter_checkpoint SET last_inbound_seq = ?,"
            + " last_outbound_seq = ?, inbound_position = ?, outbound_position = ?, session_business_date = ?,"
            + " session_symbol = ?, inbound_recording_id = ?, inbound_recording_ts = ?, outbound_recording_id = ?,"
            + " outbound_recording_ts = ? WHERE id = 1 AND last_outbound_seq <= ?";

    private ReporterStore() {
    }

    /** Reads the checkpoint, ending the read's transaction. */
    static Checkpoint loadCheckpoint(Connection connection) throws SQLException {
        Checkpoint checkpoint;
        try (PreparedStatement statement = connection.prepareStatement(LOAD_CHECKPOINT);
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new ReporterInconsistencyException("reporter_checkpoint has no row: the migrations seed one");
            }
            JournalIdentity identity = null;
            long inboundId = row.getLong(7);
            if (!row.wasNull()) {
                identity = new JournalIdentity(inboundId, row.getLong(8), row.getLong(9), row.getLong(10));
            }
            int date = row.getInt(5);
            Integer sessionDate = row.wasNull() ? null : date;
            checkpoint = new Checkpoint(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4),
                    sessionDate, row.getString(6), identity);
        }
        connection.commit();
        return checkpoint;
    }

    /**
     * The attributor's starting state for {@code checkpoint}, read from the rows it covers.
     *
     * @throws ReporterInconsistencyException if executions exist beyond the checkpoint: a checkpoint
     *         rewound by hand, whose open orders would be read in a later state than it describes.
     *         A full reset to 0 is legal: there is no state to read, and re-applying changes nothing.
     */
    static EventAttributor.Resume loadResume(Connection connection, Checkpoint checkpoint) throws SQLException {
        if (checkpoint.outboundSeq() == 0L) {
            connection.commit();
            return new EventAttributor.Resume(checkpoint.inboundSeq(), 0L, checkpoint.sessionDate(),
                    checkpoint.sessionSymbol(), List.of(), null);
        }
        try (PreparedStatement statement = connection.prepareStatement(MAX_EXECUTION);
             ResultSet row = statement.executeQuery()) {
            row.next();
            long max = row.getLong(1);
            if (max > checkpoint.outboundSeq()) {
                throw new ReporterInconsistencyException("executions holds outbound seq " + max + " beyond the checkpoint's "
                        + checkpoint.outboundSeq() + ": the checkpoint was changed by hand. Restore it, or reset it to 0"
                        + " and let the Reporter re-apply the whole journal.");
            }
        }
        List<EventAttributor.OpenOrder> open = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(LOAD_OPEN);
             ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                open.add(openOrder(row));
            }
        }
        EventAttributor.OpenOrder current = null;
        try (PreparedStatement statement = connection.prepareStatement(LOAD_ORDER)) {
            statement.setLong(1, checkpoint.inboundSeq());
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    current = openOrder(row);
                }
            }
        }
        connection.commit();
        return new EventAttributor.Resume(checkpoint.inboundSeq(), checkpoint.outboundSeq(), checkpoint.sessionDate(),
                checkpoint.sessionSymbol(), open, current);
    }

    private static EventAttributor.OpenOrder openOrder(ResultSet row) throws SQLException {
        return new EventAttributor.OpenOrder(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4));
    }

    /**
     * Writes {@code batch} and {@code checkpoint}, runs {@code beforeCommit} (a test seam, may be null)
     * and commits. On any failure nothing is committed; the caller closes the connection.
     */
    static void apply(Connection connection, ReporterBatch batch, Checkpoint checkpoint, Runnable beforeCommit)
            throws SQLException {
        insertOrders(connection, batch);
        insertExecutions(connection, batch);
        insertAudit(connection, batch);
        updateOrders(connection, batch);
        upsertCandles(connection, batch);
        updateCheckpoint(connection, checkpoint);
        if (beforeCommit != null) {
            beforeCommit.run();
        }
        connection.commit();
    }

    private static void insertOrders(Connection connection, ReporterBatch batch) throws SQLException {
        if (batch.orders.isEmpty()) return;
        try (PreparedStatement s = connection.prepareStatement(INSERT_ORDER)) {
            for (ReporterBatch.OrderRow r : batch.orders) {
                s.setLong(1, r.inboundSeq());
                s.setLong(2, r.orderId());
                s.setLong(3, r.participantId());
                setInt(s, 4, r.businessDate());
                s.setString(5, r.symbol());
                s.setString(6, r.side());
                s.setString(7, r.ordType());
                s.setString(8, r.timeInForce());
                setLong(s, 9, r.limitPrice());
                s.setLong(10, r.quantity());
                setLong(s, 11, r.displayQuantity());
                s.setLong(12, r.receivedTs());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static void insertExecutions(Connection connection, ReporterBatch batch) throws SQLException {
        if (batch.executions.isEmpty()) return;
        try (PreparedStatement s = connection.prepareStatement(INSERT_EXECUTION)) {
            for (ReporterBatch.ExecutionRow r : batch.executions) {
                s.setLong(1, r.outboundSeq());
                s.setString(2, r.eventType());
                setLong(s, 3, r.orderInboundSeq());
                s.setLong(4, r.orderId());
                setLong(s, 5, r.tradeId());
                setLong(s, 6, r.price());
                setLong(s, 7, r.filledQuantity());
                setLong(s, 8, r.remainingQuantity());
                setLong(s, 9, r.aggressorOrderId());
                setLong(s, 10, r.passiveOrderId());
                setLong(s, 11, r.passiveOrderInboundSeq());
                s.setString(12, r.aggressorSide());
                setLong(s, 13, r.aggressorParticipantId());
                setLong(s, 14, r.passiveParticipantId());
                setInt(s, 15, r.businessDate());
                s.setLong(16, r.eventTs());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static void insertAudit(Connection connection, ReporterBatch batch) throws SQLException {
        if (batch.audit.isEmpty()) return;
        try (PreparedStatement s = connection.prepareStatement(INSERT_AUDIT)) {
            for (ReporterBatch.AuditRow r : batch.audit) {
                s.setString(1, r.stream());
                s.setLong(2, r.seq());
                setLong(s, 3, r.orderInboundSeq());
                s.setLong(4, r.orderId());
                s.setString(5, r.event());
                setInt(s, 6, r.businessDate());
                s.setLong(7, r.eventTs());
                setLong(s, 8, r.price());
                setLong(s, 9, r.quantity());
                setLong(s, 10, r.remainingQuantity());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    /** After the inserts, so a NEW and its first event can share a batch. */
    private static void updateOrders(Connection connection, ReporterBatch batch) throws SQLException {
        if (batch.orderUpdates.isEmpty()) return;
        try (PreparedStatement s = connection.prepareStatement(UPDATE_ORDER)) {
            for (ReporterBatch.OrderUpdate r : batch.orderUpdates) {
                s.setString(1, r.status());
                s.setLong(2, r.cumQuantity());
                s.setLong(3, r.outboundSeq());
                s.setLong(4, r.inboundSeq());
                s.setLong(5, r.outboundSeq());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    /** One statement per fill, in seq order, so the guard is exact for any overlap with an earlier apply. */
    private static void upsertCandles(Connection connection, ReporterBatch batch) throws SQLException {
        if (batch.candleFills.isEmpty()) return;
        try (PreparedStatement s = connection.prepareStatement(UPSERT_CANDLE)) {
            for (ReporterBatch.CandleFill r : batch.candleFills) {
                s.setString(1, r.symbol());
                s.setInt(2, r.businessDate());
                s.setLong(3, r.bucketStartTs());
                s.setLong(4, r.price());
                s.setLong(5, r.price());
                s.setLong(6, r.price());
                s.setLong(7, r.price());
                s.setLong(8, r.quantity());
                s.setLong(9, r.outboundSeq());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static void updateCheckpoint(Connection connection, Checkpoint c) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(UPDATE_CHECKPOINT)) {
            s.setLong(1, c.inboundSeq());
            s.setLong(2, c.outboundSeq());
            s.setLong(3, c.inboundPosition());
            s.setLong(4, c.outboundPosition());
            setInt(s, 5, c.sessionDate());
            s.setString(6, c.sessionSymbol());
            JournalIdentity id = c.identity();
            s.setLong(7, id.inboundRecordingId());
            s.setLong(8, id.inboundRecordingTs());
            s.setLong(9, id.outboundRecordingId());
            s.setLong(10, id.outboundRecordingTs());
            s.setLong(11, c.outboundSeq());
            if (s.executeUpdate() != 1) {
                throw new ReporterInconsistencyException("The checkpoint is already beyond outbound seq " + c.outboundSeq()
                        + ": another Reporter is writing to this database");
            }
        }
    }

    private static void setLong(PreparedStatement s, int index, Long value) throws SQLException {
        if (value == null) s.setNull(index, Types.BIGINT); else s.setLong(index, value);
    }

    private static void setInt(PreparedStatement s, int index, Integer value) throws SQLException {
        if (value == null) s.setNull(index, Types.INTEGER); else s.setInt(index, value);
    }

    /**
     * Whether {@code failure} says the data itself is wrong, so retrying cannot help: SQLState class 23
     * (integrity constraint) or 22 (data exception). A batch failure reports its own state or that of
     * the statement it chains to.
     */
    static boolean isDataFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException e = sql; e != null; e = e.getNextException()) {
                    String state = e.getSQLState();
                    if (state != null && (state.startsWith("23") || state.startsWith("22"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
