package http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.ExchangeConfig;
import db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The read-only {@code /api} routes (SRS 3.6, 3.13, P15-12). Parameters are checked on the caller's
 * thread (the event loop) with no database; a valid request runs on the dedicated {@link ApiExecutor},
 * never on an event loop, and its answer goes to the caller's callback from there.
 *
 * <table>
 *   <caption>Routes</caption>
 *   <tr><td>{@code /api/trades}</td><td>{@code date}, {@code afterSeq}, {@code limit}</td></tr>
 *   <tr><td>{@code /api/reports/daily}</td><td>{@code date}</td></tr>
 *   <tr><td>{@code /api/reports/volume}</td><td>{@code from}, {@code to}, {@code symbol}</td></tr>
 *   <tr><td>{@code /api/reports/statement}</td><td>{@code participantId}, {@code date}, {@code afterSeq}, {@code limit}</td></tr>
 *   <tr><td>{@code /api/candles}</td><td>{@code symbol}, {@code date}, {@code interval}</td></tr>
 * </table>
 *
 * Database. Each executor thread holds one connection, opened when first needed: read only,
 * REPEATABLE READ, with a statement timeout, so each request is one consistent snapshot. A database
 * that cannot be reached is a 503 at once; there is no retry loop. The one exception: a cached
 * connection that fails with a connection error (the server restarted since it was opened) is
 * replaced once by a fresh one for the same request. A statement past its timeout is a 503, an
 * arithmetic overflow a 500 with an ERROR, anything else a 500 with a WARN; 503s are logged at most
 * once a minute.
 */
