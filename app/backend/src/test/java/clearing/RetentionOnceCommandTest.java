package clearing;

import config.ExchangeConfig;
import db.RequiresTestDatabase;
import db.TestDatabase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static clearing.RetentionSeed.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code retention-once} measurement command (P16-12): it refuses every database that is not a scratch
 * one before connecting, and against {@code exchange_test} it runs exactly one pass.
 */
class RetentionOnceCommandTest {

    private static final int D1 = 20261102;
    private static final int D2 = 20261103;

    private static ExchangeConfig configFor(String url) {
        Map<String, String> env = new HashMap<>();
        env.put(ExchangeConfig.DB_URL, url);
        env.put(ExchangeConfig.DB_USER, "nobody");
        env.put(ExchangeConfig.DB_PASSWORD, "unused");
        return ExchangeConfig.from(env);
    }

    @Test
    void theDatabaseNameIsReadFromTheUrl() {
        assertEquals("exchange", RetentionOnceCommand.databaseName("jdbc:postgresql://postgres:5432/exchange"));
        assertEquals("exchange_bench",
                RetentionOnceCommand.databaseName("jdbc:postgresql://postgres:5432/exchange_bench?sslmode=disable"));
        assertEquals("x_test", RetentionOnceCommand.databaseName("jdbc:postgresql://h/x_test"));
        assertNull(RetentionOnceCommand.databaseName("jdbc:postgresql://postgres:5432/"));
        assertNull(RetentionOnceCommand.databaseName("jdbc:postgresql://postgres:5432"));
        assertNull(RetentionOnceCommand.databaseName("jdbc:mysql://h/x_bench"));
    }

    @Test
    void theLiveAndDevelopmentDatabasesAreRefusedBeforeConnecting() {
        // Unreachable hosts: a refusal is decided from the name alone, so nothing ever connects.
        for (String url : List.of("jdbc:postgresql://127.0.0.1:1/exchange", "jdbc:postgresql://127.0.0.1:1/exchange_dev",
                "jdbc:postgresql://127.0.0.1:1/exchange_drill", "jdbc:postgresql://127.0.0.1:1/benchmark",
                "jdbc:postgresql://127.0.0.1:1/exchange_bench_old", "jdbc:postgresql://127.0.0.1:1/")) {
            assertNotNull(RetentionOnceCommand.refusal(url), url);
            assertEquals(RetentionOnceCommand.REFUSED, RetentionOnceCommand.run(configFor(url)), url);
        }
        assertTrue(RetentionOnceCommand.refusal("jdbc:postgresql://p/exchange").contains("not a scratch database"));
    }

    @Test
    void anUnsetUrlIsRefused() {
        assertEquals(RetentionOnceCommand.REFUSED, RetentionOnceCommand.run(ExchangeConfig.from(Map.of())));
    }

    @Test
    void scratchNamesAreAccepted() {
        assertNull(RetentionOnceCommand.refusal("jdbc:postgresql://postgres:5432/exchange_bench"));
        assertNull(RetentionOnceCommand.refusal("jdbc:postgresql://localhost:5432/exchange_test"));
    }

    @Test
    @RequiresTestDatabase
    void onePassPurgesTheEligibleDateAndASecondFindsNothing() {
        TestDatabase.cleanAndMigrate();
        order(1, 10, D1, "FILLED", 3, 10);
        order(2, 11, D1, "FILLED", 3, 20);
        fill(3, 2, 1, 1, 1_000_000L, 5, D1, 30);
        audit("IN", 1, 1L, 10, "NEW_ORDER", D1, 10);
        audit("IN", 2, 2L, 11, "NEW_ORDER", D1, 20);
        audit("OUT", 3, 2L, 11, "ORDER_FILLED", D1, 30);
        run(D1, "COMPLETE", 4, 40);
        order(3, 12, D2, "OPEN", 5, 50);
        accepted(5, 3, 12, D2, 50);
        run(D2, "COMPLETE", 6, 60);
        checkpoint(3, 6);

        Map<String, String> env = TestDatabase.venueEnvironment();
        env.put(ExchangeConfig.DB_RETENTION_DAYS, "1");
        ExchangeConfig config = ExchangeConfig.from(env);
        assertNull(RetentionOnceCommand.refusal(config.dbUrl()), "exchange_test is a scratch name");

        assertEquals(RetentionOnceCommand.OK, RetentionOnceCommand.run(config));
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM executions WHERE business_date = " + D1));
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM order_audit WHERE business_date = " + D1));
        assertEquals("1", TestDatabase.queryOne("SELECT count(*) FROM executions WHERE business_date = " + D2),
                "the newest COMPLETE date is kept with one day");
        assertEquals("t", TestDatabase.queryOne("SELECT purge_completed_ts IS NOT NULL FROM clearing_run"
                + " WHERE business_date = " + D1));

        assertEquals(RetentionOnceCommand.OK, RetentionOnceCommand.run(config), "NOTHING_TO_PURGE is a success");
    }
}
