package db;

import config.ExchangeConfig;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import session.ReferenceDataException;
import session.ReferenceSource;
import session.ReferenceUnavailableException;
import session.SessionDay;
import session.SessionReference;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.locks.LockSupport;

/**
 * Reference data and the trading calendar from PostgreSQL (SRS §3.12). {@code SESSION_SYMBOL} names
 * which instrument this venue trades; its row supplies the tick, lot and scale, and the
 * {@code SESSION_TICK_SIZE}, {@code SESSION_LOT_SIZE} and {@code SESSION_PRICE_SCALE} variables are
 * not read. A date with no calendar row is a trading day.
 *
 * There is never a fallback to configuration, so a session can never carry values the database does
 * not hold. A database that cannot be reached delays an open; one that answers with something wrong
 * refuses startup ({@link #prepare()}). A schema step that an unreachable database prevented at
 * startup runs before the next read.
 *
 * Used by one thread at a time: the startup thread, then the session scheduler's.
 */
public final class DatabaseReferenceSource implements ReferenceSource {

    private static final Logger log = LoggerFactory.getLogger(DatabaseReferenceSource.class);

    private static final String INSTRUMENT_SQL =
            "SELECT tick_size, lot_size, price_scale FROM instrument WHERE symbol = ?";
    private static final String CALENDAR_SQL =
            "SELECT is_trading_day FROM trading_calendar WHERE business_date = ?";

    private final Database database;
    private final Migrator migrator;
    private final String symbol;
    private boolean schemaReady;

    public DatabaseReferenceSource(Database database, Migrator migrator, String symbol) {
        this.database = database;
        this.migrator = migrator;
        this.symbol = symbol;
    }

    /** The prepared source {@code DB_URL} names: Main's call, on the startup thread. */
    public static DatabaseReferenceSource open(ExchangeConfig config) {
        Database database = Database.from(config);
        DatabaseReferenceSource source = new DatabaseReferenceSource(database,
                new Migrator(database, config.dbMigrateOnStart()), config.sessionSymbol());
        source.prepare();
        return source;
    }

    /**
     * Runs the schema step on the calling thread, retrying a database that cannot be reached a
     * bounded number of times ({@link Database.Timing}).
     *
     * @return whether the schema is ready; false means the venue starts without the database
     * @throws ReferenceDataException if the database answered and the step failed
     */
    public boolean prepare() {
        Database.Timing timing = database.timing();
        for (int attempt = 1; attempt <= timing.attempts(); attempt++) {
            try {
                ensureSchema();
                return true;
            } catch (ReferenceUnavailableException e) {
                log.warn("Database unavailable (attempt {} of {}): {}", attempt, timing.attempts(), e.getMessage());
                if (attempt < timing.attempts()) {
                    LockSupport.parkNanos(timing.retryDelayMs() * 1_000_000L);
                }
            }
        }
        log.error("Database unavailable after {} attempts: starting without it. An open session trades on; a day"
                + " that needs opening stays closed until the database answers", timing.attempts());
        return false;
    }

    @Override
    public SessionDay load(int businessDate) {
        ensureSchema();
        try (Connection connection = database.connect()) {
            return new SessionDay(readInstrument(connection), readTradingDay(connection, businessDate));
        } catch (SQLException e) {
            if (Database.isUnavailable(e)) {
                throw new ReferenceUnavailableException("Cannot read reference data: " + e.getMessage(), e);
            }
            throw new ReferenceDataException("Reading reference data failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String remedy() {
        return "Correct the instrument row for " + symbol + " in the database.";
    }

    private void ensureSchema() {
        if (schemaReady) return;
        try {
            migrator.run();
            schemaReady = true;
        } catch (FlywayException e) {
            if (Database.isUnavailable(e)) {
                throw new ReferenceUnavailableException(e.getMessage(), e);
            }
            throw new ReferenceDataException("The database schema step failed: " + e.getMessage(), e);
        }
    }

    private SessionReference readInstrument(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSTRUMENT_SQL)) {
            statement.setString(1, symbol);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ReferenceDataException("The database has no instrument row for " + symbol + " ("
                            + ExchangeConfig.SESSION_SYMBOL + ").");
                }
                return new SessionReference(symbol, row.getLong(1), row.getLong(2), row.getLong(3));
            }
        }
    }

    private static boolean readTradingDay(Connection connection, int businessDate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CALENDAR_SQL)) {
            statement.setInt(1, businessDate);
            try (ResultSet row = statement.executeQuery()) {
                return !row.next() || row.getBoolean(1);
            }
        }
    }
}
