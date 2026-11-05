package config;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every setting of the exchange process (D9), read once at startup from environment
 * variables, each with a development default. Immutable.
 *
 * Production reads the process environment through {@link #fromEnvironment()}. Tests build
 * a config from a plain map through {@link #from(Map)}, so they never touch the process
 * environment. A blank value counts as unset. A malformed value fails fast with an
 * {@link IllegalArgumentException} naming the variable.
 *
 * No credential has a default, and {@link #toString()} never prints the database password.
 */
public final class ExchangeConfig {

    public static final String EXCHANGE_PORT          = "EXCHANGE_PORT";
    public static final String EXCHANGE_DATA_DIR      = "EXCHANGE_DATA_DIR";
    public static final String AERON_DIR              = "AERON_DIR";
    public static final String SNAPSHOT_INTERVAL      = "SNAPSHOT_INTERVAL";
    public static final String SESSION_AUTO_OPEN      = "SESSION_AUTO_OPEN";
    public static final String DB_URL                 = "DB_URL";
    public static final String DB_USER                = "DB_USER";
    public static final String DB_PASSWORD            = "DB_PASSWORD";
    public static final String DB_MIGRATE_ON_START    = "DB_MIGRATE_ON_START";
    public static final String JOURNAL_TERM_LENGTH    = "JOURNAL_TERM_LENGTH";
    public static final String JOURNAL_SEGMENT_LENGTH = "JOURNAL_SEGMENT_LENGTH";
    public static final String ENGINE_IDLE_STRATEGY   = "ENGINE_IDLE_STRATEGY";
    // Session reference data (P15-8). With DB_URL set, the database supplies all but the symbol, which names its row.
    public static final String SESSION_SYMBOL        = "SESSION_SYMBOL";
    public static final String SESSION_TICK_SIZE      = "SESSION_TICK_SIZE";
    public static final String SESSION_LOT_SIZE       = "SESSION_LOT_SIZE";
    public static final String SESSION_PRICE_SCALE    = "SESSION_PRICE_SCALE";
    public static final String SESSION_TIME_ZONE      = "SESSION_TIME_ZONE";

    /**
     * How the engine agent waits when the journal has nothing new (P15-4). BACKOFF, the default,
     * spins and yields briefly after activity, then parks for up to 1 ms, so an idle venue uses
     * almost no CPU on a shared host. BUSY_SPIN burns a core and is for benchmarks only.
     */
    public enum EngineIdle {
        BACKOFF("backoff"), SLEEPING("sleeping"), YIELDING("yielding"), BUSY_SPIN("busy-spin");

        private final String value;

        EngineIdle(String value) { this.value = value; }

        /** The environment variable's spelling. */
        public String value() { return value; }
    }

    public static final int    DEFAULT_PORT                   = 8080;
    public static final long   DEFAULT_SNAPSHOT_INTERVAL      = 10_000L;
    /** 256 KiB: about 2,700 NEW commands per term, so three terms buffer about 8 inbound rings. */
    public static final int    DEFAULT_JOURNAL_TERM_LENGTH    = 256 * 1024;
    /** 16 MiB. The Archive preallocates every segment file to this size, so it stays small. */
    public static final int    DEFAULT_JOURNAL_SEGMENT_LENGTH = 16 * 1024 * 1024;

    public static final String DEFAULT_SESSION_SYMBOL         = "ASML";
    public static final long   DEFAULT_SESSION_TICK_SIZE      = 100L;
    public static final long   DEFAULT_SESSION_LOT_SIZE       = 1L;
    public static final long   DEFAULT_SESSION_PRICE_SCALE    = 10_000L;
    /** The business day follows New York: the terminal's market data is US equities (Alpaca). */
    public static final String DEFAULT_SESSION_TIME_ZONE      = "America/New_York";

    /** Aeron's bounds for both term and segment lengths (64 KiB to 1 GiB, powers of two). */
    static final int MIN_LENGTH = 64 * 1024;
    static final int MAX_LENGTH = 1024 * 1024 * 1024;

    /** A password parameter in a JDBC URL, which {@link #toString()} masks. */
    private static final Pattern URL_PASSWORD = Pattern.compile("(?i)([?&]password=)[^&]*");

    private final int     port;
    private final Path    dataDir;
    private final Path    aeronDir;
    private final long    snapshotInterval;
    private final boolean sessionAutoOpen;
    private final String  dbUrl;
    private final String  dbUser;
    private final String  dbPassword;
    private final boolean dbMigrateOnStart;
    private final int     journalTermLength;
    private final int     journalSegmentLength;
    private final EngineIdle engineIdle;
    private final String  sessionSymbol;
    private final long    sessionTickSize;
    private final long    sessionLotSize;
    private final long    sessionPriceScale;
    private final ZoneId  sessionTimeZone;

    private ExchangeConfig(Map<String, String> env) {
        this.port                 = intValue(env, EXCHANGE_PORT, DEFAULT_PORT, 1, 65_535);
        this.dataDir              = pathValue(env, EXCHANGE_DATA_DIR, Path.of("data"));
        this.aeronDir             = pathValue(env, AERON_DIR,
                Path.of(System.getProperty("java.io.tmpdir"), "aeron-exchange"));
        this.snapshotInterval     = longValue(env, SNAPSHOT_INTERVAL, DEFAULT_SNAPSHOT_INTERVAL, 1L);
        this.sessionAutoOpen      = booleanValue(env, SESSION_AUTO_OPEN, true);
        this.dbUrl                = stringValue(env, DB_URL, null);
        this.dbUser               = stringValue(env, DB_USER, null);
        this.dbPassword           = stringValue(env, DB_PASSWORD, null);
        this.dbMigrateOnStart     = booleanValue(env, DB_MIGRATE_ON_START, true);
        this.journalTermLength    = lengthValue(env, JOURNAL_TERM_LENGTH, DEFAULT_JOURNAL_TERM_LENGTH);
        this.journalSegmentLength = lengthValue(env, JOURNAL_SEGMENT_LENGTH, DEFAULT_JOURNAL_SEGMENT_LENGTH);
        this.engineIdle           = engineIdleValue(env, ENGINE_IDLE_STRATEGY, EngineIdle.BACKOFF);
        this.sessionSymbol        = symbolValue(env, SESSION_SYMBOL, DEFAULT_SESSION_SYMBOL);
        this.sessionTickSize      = longValue(env, SESSION_TICK_SIZE, DEFAULT_SESSION_TICK_SIZE, 1L);
        this.sessionLotSize       = longValue(env, SESSION_LOT_SIZE, DEFAULT_SESSION_LOT_SIZE, 1L);
        this.sessionPriceScale    = longValue(env, SESSION_PRICE_SCALE, DEFAULT_SESSION_PRICE_SCALE, 1L);
        this.sessionTimeZone      = zoneValue(env, SESSION_TIME_ZONE, DEFAULT_SESSION_TIME_ZONE);
        if (journalSegmentLength < journalTermLength) {
            throw new IllegalArgumentException(JOURNAL_SEGMENT_LENGTH + "=" + journalSegmentLength
                    + " is smaller than " + JOURNAL_TERM_LENGTH + "=" + journalTermLength);
        }
    }

    /** Reads the process environment. */
    public static ExchangeConfig fromEnvironment() {
        return from(System.getenv());
    }

    /** Reads the given variables; anything absent or blank takes its default. */
    public static ExchangeConfig from(Map<String, String> env) {
        return new ExchangeConfig(env);
    }

    /** Port the WebSocket and HTTP server listens on. */
    public int port() { return port; }

    /** Persistent data directory, absolute: Archive recordings and snapshots. */
    public Path dataDir() { return dataDir; }

    /** The Archive's directory under the data directory. Never cleared automatically. */
    public Path archiveDir() { return dataDir.resolve("archive"); }

    /** Engine snapshots under the data directory (P15-5). */
    public Path snapshotDir() { return dataDir.resolve("snapshots"); }

    /** Media driver working directory, absolute. Recreated when a driver starts. */
    public Path aeronDir() { return aeronDir; }

    /** Applied inbound commands between periodic snapshots. */
    public long snapshotInterval() { return snapshotInterval; }

    /** Whether the session opens automatically at startup (P15-8). */
    public boolean sessionAutoOpen() { return sessionAutoOpen; }

    /**
     * The session's symbol: 1 to 8 of {@code [A-Z0-9.]}. The tick, lot and scale below are only
     * checked to be positive here; whether the engine honours them is {@code engine.SessionRules}'
     * decision, applied at startup before any session command is issued.
     */
    public String sessionSymbol() { return sessionSymbol; }

    /** The session's tick size in units of $0.0001. */
    public long sessionTickSize() { return sessionTickSize; }

    /** The session's lot size. */
    public long sessionLotSize() { return sessionLotSize; }

    /** The session's price scale (units per dollar). */
    public long sessionPriceScale() { return sessionPriceScale; }

    /** The time zone whose calendar date is the business date. */
    public ZoneId sessionTimeZone() { return sessionTimeZone; }

    /**
     * JDBC URL of the venue's database, or null when unset. Unset means the venue runs without a
     * database and takes its session reference data from configuration. It may carry a password
     * parameter, so log {@link #toString()}, never this value.
     */
    public String dbUrl() { return dbUrl; }

    /** Database user, or null when unset. */
    public String dbUser() { return dbUser; }

    /** Database password, or null when unset. */
    public String dbPassword() { return dbPassword; }

    /** Whether startup applies pending migrations; false only validates (they ran as a pre-deploy command). */
    public boolean dbMigrateOnStart() { return dbMigrateOnStart; }

    /** Term buffer length of the journal's IPC streams, in bytes. */
    public int journalTermLength() { return journalTermLength; }

    /** Archive segment file length, in bytes. */
    public int journalSegmentLength() { return journalSegmentLength; }

    /** The engine agent's idle strategy. */
    public EngineIdle engineIdle() { return engineIdle; }

    @Override
    public String toString() {
        return "ExchangeConfig{port=" + port
                + ", dataDir=" + dataDir
                + ", aeronDir=" + aeronDir
                + ", snapshotInterval=" + snapshotInterval
                + ", sessionAutoOpen=" + sessionAutoOpen
                + ", dbUrl=" + (dbUrl == null ? "<unset>" : URL_PASSWORD.matcher(dbUrl).replaceAll("$1***"))
                + ", dbUser=" + (dbUser == null ? "<unset>" : dbUser)
                + ", dbPassword=" + (dbPassword == null ? "<unset>" : "***")
                + ", dbMigrateOnStart=" + dbMigrateOnStart
                + ", journalTermLength=" + journalTermLength
                + ", journalSegmentLength=" + journalSegmentLength
                + ", engineIdle=" + engineIdle.value()
                + ", sessionSymbol=" + sessionSymbol
                + ", sessionTickSize=" + sessionTickSize
                + ", sessionLotSize=" + sessionLotSize
                + ", sessionPriceScale=" + sessionPriceScale
                + ", sessionTimeZone=" + sessionTimeZone
                + '}';
    }

    // --- parsing ---

    private static String raw(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String stringValue(Map<String, String> env, String name, String fallback) {
        String value = raw(env, name);
        return value == null ? fallback : value;
    }

    private static String symbolValue(Map<String, String> env, String name, String fallback) {
        String value = stringValue(env, name, fallback);
        if (!value.matches("[A-Z0-9.]{1,8}")) {
            throw new IllegalArgumentException(name + " must be 1 to 8 characters of A-Z, 0-9 and '.': " + value);
        }
        return value;
    }

    private static ZoneId zoneValue(Map<String, String> env, String name, String fallback) {
        String value = stringValue(env, name, fallback);
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(name + " is not a time zone id: " + value, e);
        }
    }

    private static Path pathValue(Map<String, String> env, String name, Path fallback) {
        String value = raw(env, name);
        return (value == null ? fallback : Path.of(value)).toAbsolutePath().normalize();
    }

    private static long longValue(Map<String, String> env, String name, long fallback, long min) {
        String value = raw(env, name);
        if (value == null) return fallback;
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a whole number: " + value);
        }
        if (parsed < min) {
            throw new IllegalArgumentException(name + " must be at least " + min + ": " + value);
        }
        return parsed;
    }

    private static int intValue(Map<String, String> env, String name, int fallback, int min, int max) {
        long parsed = longValue(env, name, fallback, min);
        if (parsed > max) {
            throw new IllegalArgumentException(name + " must be at most " + max + ": " + parsed);
        }
        return (int) parsed;
    }

    private static int lengthValue(Map<String, String> env, String name, int fallback) {
        int parsed = intValue(env, name, fallback, MIN_LENGTH, MAX_LENGTH);
        if (Integer.bitCount(parsed) != 1) {
            throw new IllegalArgumentException(name + " must be a power of two: " + parsed);
        }
        return parsed;
    }

    private static EngineIdle engineIdleValue(Map<String, String> env, String name, EngineIdle fallback) {
        String value = raw(env, name);
        if (value == null) return fallback;
        String wanted = value.toLowerCase(Locale.ROOT);
        for (EngineIdle idle : EngineIdle.values()) {
            if (idle.value().equals(wanted)) return idle;
        }
        throw new IllegalArgumentException(name + " must be backoff, sleeping, yielding or busy-spin: " + value);
    }

    private static boolean booleanValue(Map<String, String> env, String name, boolean fallback) {
        String value = raw(env, name);
        if (value == null) return fallback;
        if (value.equalsIgnoreCase("true"))  return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(name + " must be true or false: " + value);
    }
}
