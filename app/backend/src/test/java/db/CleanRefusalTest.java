package db;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway clean is impossible anywhere but {@code exchange_test}. The other database here is
 * {@code postgres}, never {@code exchange_dev}: if the guard ever broke, this test must not be what
 * destroys the development database.
 */
@RequiresTestDatabase
class CleanRefusalTest {

    @Test
    void theCleanPathRefusesAnotherDatabaseOnTheSameServer() {
        Database other = TestDatabase.otherDatabase();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TestDatabase.clean(other));
        assertTrue(e.getMessage().contains("Refusing to clean database postgres"), e.getMessage());
    }

    @Test
    void theCleanPathAcceptsTheTestDatabase() {
        TestDatabase.cleanAndMigrate();
        TestDatabase.clean();
        assertEquals("0", TestDatabase.queryOne(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"));
    }

    @Test
    void theProductionMigratorCannotClean() {
        TestDatabase.cleanAndMigrate();
        Migrator production = new Migrator(TestDatabase.database(), true);
        assertThrows(FlywayException.class, () -> production.flyway().clean());
        assertEquals("ASML", TestDatabase.queryOne("SELECT symbol FROM instrument"), "nothing was cleaned");
    }
}
