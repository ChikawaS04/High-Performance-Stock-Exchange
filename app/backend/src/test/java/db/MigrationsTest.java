package db;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schema on an empty {@code exchange_test}: it applies, its seeds are there, and its keys hold (D17). */
@RequiresTestDatabase
class MigrationsTest {

    private static final String ORDER_COLUMNS = "(inbound_seq, order_id, participant_id, side, ord_type, time_in_force,"
            + " quantity, received_ts) VALUES ";

    @BeforeEach
    void emptySchemaWithEveryMigration() {
        TestDatabase.cleanAndMigrate();
    }

    private static String order(long inboundSeq, long orderId) {
        return "INSERT INTO orders " + ORDER_COLUMNS + "(" + inboundSeq + ", " + orderId
                + ", 1, 'BUY', 'LIMIT', 'GTC', 10, 1000)";
    }

    private static String execution(long outboundSeq, String orderInboundSeq, String tradeId) {
        return "INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id, trade_id, event_ts)"
                + " VALUES (" + outboundSeq + ", 'ORDER_FILLED', " + orderInboundSeq + ", 7, " + tradeId + ", 2000)";
    }

    private static void assertRefused(String sql) {
        assertThrows(IllegalStateException.class, () -> TestDatabase.execute(sql), sql);
    }

    @Test
    void everyMigrationAppliesToAnEmptyDatabase() {
        MigrationInfo[] applied = new Migrator(TestDatabase.database(), true).flyway().info().applied();
        assertEquals(5, applied.length);
        for (MigrationInfo migration : applied) {
            assertEquals(MigrationState.SUCCESS, migration.getState(), migration.getScript());
        }
        for (String table : List.of("instrument", "trading_calendar", "participant", "account", "account_balance",
                "account_position", "house_position", "orders", "executions", "order_audit", "clearing_run",
                "clearing_obligation", "candle", "reporter_checkpoint")) {
            assertEquals("1", TestDatabase.queryOne("SELECT count(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_name = '" + table + "'"), table);
        }
    }

    @Test
    void aSecondMigrateChangesNothing() {
        new Migrator(TestDatabase.database(), true).run();
        assertEquals("5", TestDatabase.queryOne("SELECT count(*) FROM flyway_schema_history WHERE success"));
        assertEquals("1", TestDatabase.queryOne("SELECT count(*) FROM instrument"));
    }

