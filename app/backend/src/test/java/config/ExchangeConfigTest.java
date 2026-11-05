package config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure tests of ExchangeConfig (D9): defaults, overrides from a map, validation. */
class ExchangeConfigTest {

    @Test
    void everyVariableHasItsDefault() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of());

        assertEquals(8080, cfg.port());
        assertEquals(Path.of("data").toAbsolutePath().normalize(), cfg.dataDir());
        assertEquals(cfg.dataDir().resolve("archive"), cfg.archiveDir());
        assertEquals(Path.of(System.getProperty("java.io.tmpdir"), "aeron-exchange").toAbsolutePath().normalize(),
                cfg.aeronDir());
        assertEquals(10_000L, cfg.snapshotInterval());
        assertTrue(cfg.sessionAutoOpen());
        assertNull(cfg.dbUrl(), "unset means no database: reference data from configuration");
        assertTrue(cfg.dbMigrateOnStart());
        assertNull(cfg.dbUser());
        assertNull(cfg.dbPassword());
        assertEquals(256 * 1024, cfg.journalTermLength());
        assertEquals(16 * 1024 * 1024, cfg.journalSegmentLength());
    }

    @Test
    void everyVariableCanBeOverridden() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of(
                "EXCHANGE_PORT", "9090",
                "EXCHANGE_DATA_DIR", "/srv/exchange",
                "AERON_DIR", "/dev/shm/aeron-x",
                "SNAPSHOT_INTERVAL", "500",
                "SESSION_AUTO_OPEN", "FALSE",
                "DB_URL", "jdbc:postgresql://db:5432/exchange_test",
                "DB_USER", "venue",
                "DB_PASSWORD", "secret",
                "JOURNAL_TERM_LENGTH", "65536",
                "JOURNAL_SEGMENT_LENGTH", "131072"));

        assertEquals(9090, cfg.port());
        assertEquals(Path.of("/srv/exchange").toAbsolutePath().normalize(), cfg.dataDir());
        assertEquals(Path.of("/dev/shm/aeron-x").toAbsolutePath().normalize(), cfg.aeronDir());
        assertEquals(500L, cfg.snapshotInterval());
        assertFalse(cfg.sessionAutoOpen());
        assertEquals("jdbc:postgresql://db:5432/exchange_test", cfg.dbUrl());
        assertEquals("venue", cfg.dbUser());
        assertEquals("secret", cfg.dbPassword());
        assertEquals(65_536, cfg.journalTermLength());
        assertEquals(131_072, cfg.journalSegmentLength());
    }

    @Test
    void blankValuesCountAsUnset() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of("EXCHANGE_PORT", "  ", "DB_USER", ""));
        assertEquals(8080, cfg.port());
        assertNull(cfg.dbUser());
    }

    @Test
    void malformedValuesNameTheVariable() {
        assertRejected(Map.of("EXCHANGE_PORT", "eighty"), "EXCHANGE_PORT");
        assertRejected(Map.of("EXCHANGE_PORT", "0"), "EXCHANGE_PORT");
        assertRejected(Map.of("EXCHANGE_PORT", "70000"), "EXCHANGE_PORT");
        assertRejected(Map.of("SNAPSHOT_INTERVAL", "0"), "SNAPSHOT_INTERVAL");
        assertRejected(Map.of("SESSION_AUTO_OPEN", "yes"), "SESSION_AUTO_OPEN");
    }

    @Test
    void journalLengthsMustBeValidPowersOfTwo() {
        assertRejected(Map.of("JOURNAL_TERM_LENGTH", "100000"), "JOURNAL_TERM_LENGTH");
        assertRejected(Map.of("JOURNAL_TERM_LENGTH", "32768"), "JOURNAL_TERM_LENGTH");
        assertRejected(Map.of("JOURNAL_SEGMENT_LENGTH", "2147483647"), "JOURNAL_SEGMENT_LENGTH");
        assertRejected(Map.of("JOURNAL_TERM_LENGTH", "1048576", "JOURNAL_SEGMENT_LENGTH", "524288"),
                "JOURNAL_SEGMENT_LENGTH");
    }

    @Test
    void thePasswordIsNeverPrinted() {
        String shown = ExchangeConfig.from(Map.of("DB_PASSWORD", "hunter2", "DB_USER", "venue")).toString();
        assertFalse(shown.contains("hunter2"), shown);
        assertTrue(shown.contains("dbPassword=***"), shown);
        assertTrue(ExchangeConfig.from(Map.of()).toString().contains("dbPassword=<unset>"));
    }

    @Test
    void aPasswordInTheDatabaseUrlIsNeverPrinted() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of(
                "DB_URL", "jdbc:postgresql://db:5432/x?user=venue&password=hunter2&ssl=true"));
        String shown = cfg.toString();
        assertFalse(shown.contains("hunter2"), shown);
        assertTrue(shown.contains("dbUrl=jdbc:postgresql://db:5432/x?user=venue&password=***&ssl=true"), shown);
        assertTrue(cfg.dbUrl().contains("hunter2"), "the value itself is untouched");
        assertTrue(ExchangeConfig.from(Map.of()).toString().contains("dbUrl=<unset>"));
    }

    @Test
    void migrateOnStartCanBeTurnedOff() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of("DB_MIGRATE_ON_START", "false"));
        assertFalse(cfg.dbMigrateOnStart());
        assertTrue(cfg.toString().contains("dbMigrateOnStart=false"), cfg.toString());
        assertRejected(Map.of("DB_MIGRATE_ON_START", "maybe"), "DB_MIGRATE_ON_START");
    }

    @Test
    void engineIdleStrategyDefaultsToBackoffAndAcceptsEachValue() {
        assertEquals(ExchangeConfig.EngineIdle.BACKOFF, ExchangeConfig.from(Map.of()).engineIdle());
        for (ExchangeConfig.EngineIdle idle : ExchangeConfig.EngineIdle.values()) {
            assertEquals(idle, ExchangeConfig.from(Map.of("ENGINE_IDLE_STRATEGY", idle.value())).engineIdle());
        }
        assertEquals(ExchangeConfig.EngineIdle.BUSY_SPIN,
                ExchangeConfig.from(Map.of("ENGINE_IDLE_STRATEGY", "Busy-Spin")).engineIdle(), "case-insensitive");
        assertRejected(Map.of("ENGINE_IDLE_STRATEGY", "spin"), "ENGINE_IDLE_STRATEGY");
        assertTrue(ExchangeConfig.from(Map.of()).toString().contains("engineIdle=backoff"));
    }

    @Test
    void sessionReferenceDataDefaultsToTheEnginesValuesAndNewYork() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of());
        assertEquals("ASML", cfg.sessionSymbol());
        assertEquals(100L, cfg.sessionTickSize());
        assertEquals(1L, cfg.sessionLotSize());
        assertEquals(10_000L, cfg.sessionPriceScale());
        assertEquals(java.time.ZoneId.of("America/New_York"), cfg.sessionTimeZone());
        assertTrue(cfg.sessionAutoOpen());
        assertTrue(cfg.toString().contains("sessionSymbol=ASML"), cfg.toString());
        assertTrue(cfg.toString().contains("sessionTimeZone=America/New_York"), cfg.toString());
    }

    @Test
    void sessionReferenceDataCanBeOverridden() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of(
                "SESSION_SYMBOL", "BRK.B",
                "SESSION_TICK_SIZE", "50",
                "SESSION_LOT_SIZE", "100",
                "SESSION_PRICE_SCALE", "100",
                "SESSION_TIME_ZONE", "Europe/Amsterdam",
                "SESSION_AUTO_OPEN", "false"));
        assertEquals("BRK.B", cfg.sessionSymbol());
        assertEquals(50L, cfg.sessionTickSize());
        assertEquals(100L, cfg.sessionLotSize());
        assertEquals(100L, cfg.sessionPriceScale());
        assertEquals(java.time.ZoneId.of("Europe/Amsterdam"), cfg.sessionTimeZone());
        assertFalse(cfg.sessionAutoOpen());
    }

    @Test
    void malformedSessionValuesNameTheVariable() {
        assertRejected(Map.of("SESSION_SYMBOL", "asml"), "SESSION_SYMBOL");
        assertRejected(Map.of("SESSION_SYMBOL", "TOOLONGSYM"), "SESSION_SYMBOL");
        assertRejected(Map.of("SESSION_TICK_SIZE", "0"), "SESSION_TICK_SIZE");
        assertRejected(Map.of("SESSION_LOT_SIZE", "-1"), "SESSION_LOT_SIZE");
        assertRejected(Map.of("SESSION_PRICE_SCALE", "ten"), "SESSION_PRICE_SCALE");
        assertRejected(Map.of("SESSION_TIME_ZONE", "Mars/Olympus"), "SESSION_TIME_ZONE");
    }

    private static void assertRejected(Map<String, String> env, String variable) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExchangeConfig.from(env));
        assertTrue(e.getMessage().contains(variable), e.getMessage());
    }
}
