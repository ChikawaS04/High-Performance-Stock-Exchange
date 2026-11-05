package integration;

import config.ExchangeConfig;
import db.Database;
import db.DatabaseReferenceSource;
import db.Migrator;
import db.RequiresTestDatabase;
import db.TestDatabase;
import engine.CommandHandler;
import event.OrderEvent;
import event.OrderEventType;
import gateway.OrderGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import session.SessionController;
import session.SessionStartupException;
import util.VenueStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The session controller against {@code exchange_test} and a real journal (D17). The configuration
 * here carries a tick the engine would refuse, so a session that opens can only have read the
 * database, and the journaled command shows which values it carried.
 */
@RequiresTestDatabase
class DatabaseSessionStartupTest {

    private static final int DAY = JournaledVenue.BUSINESS_DATE;

    /** What a SESSION_OPEN carried, copied on the applying thread. */
    private record Open(int businessDate, byte[] symbol, long tick, long lot, long scale) {
    }

    @TempDir
    Path root;

    private final List<Open> opens = Collections.synchronizedList(new ArrayList<>());
    private final CommandHandler tap = this::capture;

    private void capture(OrderEvent command) {
        if (command.eventType == OrderEventType.SESSION_OPEN) {
            opens.add(new Open(command.businessDate, command.symbol.clone(), command.tickSize, command.lotSize,
                    command.priceScale));
        }
    }

    @BeforeEach
    void emptySchemaWithEveryMigration() {
        TestDatabase.cleanAndMigrate();
    }

    private ExchangeConfig config() {
        Map<String, String> env = TestDatabase.venueEnvironment();
        env.put(ExchangeConfig.SESSION_TICK_SIZE, "200");
        env.put(ExchangeConfig.SESSION_LOT_SIZE, "7");
        return JournaledVenue.config(root, env);
    }

    private SessionController controller(JournaledVenue venue, OrderGateway gateway, ExchangeConfig config) {
        Database database = TestDatabase.database();
        DatabaseReferenceSource source = new DatabaseReferenceSource(database, new Migrator(database, true),
                config.sessionSymbol());
        assertTrue(source.prepare());
        return SessionController.fromConfig(config, source, JournaledVenue.CLOCK, gateway, Runnable::run, venue.pipeline());
    }

    @Test
    void theSessionOpenCarriesWhatTheDatabaseHolds() {
        ExchangeConfig config = config();
        try (JournaledVenue venue = JournaledVenue.create(config, tap, null)) {
            OrderGateway gateway = venue.gateway();
            venue.start();
            controller(venue, gateway, config).startup();
            assertEquals(VenueStatus.sessionWord(true, DAY, -1), venue.pipeline().session());
        }
        assertEquals(1, opens.size());
        Open open = opens.get(0);
        assertEquals(DAY, open.businessDate());
        assertEquals(100L, open.tick(), "the row, not SESSION_TICK_SIZE");
        assertEquals(1L, open.lot(), "the row, not SESSION_LOT_SIZE");
        assertEquals(10_000L, open.scale());
        assertEquals('A', open.symbol()[0]);
    }

    @Test
    void aRestartResumesTheOpenDayWithNoSecondOpen() {
        ExchangeConfig config = config();
        try (JournaledVenue venue = JournaledVenue.create(config, tap, null)) {
            OrderGateway gateway = venue.gateway();
            venue.start();
            controller(venue, gateway, config).startup();
        }
        opens.clear();
        try (JournaledVenue again = JournaledVenue.create(config, tap, null)) {
            OrderGateway gateway = again.gateway();
            again.start();
            controller(again, gateway, config).startup();
            assertEquals(VenueStatus.sessionWord(true, DAY, -1), again.pipeline().session());
        }
        assertTrue(opens.isEmpty());
    }

    @Test
    void aCalendarRowClosingTodayJournalsNoOpen() {
        TestDatabase.execute("INSERT INTO trading_calendar VALUES (" + DAY + ", false, 'Holiday')");
        ExchangeConfig config = config();
        try (JournaledVenue venue = JournaledVenue.create(config, tap, null)) {
            OrderGateway gateway = venue.gateway();
            venue.start();
            controller(venue, gateway, config).startup();
            assertEquals(0L, venue.pipeline().session());
            assertEquals(0L, venue.pipeline().journaledSeq());
        }
        assertTrue(opens.isEmpty());
    }

    @Test
    void aRowTheRulesRefuseStopsStartupBeforeAnythingIsJournaled() {
        TestDatabase.execute("UPDATE instrument SET tick_size = 200 WHERE symbol = 'ASML'");
        ExchangeConfig config = config();
        try (JournaledVenue venue = JournaledVenue.create(config, tap, null)) {
            OrderGateway gateway = venue.gateway();
            venue.start();
            SessionController controller = controller(venue, gateway, config);
            SessionStartupException e = assertThrows(SessionStartupException.class, controller::startup);
            assertTrue(e.getMessage().contains("instrument row for ASML"), e.getMessage());
            assertEquals(0L, venue.pipeline().journaledSeq());
        }
        assertTrue(opens.isEmpty());
    }

    @Test
    void aMissingRowForTheConfiguredSymbolRefusesStartup() {
        ExchangeConfig config = JournaledVenue.config(root, Map.of(ExchangeConfig.SESSION_SYMBOL, "MSFT"));
        try (JournaledVenue venue = JournaledVenue.create(config, tap, null)) {
            OrderGateway gateway = venue.gateway();
            venue.start();
            SessionController controller = controller(venue, gateway, config);
            SessionStartupException e = assertThrows(SessionStartupException.class, controller::startup);
            assertTrue(e.getMessage().contains("no instrument row for MSFT"), e.getMessage());
        }
    }

    @Test
    void theRollReadsTheNextDayFromTheDatabase() {
        ExchangeConfig config = config();
        session.MutableClock clock = new session.MutableClock("2026-11-02T17:00:00Z");
        try (JournaledVenue venue = JournaledVenue.create(config, tap, null)) {
            OrderGateway gateway = venue.gateway();
            venue.start();
            Database database = TestDatabase.database();
            DatabaseReferenceSource source = new DatabaseReferenceSource(database, new Migrator(database, true), "ASML");
            SessionController controller = SessionController.fromConfig(config, source, clock, gateway, Runnable::run,
                    venue.pipeline());
            controller.startup();

            TestDatabase.execute("INSERT INTO trading_calendar VALUES (20261103, false, 'Holiday')");
            TestDatabase.execute("INSERT INTO trading_calendar VALUES (20261104, true, NULL)");
            clock.set("2026-11-03T05:00:01Z");
            controller.roll();
            assertEquals(VenueStatus.sessionWord(false, -1, DAY), venue.pipeline().session(), "a holiday: closed");

            clock.set("2026-11-04T05:00:01Z");
            controller.roll();
            assertEquals(VenueStatus.sessionWord(true, 20261104, -1), venue.pipeline().session());
        }
        assertEquals(2, opens.size());
        assertEquals(100L, opens.get(1).tick());
    }
}