    @Test
    void seedDataMatchesTheConfigurationDefaults() {
        assertEquals("ASML 100 1 10000", TestDatabase.queryOne(
                "SELECT symbol || ' ' || tick_size || ' ' || lot_size || ' ' || price_scale FROM instrument"));
        assertEquals("1 INTERNAL", TestDatabase.queryOne(
                "SELECT participant_id || ' ' || participant_type FROM participant"));
        assertEquals("1 0 0", TestDatabase.queryOne(
                "SELECT id || ' ' || last_inbound_seq || ' ' || last_outbound_seq FROM reporter_checkpoint"));
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM trading_calendar"),
                "an empty calendar: every day trades");
    }

    @Test
    void thereIsOnlyOneInternalParticipant() {
        assertRefused("INSERT INTO participant VALUES (2, 'Other', 'INTERNAL')");
        TestDatabase.execute("INSERT INTO participant VALUES (2, 'Broker', 'EXTERNAL')");
        TestDatabase.execute("INSERT INTO participant VALUES (3, 'Broker 2', 'EXTERNAL')");
        assertRefused("INSERT INTO participant VALUES (4, 'Bad', 'OTHER')");
    }

    @Test
    void everyParticipantIdIsABigint() {
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND column_name LIKE '%participant_id' AND data_type <> 'bigint'"));
        assertEquals("5", TestDatabase.queryOne("SELECT count(DISTINCT table_name) FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND column_name LIKE '%participant_id'"),
                "participant, account, orders, executions, clearing_obligation");
    }

    @Test
    void ordersAreKeyedByTheInboundSeqSoAnOrderIdMayBeReused() {
        TestDatabase.execute(order(1, 7));
        TestDatabase.execute(order(2, 7));
        assertEquals("2", TestDatabase.queryOne("SELECT count(*) FROM orders WHERE order_id = 7"));
        assertRefused(order(2, 8));
    }

    @Test
    void executionsAreKeyedByTheOutboundSeqAndATradeIdIsUnique() {
        TestDatabase.execute(order(1, 7));
        TestDatabase.execute(execution(1, "1", "100"));
        assertRefused(execution(1, "1", "101"));
        assertRefused(execution(2, "1", "100"));
        TestDatabase.execute(execution(3, "1", "NULL"));
        TestDatabase.execute(execution(4, "1", "NULL"));
    }

    @Test
    void aRejectedCancelForAnUnknownOrderHasNoOrderRow() {
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id, event_ts)"
                + " VALUES (1, 'ORDER_REJECTED', NULL, 99, 2000)");
        TestDatabase.execute("INSERT INTO order_audit (stream, seq, order_inbound_seq, order_id, event, event_ts)"
                + " VALUES ('OUT', 1, NULL, 99, 'ORDER_REJECTED', 2000)");
        assertEquals("1", TestDatabase.queryOne("SELECT count(*) FROM executions WHERE order_inbound_seq IS NULL"));
    }

    @Test
    void aNewRejectedOutsideASessionHasNoBusinessDateOrSymbol() {
        TestDatabase.execute(order(1, 7));
        assertEquals("1", TestDatabase.queryOne(
                "SELECT count(*) FROM orders WHERE business_date IS NULL AND symbol IS NULL"));
    }

    @Test
    void theAuditTrailIsKeyedByStreamAndSeq() {
        String row = "INSERT INTO order_audit (stream, seq, order_id, event, event_ts) VALUES ";
        TestDatabase.execute(row + "('IN', 1, 7, 'NEW_ORDER', 1000)");
        TestDatabase.execute(row + "('OUT', 1, 7, 'ORDER_ACCEPTED', 1000)");
        assertRefused(row + "('IN', 1, 7, 'NEW_ORDER', 1000)");
    }

    @Test
    void aBatchMayWriteAnExecutionBeforeItsOrderInOneTransaction() throws SQLException {
        try (Connection connection = TestDatabase.database().connect(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(execution(1, "5", "100"));
            statement.execute("INSERT INTO order_audit (stream, seq, order_inbound_seq, order_id, event, event_ts)"
                    + " VALUES ('OUT', 1, 5, 7, 'ORDER_FILLED', 2000)");
            statement.execute(order(5, 7));
            connection.commit();
        }
        assertEquals("1", TestDatabase.queryOne("SELECT count(*) FROM executions"));
    }

    @Test
    void aDeferredForeignKeyIsStillEnforcedAtCommit() throws SQLException {
        try (Connection connection = TestDatabase.database().connect(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(execution(1, "5", "100"));
            SQLException e = assertThrows(SQLException.class, connection::commit);
            assertEquals("23503", e.getSQLState(), e.getMessage());
        }
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM executions"));
    }

    @Test
    void everyForeignKeyBetweenTradingRecordTablesIsDeferred() {
        assertEquals("3", TestDatabase.queryOne("SELECT count(*) FROM pg_constraint c"
                + " WHERE c.contype = 'f' AND c.confrelid = 'orders'::regclass AND c.condeferrable AND c.condeferred"));
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM pg_constraint c"
                + " WHERE c.contype = 'f' AND c.confrelid = 'orders'::regclass AND NOT c.condeferred"));
    }

    @Test
    void valuesOutsideTheChecksAreRefused() {
        assertRefused("INSERT INTO instrument VALUES ('asml', 100, 1, 10000)");
        assertRefused("INSERT INTO instrument VALUES ('MSFT', 0, 1, 10000)");
        assertRefused("INSERT INTO trading_calendar VALUES (1, true, NULL)");
        assertRefused("INSERT INTO reporter_checkpoint VALUES (2, 0, 0)");
        assertTrue(TestDatabase.queryOne("SELECT count(*) FROM instrument").equals("1"));
    }
}
