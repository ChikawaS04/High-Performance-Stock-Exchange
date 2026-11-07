package reporter;

import db.RequiresTestDatabase;
import db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Reporter's SQL against {@code exchange_test}: idempotency, the transaction, classification. */
@RequiresTestDatabase
class ReporterStoreTest {

    private static final int DAY = 20261107;
    private static final long BUCKET = 29_000_000L * Candles.BUCKET_NANOS;
    private static final ReporterStore.JournalIdentity JOURNAL = new ReporterStore.JournalIdentity(1L, 111L, 0L, 222L);

    @BeforeEach
    void emptySchema() {
        TestDatabase.cleanAndMigrate();
    }

    private static ReporterBatch.OrderRow order(long seq, long id) {
        return new ReporterBatch.OrderRow(seq, id, 1L, DAY, "ASML", "BUY", "LIMIT", "GTC", 1_000_000L, 10L, null, 5L);
    }

    private static ReporterBatch.ExecutionRow fillRow(long seq, Long orderSeq, Long passiveSeq, long tradeId) {
        return new ReporterBatch.ExecutionRow(seq, "ORDER_FILLED", orderSeq, 7L, tradeId, 1_000_000L, 10L, 0L, 7L, 8L,
                passiveSeq, "BUY", 1L, 1L, DAY, 6L);
    }

    /** A batch: two NEWs and a fill between them, with its updates, audit and candle. */
    private static ReporterBatch dayBatch() {
        ReporterBatch batch = new ReporterBatch();
        batch.orders.add(order(1L, 8L));
        batch.orders.add(order(2L, 7L));
        batch.executions.add(fillRow(1L, 2L, 1L, 100L));
        batch.orderUpdates.add(new ReporterBatch.OrderUpdate(1L, "FILLED", 10L, 1L));
        batch.orderUpdates.add(new ReporterBatch.OrderUpdate(2L, "FILLED", 10L, 1L));
        batch.audit.add(new ReporterBatch.AuditRow("IN", 2L, 2L, 7L, "NEW_ORDER", DAY, 5L, 1_000_000L, 10L, null));
        batch.audit.add(new ReporterBatch.AuditRow("OUT", 1L, 2L, 7L, "ORDER_FILLED", DAY, 6L, 1_000_000L, 10L, 0L));
        batch.candleFills.add(new ReporterBatch.CandleFill("ASML", DAY, BUCKET, 1_000_000L, 10L, 1L));
        return batch;
    }

    private static ReporterStore.Checkpoint checkpoint(long in, long out) {
        return new ReporterStore.Checkpoint(in, out, in * 96, out * 160, DAY, "ASML", JOURNAL);
    }

    private static Connection connection() throws SQLException {
        Connection connection = TestDatabase.database().connect();
        connection.setAutoCommit(false);
        return connection;
    }

    private static void apply(ReporterBatch batch, ReporterStore.Checkpoint checkpoint) throws SQLException {
        try (Connection connection = connection()) {
            ReporterStore.apply(connection, batch, checkpoint, null);
        }
    }

    private static String dump() {
        return TestDatabase.queryOne("SELECT (SELECT coalesce(string_agg(o::text, ';' ORDER BY inbound_seq), '') FROM orders o)"
                + " || '|' || (SELECT coalesce(string_agg(e::text, ';' ORDER BY outbound_seq), '') FROM executions e)"
                + " || '|' || (SELECT coalesce(string_agg(a::text, ';' ORDER BY stream, seq), '') FROM order_audit a)"
                + " || '|' || (SELECT coalesce(string_agg(c::text, ';' ORDER BY bucket_start_ts), '') FROM candle c)");
    }

    private static ReporterBatch closeBatch(long closeSeq) {
        ReporterBatch batch = new ReporterBatch();
        batch.pendingRuns.add(new ReporterBatch.PendingRun(DAY, closeSeq, 77L));
        return batch;
    }

    @Test
    void aClosedDateIsRecordedAsAPendingRunInTheCheckpointsTransaction() throws SQLException {
        try (Connection connection = connection()) {
            assertThrows(IllegalStateException.class, () -> ReporterStore.apply(connection, closeBatch(3L),
                    checkpoint(2L, 3L), () -> { throw new IllegalStateException("crash before commit"); }));
        }
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM clearing_run"), "rolled back with the checkpoint");
        apply(closeBatch(3L), checkpoint(2L, 3L));
        assertEquals(DAY + " PENDING 3 77 3", TestDatabase.queryOne("SELECT r.business_date || ' ' || r.status || ' '"
                + " || r.close_outbound_seq || ' ' || r.closed_ts || ' ' || c.last_outbound_seq"
                + " FROM clearing_run r, reporter_checkpoint c"));
    }

