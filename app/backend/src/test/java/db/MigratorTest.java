package db;

import config.ExchangeConfig;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import session.ReferenceDataException;
import session.ReferenceUnavailableException;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema step and what its failures mean (decision 2): an unreachable database is retried a
 * bounded number of times and the venue starts without it; a database that answers with something
 * wrong refuses startup, with no retry. The unreachable cases need no database.
 */
class MigratorTest {

    private static Database unreachable(Database.Timing timing) {
        return new Database(TestDatabase.UNREACHABLE_URL, "exchange", "unused", timing);
    }

    private static DatabaseReferenceSource source(Database database, Migrator migrator) {
        return new DatabaseReferenceSource(database, migrator, "ASML");
    }

    @Test
    void anUnreachableDatabaseIsRetriedThenTheVenueStartsWithoutIt() {
        Database database = unreachable(new Database.Timing(3, 5L, 1, 1));
        long start = System.nanoTime();
        assertFalse(source(database, new Migrator(database, true)).prepare());
        assertTrue(System.nanoTime() - start < 30_000_000_000L, "bounded");
    }

    @Test
    void anUnreachableDatabaseIsClassifiedUnavailable() {
        Database database = unreachable(TestDatabase.FAST);
        FlywayException e = assertThrows(FlywayException.class, () -> new Migrator(database, true).run());
        assertTrue(Database.isUnavailable(e), e.toString());
        assertThrows(ReferenceUnavailableException.class,
                () -> source(database, new Migrator(database, true)).load(20261102));
    }

    @Test
    void theTimingIsInjectable() {
        assertEquals(new Database.Timing(3, 2_000L, 5, 30), Database.Timing.PRODUCTION);
        Database.Timing fast = new Database.Timing(1, 0L, 1, 1);
        assertEquals(fast, unreachable(fast).timing());
    }

    @Test
    void connectionFailuresAreUnavailableAndAnswersAreNot() {
        assertTrue(Database.isUnavailable(new SQLException("refused", "08001")));
        assertTrue(Database.isUnavailable(new SQLException("lost", "08006")));
        assertTrue(Database.isUnavailable(new SQLException("starting up", "57P03")));
        assertTrue(Database.isUnavailable(new RuntimeException(new SQLException("wrapped", "08001"))));
        assertFalse(Database.isUnavailable(new SQLException("bad password", "28P01")));
        assertFalse(Database.isUnavailable(new SQLException("no database", "3D000")));
        assertFalse(Database.isUnavailable(new SQLException("no table", "42P01")));
        assertFalse(Database.isUnavailable(new SQLException("no state")));
        assertFalse(Database.isUnavailable(new RuntimeException("not sql")));
    }

    @Test
    void theMigrateCommandNeedsADatabaseAndReportsFailure() {
        assertEquals(MigrateCommand.NOT_CONFIGURED, MigrateCommand.run(ExchangeConfig.from(Map.of())));
        assertEquals(MigrateCommand.FAILED, MigrateCommand.run(ExchangeConfig.from(Map.of(
                ExchangeConfig.DB_URL, TestDatabase.UNREACHABLE_URL))));
    }

    @Test
    @RequiresTestDatabase
    void theMigrateCommandAppliesTheMigrations() {
        TestDatabase.clean();
        assertEquals(MigrateCommand.OK, MigrateCommand.run(ExchangeConfig.from(TestDatabase.venueEnvironment())));
        assertEquals("ASML", TestDatabase.queryOne("SELECT symbol FROM instrument"));
    }

    @Test
    @RequiresTestDatabase
    void validateOnlyRefusesASchemaBehindTheMigrations() {
        TestDatabase.clean();
        Database database = TestDatabase.database();
        ReferenceDataException e = assertThrows(ReferenceDataException.class,
                () -> source(database, new Migrator(database, false)).prepare());
        assertTrue(e.getMessage().contains("schema step failed"), e.getMessage());
        assertEquals("0", TestDatabase.queryOne(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'instrument'"), "validate changes nothing");
    }

    @Test
    @RequiresTestDatabase
    void validateOnlyAcceptsACurrentSchema() {
        TestDatabase.cleanAndMigrate();
        Database database = TestDatabase.database();
        assertTrue(source(database, new Migrator(database, false)).prepare());
    }

    @Test
    @RequiresTestDatabase
    void aFailedMigrationRefusesStartupAndRollsBack() {
        TestDatabase.clean();
        Database database = TestDatabase.database();
        Migrator broken = new Migrator(database, true, "classpath:db/broken");
        assertThrows(ReferenceDataException.class, () -> source(database, broken).prepare());
        assertEquals("0", TestDatabase.queryOne(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'broken'"));
    }

    /**
     * P16-12, rollback compatibility: an older image against a database a newer image has migrated. Flyway
     * ignores applied migrations newer than its own (its default {@code ignoreMigrationPatterns} is
     * {@code *:future}), so both the validate-only start and the migrate job pass and the old code runs on
     * the newer schema. Nothing in the venue stops that; the deploy script's compatibility floor does.
     */
    @Test
    @RequiresTestDatabase
    void anOlderImageAcceptsASchemaOneMigrationAhead() {
        TestDatabase.cleanAndMigrate();
        try {
            TestDatabase.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script,"
                    + " checksum, installed_by, execution_time, success) SELECT max(installed_rank) + 1, '999',"
                    + " 'from a newer image', 'SQL', 'V999__from_a_newer_image.sql', 0, current_user, 1, true"
                    + " FROM flyway_schema_history");
            Database database = TestDatabase.database();
            new Migrator(database, false).run();
            new Migrator(database, true).run();
            assertTrue(source(database, new Migrator(database, false)).prepare());
        } finally {
            TestDatabase.cleanAndMigrate();
        }
    }

    @Test
    @RequiresTestDatabase
    void aWrongPasswordRefusesStartupWithoutRetrying() {
        Database database = new Database(System.getenv(TestDatabase.TEST_DB_URL), System.getenv(TestDatabase.TEST_DB_USER),
                "not-the-password", TestDatabase.FAST);
        assertThrows(ReferenceDataException.class, () -> source(database, new Migrator(database, true)).prepare());
    }
}
