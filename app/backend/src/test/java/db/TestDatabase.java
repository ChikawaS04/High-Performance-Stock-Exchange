package db;

import config.ExchangeConfig;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * The test database (D17). Found through {@code TEST_DB_URL}, {@code TEST_DB_USER} and
 * {@code TEST_DB_PASSWORD}, which production never reads; no test reads {@code DB_URL} from the
 * environment, so a test can never reach {@code exchange_dev} by accident.
 *
 * {@link #clean} is the only place clean is ever enabled. It asks the server which database the
 * connection reached and refuses anything but {@value #NAME}, before Flyway is even built: the
 * server's answer, not the URL's text, so an alias or a redirected host cannot get past it.
 */
public final class TestDatabase {

    public static final String TEST_DB_URL = "TEST_DB_URL";
    public static final String TEST_DB_USER = "TEST_DB_USER";
    public static final String TEST_DB_PASSWORD = "TEST_DB_PASSWORD";

    /** The only database clean may ever touch. */
    public static final String NAME = "exchange_test";

    /** Short delays, so no test waits on the production retry timing. */
    public static final Database.Timing FAST = new Database.Timing(2, 10L, 1, 10);

    /** Nothing listens here: connections are refused. */
    public static final String UNREACHABLE_URL = "jdbc:postgresql://127.0.0.1:1/" + NAME;

    private TestDatabase() {
    }

    public static boolean configured() {
        String url = System.getenv(TEST_DB_URL);
        return url != null && !url.isBlank();
    }

    /** The test database. */
    public static Database database() {
        return new Database(System.getenv(TEST_DB_URL), System.getenv(TEST_DB_USER), System.getenv(TEST_DB_PASSWORD),
                FAST);
    }

    /** Another database on the same server, with the same credentials; {@code postgres}, never {@code exchange_dev}. */
    public static Database otherDatabase() {
        String url = System.getenv(TEST_DB_URL).replace("/" + NAME, "/postgres");
        return new Database(url, System.getenv(TEST_DB_USER), System.getenv(TEST_DB_PASSWORD), FAST);
    }

    /** The variables a venue on the test database would run with. */
    public static Map<String, String> venueEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put(ExchangeConfig.DB_URL, System.getenv(TEST_DB_URL));
        env.put(ExchangeConfig.DB_USER, System.getenv(TEST_DB_USER));
        env.put(ExchangeConfig.DB_PASSWORD, System.getenv(TEST_DB_PASSWORD));
        return env;
    }

    /** An empty test database. */
    public static void clean() {
        clean(database());
    }

    /** An empty test database with every migration applied. */
    public static void cleanAndMigrate() {
        Database database = database();
        clean(database);
        new Migrator(database, true).run();
    }

    /**
     * Empties {@code database}.
     *
     * @throws IllegalStateException if the server says it is anything but {@value #NAME}
     */
    static void clean(Database database) {
        requireTestDatabase(serverDatabaseName(database));
        Flyway.configure()
                .dataSource(database.dataSource())
                .locations(Migrator.LOCATION)
                .cleanDisabled(false)
                .load()
                .clean();
    }

    /** The guard itself: exactly {@value #NAME}, and nothing else. */
    static void requireTestDatabase(String name) {
        if (!NAME.equals(name)) {
            throw new IllegalStateException("Refusing to clean database " + name + ": only " + NAME + " may be cleaned");
        }
    }

    private static String serverDatabaseName(Database database) {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_database()")) {
            row.next();
            return row.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot ask the server which database this is: " + e.getMessage(), e);
        }
    }

    /** Runs one statement on the test database. */
    public static void execute(String sql) {
        try (Connection connection = database().connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }

    /** The first column of the first row of {@code sql}, as text. */
    public static String queryOne(String sql) {
        try (Connection connection = database().connect();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }
}