    @Test
    void reRecordingTheSameCloseChangesNothing() throws SQLException {
        apply(closeBatch(3L), checkpoint(2L, 3L));
        String before = TestDatabase.queryOne("SELECT xmin::text || ' ' || r::text FROM clearing_run r");
        apply(closeBatch(3L), checkpoint(2L, 3L));
        assertEquals(before, TestDatabase.queryOne("SELECT xmin::text || ' ' || r::text FROM clearing_run r"));
    }

    @Test
    void aDifferentCloseForARecordedDateStops() throws SQLException {
        apply(closeBatch(3L), checkpoint(2L, 3L));
        ReporterInconsistencyException e = assertThrows(ReporterInconsistencyException.class,
                () -> apply(closeBatch(9L), checkpoint(5L, 9L)));
        assertTrue(e.getMessage().contains("outbound seq 3"), e.getMessage());
        assertEquals("3", TestDatabase.queryOne("SELECT close_outbound_seq FROM clearing_run"));
        assertEquals("3", TestDatabase.queryOne("SELECT last_outbound_seq FROM reporter_checkpoint"));
    }

    @Test
    void aBatchAndItsCheckpointCommitTogether() throws SQLException {
        apply(dayBatch(), checkpoint(2L, 1L));
        assertEquals("2", TestDatabase.queryOne("SELECT count(*) FROM orders"));
        assertEquals("FILLED 10 1", TestDatabase.queryOne(
                "SELECT status || ' ' || cum_quantity || ' ' || last_outbound_seq FROM orders WHERE inbound_seq = 2"));
        try (Connection connection = connection()) {
            ReporterStore.Checkpoint loaded = ReporterStore.loadCheckpoint(connection);
            assertEquals(checkpoint(2L, 1L), loaded);
        }
    }

    @Test
    void aFailureBeforeCommitLeavesNothing() throws SQLException {
        try (Connection connection = connection()) {
            assertThrows(IllegalStateException.class, () -> ReporterStore.apply(connection, dayBatch(), checkpoint(2L, 1L),
                    () -> { throw new IllegalStateException("killed between write and commit"); }));
        }
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM orders"));
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM executions"));
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM candle"));
        assertEquals("0 0", TestDatabase.queryOne("SELECT last_inbound_seq || ' ' || last_outbound_seq FROM reporter_checkpoint"));
    }

    @Test
    void reApplyingABatchChangesNothing() throws SQLException {
        apply(dayBatch(), checkpoint(2L, 1L));
        String before = dump();
        apply(dayBatch(), checkpoint(2L, 1L));
        assertEquals(before, dump());
    }

    @Test
    void anOlderUpdateNeverOverwritesANewerOne() throws SQLException {
        apply(dayBatch(), checkpoint(2L, 1L));
        ReporterBatch later = new ReporterBatch();
        later.orderUpdates.add(new ReporterBatch.OrderUpdate(1L, "CANCELLED", 10L, 5L));
        apply(later, checkpoint(2L, 5L));
        ReporterBatch older = new ReporterBatch();
        older.orderUpdates.add(new ReporterBatch.OrderUpdate(1L, "FILLED", 3L, 4L));
        apply(older, checkpoint(2L, 5L));
        assertEquals("CANCELLED 10 5", TestDatabase.queryOne(
                "SELECT status || ' ' || cum_quantity || ' ' || last_outbound_seq FROM orders WHERE inbound_seq = 1"));
    }

    @Test
    void aFillAtOrBelowACandlesSeqIsAlreadyIncluded() throws SQLException {
        for (int i = 0; i < 2; i++) {
            ReporterBatch batch = new ReporterBatch();
            batch.candleFills.add(new ReporterBatch.CandleFill("ASML", DAY, BUCKET, 5L, 1L, 10L));
            batch.candleFills.add(new ReporterBatch.CandleFill("ASML", DAY, BUCKET, 9L, 2L, 11L));
            batch.candleFills.add(new ReporterBatch.CandleFill("ASML", DAY, BUCKET, 3L, 4L, 12L));
            batch.candleFills.add(new ReporterBatch.CandleFill("ASML", DAY, BUCKET, 7L, 8L, 13L));
            apply(batch, checkpoint(0L, 13L));
        }
        assertEquals("5 9 3 7 15 4 13", TestDatabase.queryOne("SELECT open || ' ' || high || ' ' || low || ' ' || close"
                + " || ' ' || volume || ' ' || trade_count || ' ' || last_outbound_seq FROM candle"));
    }

