package db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import session.ReferenceDataException;
import session.SessionDay;
import session.SessionReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reference data and the calendar as the database holds them. */
@RequiresTestDatabase
class DatabaseReferenceSourceTest {

    private static final int DAY = 20261102;

    @BeforeEach
    void emptySchemaWithEveryMigration() {
        TestDatabase.cleanAndMigrate();
    }

    private static DatabaseReferenceSource source(String symbol) {
        Database database = TestDatabase.database();
        return new DatabaseReferenceSource(database, new Migrator(database, true), symbol);
    }

    @Test
    void loadsTheNamedInstrumentsRow() {
        TestDatabase.execute("INSERT INTO instrument VALUES ('BRK.B', 200, 5, 20000)");
        assertEquals(new SessionReference("ASML", 100L, 1L, 10_000L), source("ASML").load(DAY).reference());
        assertEquals(new SessionReference("BRK.B", 200L, 5L, 20_000L), source("BRK.B").load(DAY).reference(),
                "whatever the row says; the rules are the controller's check");
    }

    @Test
    void aDateWithNoCalendarRowTrades() {
        assertTrue(source("ASML").load(DAY).tradingDay());
    }

    @Test
    void aCalendarRowDecidesItsDateOnly() {
        TestDatabase.execute("INSERT INTO trading_calendar VALUES (" + DAY + ", false, 'Holiday')");
        TestDatabase.execute("INSERT INTO trading_calendar VALUES (" + (DAY + 1) + ", true, NULL)");
        DatabaseReferenceSource source = source("ASML");
        SessionDay holiday = source.load(DAY);
        assertFalse(holiday.tradingDay());
        assertEquals("ASML", holiday.reference().symbol());
        assertTrue(source.load(DAY + 1).tradingDay());
        assertTrue(source.load(DAY + 2).tradingDay());
    }

    @Test
    void aMissingInstrumentIsUnusable() {
        ReferenceDataException e = assertThrows(ReferenceDataException.class, () -> source("MSFT").load(DAY));
        assertTrue(e.getMessage().contains("no instrument row for MSFT"), e.getMessage());
        assertTrue(e.getMessage().contains("SESSION_SYMBOL"), e.getMessage());
    }

    @Test
    void theSchemaStepRunsBeforeTheFirstRead() {
        TestDatabase.clean();
        assertEquals("ASML", source("ASML").load(DAY).reference().symbol(),
                "as after a startup that could not reach the database");
    }

    @Test
    void everyReadSeesTheDatabaseAsItIsNow() {
        DatabaseReferenceSource source = source("ASML");
        assertTrue(source.load(DAY).tradingDay());
        TestDatabase.execute("INSERT INTO trading_calendar VALUES (" + DAY + ", false, NULL)");
        assertFalse(source.load(DAY).tradingDay(), "nothing cached");
    }
}
