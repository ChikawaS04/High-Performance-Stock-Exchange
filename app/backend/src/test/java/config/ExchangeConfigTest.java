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
        assertEquals("jdbc:postgresql://localhost:5432/exchange_dev", cfg.dbUrl());
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

    private static void assertRejected(Map<String, String> env, String variable) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExchangeConfig.from(env));
        assertTrue(e.getMessage().contains(variable), e.getMessage());
    }
}