public final class ApiService implements ApiHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiService.class);

    static final String TRADES = "/api/trades";
    static final String DAILY = "/api/reports/daily";
    static final String VOLUME = "/api/reports/volume";
    static final String STATEMENT = "/api/reports/statement";
    static final String CANDLES = "/api/candles";

    private static final Map<String, Set<String>> ROUTES = Map.of(
            TRADES, Set.of("date", "afterSeq", "limit"),
            DAILY, Set.of("date"),
            VOLUME, Set.of("from", "to", "symbol"),
            STATEMENT, Set.of("participantId", "date", "afterSeq", "limit"),
            CANDLES, Set.of("symbol", "date", "interval"));

    static final int THREADS = 2;
    static final int MAX_PENDING = 16;
    static final int STATEMENT_TIMEOUT_MS = 5_000;
    private static final long CLOSE_TIMEOUT_MS = 5_000L;
    private static final long UNAVAILABLE_LOG_INTERVAL_NANOS = 60_000_000_000L;

    private static final String QUERY_CANCELED = "57014";
    private static final String NUMERIC_OUT_OF_RANGE = "22003";

    /** Where connections come from; a seam for tests. */
    public interface ConnectionSource {
        Connection connect() throws SQLException;
    }

    /** One report, run inside the worker's transaction. */
    private interface Query {
        ObjectNode run(Connection c) throws SQLException;
    }

    /** One executor thread's connection. */
    private static final class Worker {
        Connection connection;
    }

    private final ConnectionSource connections;
    private final Runnable beforeQuery;
    private final ObjectMapper mapper = new ObjectMapper();   // thread safe once configured; shared by the workers
    private final ApiExecutor<Worker> executor;
    private final Object logLock = new Object();
    private long lastUnavailableLogNanos;
    private boolean unavailableLogged;
    private int suppressedUnavailable;

    /**
     * @param connections where each worker's connection comes from
     * @param beforeQuery runs on the worker thread before each query: a seam for tests (thread checks,
     *                    latches); {@code () -> {}} in production
     */
    ApiService(ConnectionSource connections, Runnable beforeQuery, int threads, int maxPending) {
        this.connections = connections;
        this.beforeQuery = beforeQuery;
        this.executor = new ApiExecutor<>("api", threads, maxPending, Worker::new, ApiService::closeWorker,
                CLOSE_TIMEOUT_MS);
    }

    /** As {@link #ApiService(ConnectionSource, Runnable, int, int)} with production sizing. */
    public ApiService(ConnectionSource connections) {
        this(connections, () -> { }, THREADS, MAX_PENDING);
    }

    /** The api for the database {@code DB_URL} names, with its own short timeouts ({@link Database.Timing#API}). */
    public static ApiService forVenue(ExchangeConfig config) {
        Database database = new Database(config.dbUrl(), config.dbUser(), config.dbPassword(), Database.Timing.API);
        return new ApiService(database::connect);
    }

    @Override
    public boolean isRoute(String path) {
        return ROUTES.containsKey(path);
    }

    @Override
    public ApiResponse handle(ApiRequest request, Consumer<ApiResponse> done) {
        Set<String> allowed = ROUTES.get(request.path());
        if (allowed == null) return ApiResponse.NOT_FOUND;
        Query query;
        try {
            query = parse(request.path(), new ApiParams(request.parameters(), allowed));
        } catch (ApiException e) {
            return e.response();
        }
        boolean queued = executor.submit(worker -> {
            ApiResponse response;
            try {
                response = execute(worker, query);
            } catch (RuntimeException e) {
                log.warn("An /api request failed", e);
                closeWorker(worker);
                response = ApiResponse.INTERNAL;
            }
            done.accept(response);   // every request accepted here gets exactly one answer
        });
        return queued ? null : ApiResponse.BUSY;
    }

    private Query parse(String path, ApiParams p) {
        switch (path) {
            case TRADES -> {
                int date = p.date("date");
                long afterSeq = p.afterSeq();
                int limit = p.limit();
                return c -> ReportStore.trades(c, mapper, date, afterSeq, limit);
            }
            case DAILY -> {
                int date = p.date("date");
                return c -> ReportStore.daily(c, mapper, date);
            }
            case VOLUME -> {
                int from = p.date("from");
                int to = p.date("to");
                ApiParams.checkRange(from, to);
                String symbol = p.symbol("symbol", false);
                return c -> ReportStore.volume(c, mapper, from, to, symbol);
            }
            case STATEMENT -> {
                long participantId = p.requiredLong("participantId", 1L, Long.MAX_VALUE);
                int date = p.date("date");
                long afterSeq = p.afterSeq();
                int limit = p.limit();
                return c -> ReportStore.statement(c, mapper, participantId, date, afterSeq, limit);
            }
            case CANDLES -> {
                String symbol = p.symbol("symbol", true);
                int date = p.date("date");
                int interval = p.intervalMinutes();
                return c -> ReportStore.candles(c, mapper, symbol, date, interval);
            }
            default -> throw new IllegalStateException("unrouted " + path);
        }
    }

    private ApiResponse execute(Worker worker, Query query) {
        beforeQuery.run();
        boolean fresh = worker.connection == null;   // a connection opened for this request is never retried
        try {
            if (fresh) {
                worker.connection = open();
            }
            return run(worker.connection, query);
        } catch (SQLException e) {
            if (!Database.isUnavailable(e)) return failed(worker, e);
            closeWorker(worker);
            if (fresh) return unavailable(e);
            try {
                worker.connection = open();
                return run(worker.connection, query);
            } catch (SQLException again) {
                if (!Database.isUnavailable(again)) return failed(worker, again);
                closeWorker(worker);
                return unavailable(again);
            }
        }
    }

    private ApiResponse run(Connection c, Query query) throws SQLException {
        try {
            ObjectNode body = query.run(c);
            c.commit();
            noteAvailable();
            return ApiResponse.ok(mapper.writeValueAsBytes(body));
        } catch (ApiException e) {
            rollbackQuietly(c);
            return e.response();
        } catch (ArithmeticException e) {
            rollbackQuietly(c);
            log.error("An /api report overflowed a long and was refused: {}", e.toString());
            return ApiResponse.OVERFLOW;
        } catch (JsonProcessingException e) {
            rollbackQuietly(c);
            throw new IllegalStateException("Cannot serialize an /api body", e);
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        }
    }

    private ApiResponse failed(Worker worker, SQLException e) {
        String state = e.getSQLState();
        if (NUMERIC_OUT_OF_RANGE.equals(state)) {
            log.error("An /api report overflowed a bigint and was refused: {}", e.getMessage());
            return ApiResponse.OVERFLOW;
        }
        if (QUERY_CANCELED.equals(state)) {
            logUnavailable("an /api query ran past its " + STATEMENT_TIMEOUT_MS + " ms timeout", e);
            return ApiResponse.DATABASE_UNAVAILABLE;
        }
        log.warn("An /api query failed (SQLState {})", state, e);
        try {
            if (worker.connection != null && worker.connection.isClosed()) closeWorker(worker);
        } catch (SQLException ignored) {
            closeWorker(worker);
        }
        return ApiResponse.INTERNAL;
    }

    private ApiResponse unavailable(SQLException e) {
        logUnavailable("the database cannot be reached", e);
        return ApiResponse.DATABASE_UNAVAILABLE;
    }

    private Connection open() throws SQLException {
        Connection c = connections.connect();
        try {
            try (Statement s = c.createStatement()) {
                s.execute("SET statement_timeout = " + STATEMENT_TIMEOUT_MS);
            }
            c.setAutoCommit(false);
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return c;
        } catch (SQLException e) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // already failing
            }
            throw e;
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the connection is going away; the server rolls back
        }
    }

    private static void closeWorker(Worker worker) {
        Connection c = worker.connection;
        worker.connection = null;
        if (c == null) return;
        try {
            c.close();
        } catch (SQLException ignored) {
            // nothing to recover
        }
    }

    private void logUnavailable(String what, SQLException e) {
        synchronized (logLock) {
            long now = System.nanoTime();
            if (!unavailableLogged || now - lastUnavailableLogNanos >= UNAVAILABLE_LOG_INTERVAL_NANOS) {
                log.error("/api answers 503: {} ({} not logged): {}", what, suppressedUnavailable, e.toString());
                unavailableLogged = true;
                lastUnavailableLogNanos = now;
                suppressedUnavailable = 0;
            } else {
                suppressedUnavailable++;
            }
        }
    }

    private void noteAvailable() {
        synchronized (logLock) {
            if (unavailableLogged) {
                log.info("/api reads the database again");
                unavailableLogged = false;
                suppressedUnavailable = 0;
            }
        }
    }

    /** Tasks queued or running now; for tests. */
    int pending() {
        return executor.pending();
    }

    @Override
    public void close() {
        executor.close();
    }
}