    @Test
    void foreignKeysWaitForTheCommitSoEitherOrderWorks() throws SQLException {
        // The orders row arrives after the execution referencing it, within one transaction.
        try (Connection connection = connection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id, event_ts)"
                             + " VALUES (1, 'ORDER_ACCEPTED', 1, 8, 6)")) {
            statement.executeUpdate();
            ReporterBatch batch = new ReporterBatch();
            batch.orders.add(order(1L, 8L));
            ReporterStore.apply(connection, batch, checkpoint(1L, 1L), null);
        }
        assertEquals("1", TestDatabase.queryOne("SELECT count(*) FROM executions"));
    }

    @Test
    void anExecutionOfAMissingOrderFailsAtCommitAsADataFailure() {
        ReporterBatch batch = new ReporterBatch();
        batch.executions.add(fillRow(1L, 99L, null, 100L));
        SQLException failure = assertThrows(SQLException.class, () -> apply(batch, checkpoint(0L, 1L)));
        assertTrue(ReporterStore.isDataFailure(failure), failure.getSQLState());
    }

    @Test
    void aTradeIdClashIsADataFailureNotASilentDuplicate() throws SQLException {
        apply(dayBatch(), checkpoint(2L, 1L));
        ReporterBatch clash = new ReporterBatch();
        clash.executions.add(fillRow(2L, 2L, 1L, 100L));
        SQLException failure = assertThrows(SQLException.class, () -> apply(clash, checkpoint(2L, 2L)));
        assertTrue(ReporterStore.isDataFailure(failure), failure.getSQLState());
        assertEquals("1", TestDatabase.queryOne("SELECT last_outbound_seq FROM reporter_checkpoint"));
    }

    @Test
    void aCheckpointGoingBackwardsIsRefused() throws SQLException {
        apply(dayBatch(), checkpoint(2L, 5L));
        assertThrows(ReporterInconsistencyException.class, () -> apply(new ReporterBatch(), checkpoint(2L, 4L)));
    }

    @Test
    void theResumeStateIsTheOpenOrdersAndTheLastCommandsNew() throws SQLException {
        ReporterBatch batch = dayBatch();
        batch.orderUpdates.clear();
        batch.orderUpdates.add(new ReporterBatch.OrderUpdate(1L, "PARTIALLY_FILLED", 4L, 1L));
        batch.orderUpdates.add(new ReporterBatch.OrderUpdate(2L, "FILLED", 10L, 1L));
        apply(batch, checkpoint(2L, 1L));
        try (Connection connection = connection()) {
            EventAttributor.Resume resume = ReporterStore.loadResume(connection, checkpoint(2L, 1L));
            assertEquals(1, resume.resting().size());
            EventAttributor.OpenOrder open = List.copyOf(resume.resting()).get(0);
            assertEquals(1L, open.inboundSeq);
            assertEquals(8L, open.orderId);
            assertEquals(4L, open.cumQuantity);
            assertEquals(2L, resume.current().inboundSeq);
            assertEquals(DAY, resume.sessionDate());
            assertEquals("ASML", resume.sessionSymbol());
        }
    }

    @Test
    void executionsBeyondTheCheckpointAreRefusedUnlessItIsAFullReset() throws SQLException {
        apply(dayBatch(), checkpoint(2L, 1L));
        ReporterBatch more = new ReporterBatch();
        more.executions.add(fillRow(5L, 2L, 1L, 101L));
        apply(more, checkpoint(2L, 5L));
        try (Connection connection = connection()) {
            assertThrows(ReporterInconsistencyException.class,
                    () -> ReporterStore.loadResume(connection, checkpoint(2L, 3L)));
            assertTrue(ReporterStore.loadResume(connection, new ReporterStore.Checkpoint(0L, 0L, 0L, 0L, null, null, null))
                    .resting().isEmpty());
        }
    }

    @Test
    void onlyDataFailuresAreDataFailures() {
        assertTrue(ReporterStore.isDataFailure(new SQLException("x", "23505")));
        assertTrue(ReporterStore.isDataFailure(new SQLException("x", "22003")));
        assertFalse(ReporterStore.isDataFailure(new SQLException("x", "08006")));
        assertFalse(ReporterStore.isDataFailure(new SQLException("x", "57P01")));
        assertFalse(ReporterStore.isDataFailure(new SQLException("x", "42P01")));
        assertFalse(ReporterStore.isDataFailure(new IllegalStateException("not SQL")));
        assertTrue(ReporterStore.isDataFailure(new IllegalStateException(new SQLException("x", "23503"))));
    }
}
