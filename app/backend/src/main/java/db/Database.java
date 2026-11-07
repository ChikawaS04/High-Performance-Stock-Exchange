package db;

import config.ExchangeConfig;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The connection factory for the venue's database (SRS §3.13): plain JDBC with bounded connects, and
 * no pool, because reads happen only at session boundaries. Never used on a Netty event loop or the
 * engine thread (SRS §5.9). Never log the data source: it holds the password.
 */
public final class Database {

    /**
     * How long the database may hold up its caller.
     *
     * @param attempts              tries of the startup schema step before the venue starts without it
     * @param retryDelayMs          pause between those tries
     * @param connectTimeoutSeconds bound on one connection attempt, login included (at least 1)
     * @param socketTimeoutSeconds  bound on one read from the server
     */
    public record Timing(int attempts, long retryDelayMs, int connectTimeoutSeconds, int socketTimeoutSeconds) {
        public static final Timing PRODUCTION = new Timing(3, 2_000L, 5, 30);
    }

    /** SQLState class of every connection exception. */
    private static final String CONNECTION_EXCEPTION_CLASS = "08";
    /** SQLState of a server that is starting up or shutting down. */
    private static final String CANNOT_CONNECT_NOW = "57P03";

    private final PGSimpleDataSource dataSource = new PGSimpleDataSource();
    private final Timing timing;

    public Database(String url, String user, String password, Timing timing) {
        this.timing = timing;
        dataSource.setUrl(url);     // parses the URL and replaces earlier properties, so it goes first
        if (user != null) dataSource.setUser(user);
        if (password != null) dataSource.setPassword(password);
        dataSource.setConnectTimeout(timing.connectTimeoutSeconds());
        dataSource.setLoginTimeout(timing.connectTimeoutSeconds());
        dataSource.setSocketTimeout(timing.socketTimeoutSeconds());
        dataSource.setApplicationName("exchange");
    }

    /** The database {@code DB_URL} names, with the production timing. */
    public static Database from(ExchangeConfig config) {
        return new Database(config.dbUrl(), config.dbUser(), config.dbPassword(), Timing.PRODUCTION);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public Timing timing() {
        return timing;
    }

    public Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Whether {@code failure} means the database cannot be reached now, so waiting may fix it, as
     * opposed to a database that answered and refused: bad credentials, a missing database, a
     * failed migration or a missing table. Walks the causes, because Flyway wraps its SQLExceptions.
     */
    public static boolean isUnavailable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                return state.startsWith(CONNECTION_EXCEPTION_CLASS) || state.equals(CANNOT_CONNECT_NOW);
            }
        }
        return false;
    }

    /**
     * Whether {@code failure} says the data itself is wrong, so retrying cannot help: SQLState class 23
     * (integrity constraint) or 22 (data exception). A batch failure reports its own state or that of
     * the statement it chains to. The Reporter and clearing stop on these (P15-10, P15-11).
     */
    public static boolean isDataFailure(Throwable failure) {
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
