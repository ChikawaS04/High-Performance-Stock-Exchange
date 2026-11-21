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
    void journalIdleStrategyDefaultsToBackoffAndAcceptsEachValue() {
        assertEquals(ExchangeConfig.EngineIdle.BACKOFF, ExchangeConfig.from(Map.of()).journalIdle());
        for (ExchangeConfig.EngineIdle idle : ExchangeConfig.EngineIdle.values()) {
            assertEquals(idle, ExchangeConfig.from(Map.of("JOURNAL_IDLE_STRATEGY", idle.value())).journalIdle());
        }
        assertEquals(ExchangeConfig.EngineIdle.YIELDING,
                ExchangeConfig.from(Map.of("JOURNAL_IDLE_STRATEGY", "YIELDING")).journalIdle(), "case-insensitive");
        assertEquals(ExchangeConfig.EngineIdle.BACKOFF,
                ExchangeConfig.from(Map.of("ENGINE_IDLE_STRATEGY", "busy-spin")).journalIdle(),
                "independent of the engine's setting");
        assertRejected(Map.of("JOURNAL_IDLE_STRATEGY", "noop"), "JOURNAL_IDLE_STRATEGY");
        assertTrue(ExchangeConfig.from(Map.of()).toString().contains("journalIdle=backoff"));
    }

    @Test
    void ringWaitStrategyDefaultsToBlockingAndAcceptsEachValue() {
        assertEquals(ExchangeConfig.RingWait.BLOCKING, ExchangeConfig.from(Map.of()).ringWait());
        for (ExchangeConfig.RingWait wait : ExchangeConfig.RingWait.values()) {
            assertEquals(wait, ExchangeConfig.from(Map.of("RING_WAIT_STRATEGY", wait.value())).ringWait());
        }
        assertEquals(ExchangeConfig.RingWait.BUSY_SPIN,
                ExchangeConfig.from(Map.of("RING_WAIT_STRATEGY", " Busy-Spin ")).ringWait(), "case-insensitive, trimmed");
        assertRejected(Map.of("RING_WAIT_STRATEGY", "backoff"), "RING_WAIT_STRATEGY");
        assertRejected(Map.of("RING_WAIT_STRATEGY", "sleeping"), "RING_WAIT_STRATEGY");
        assertTrue(ExchangeConfig.from(Map.of()).toString().contains("ringWait=blocking"));
    }

    @Test
    void archiveThreadingDefaultsToSharedAndAcceptsEachValue() {
        assertEquals(ExchangeConfig.ArchiveThreading.SHARED, ExchangeConfig.from(Map.of()).archiveThreading());
        for (ExchangeConfig.ArchiveThreading threading : ExchangeConfig.ArchiveThreading.values()) {
            assertEquals(threading,
                    ExchangeConfig.from(Map.of("JOURNAL_ARCHIVE_THREADING", threading.value())).archiveThreading());
        }
        assertEquals(ExchangeConfig.ArchiveThreading.DEDICATED,
                ExchangeConfig.from(Map.of("JOURNAL_ARCHIVE_THREADING", "Dedicated")).archiveThreading(),
                "case-insensitive");
        assertRejected(Map.of("JOURNAL_ARCHIVE_THREADING", "invoker"), "JOURNAL_ARCHIVE_THREADING");
        assertTrue(ExchangeConfig.from(Map.of()).toString().contains("archiveThreading=shared"));
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

    // --- Alpaca settings for the ignition price (P16-3) ---

    @Test
    void alpacaSettingsDefaultToOffWithTheProductionDataUrl() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of());
        assertNull(cfg.alpacaKeyId());
        assertNull(cfg.alpacaSecretKey());
        assertFalse(cfg.ignitionEnabled());
        assertEquals("https://data.alpaca.markets", cfg.alpacaDataUrl());
    }

    @Test
    void bothKeysEnableTheIgnitionPrice() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of(
                "ALPACA_KEY_ID", "test-key-id", "ALPACA_SECRET_KEY", "test-secret",
                "ALPACA_DATA_URL", "http://127.0.0.1:9999/"));
        assertTrue(cfg.ignitionEnabled());
        assertEquals("test-key-id", cfg.alpacaKeyId());
        assertEquals("test-secret", cfg.alpacaSecretKey());
        assertEquals("http://127.0.0.1:9999", cfg.alpacaDataUrl(), "the trailing slash is dropped");
    }

    @Test
    void oneKeyWithoutTheOtherRefusesStartupNamingTheMissingOne() {
        assertRejected(Map.of("ALPACA_KEY_ID", "test-key-id"), "ALPACA_SECRET_KEY");
        assertRejected(Map.of("ALPACA_SECRET_KEY", "test-secret"), "ALPACA_KEY_ID");
        assertFalse(ExchangeConfig.from(Map.of("ALPACA_KEY_ID", " ", "ALPACA_SECRET_KEY", "")).ignitionEnabled(),
                "blank keys count as unset");
    }

    @Test
    void aMalformedDataUrlIsRejected() {
        assertRejected(Map.of("ALPACA_DATA_URL", "data.alpaca.markets"), "ALPACA_DATA_URL");
        assertRejected(Map.of("ALPACA_DATA_URL", "ftp://data.alpaca.markets"), "ALPACA_DATA_URL");
        assertRejected(Map.of("ALPACA_DATA_URL", "https://data.alpaca.markets?x=1"), "ALPACA_DATA_URL");
        assertRejected(Map.of("ALPACA_DATA_URL", "https://user:pw@data.alpaca.markets"), "ALPACA_DATA_URL");
        assertRejected(Map.of("ALPACA_DATA_URL", "https://exa mple.com"), "ALPACA_DATA_URL");
    }

    @Test
    void toStringMasksBothAlpacaKeys() {
        String shown = ExchangeConfig.from(Map.of(
                "ALPACA_KEY_ID", "test-key-id", "ALPACA_SECRET_KEY", "test-secret")).toString();
        assertFalse(shown.contains("test-key-id"), shown);
        assertFalse(shown.contains("test-secret"), shown);
        assertTrue(shown.contains("alpacaKeyId=***"), shown);
        assertTrue(shown.contains("alpacaSecretKey=***"), shown);
        assertTrue(shown.contains("alpacaDataUrl=https://data.alpaca.markets"), shown);
        String unset = ExchangeConfig.from(Map.of()).toString();
        assertTrue(unset.contains("alpacaKeyId=<unset>") && unset.contains("alpacaSecretKey=<unset>"), unset);
    }

    @Test
    void journalRetentionDefaultsToTwoDaysEnabled() {
        ExchangeConfig config = ExchangeConfig.from(Map.of());
        assertEquals(2, config.journalRetentionDays());
        assertTrue(config.journalRetentionEnabled());
        assertTrue(config.toString().contains("journalRetentionDays=2"));
        assertTrue(config.toString().contains("journalRetentionEnabled=true"));
        assertEquals(2, ExchangeConfig.from(Map.of("JOURNAL_RETENTION_DAYS", " ")).journalRetentionDays(),
                "blank is the default (credo.env.example)");
    }

    @Test
    void journalRetentionDaysAcceptsZeroTo366() {
        assertEquals(0, ExchangeConfig.from(Map.of("JOURNAL_RETENTION_DAYS", "0")).journalRetentionDays());
        assertEquals(366, ExchangeConfig.from(Map.of("JOURNAL_RETENTION_DAYS", "366")).journalRetentionDays());
        assertRejected(Map.of("JOURNAL_RETENTION_DAYS", "-1"), "JOURNAL_RETENTION_DAYS");
        assertRejected(Map.of("JOURNAL_RETENTION_DAYS", "367"), "JOURNAL_RETENTION_DAYS");
        assertRejected(Map.of("JOURNAL_RETENTION_DAYS", "two"), "JOURNAL_RETENTION_DAYS");
    }

    @Test
    void journalRetentionCanBeSwitchedOff() {
        assertFalse(ExchangeConfig.from(Map.of("JOURNAL_RETENTION_ENABLED", "false")).journalRetentionEnabled());
        assertTrue(ExchangeConfig.from(Map.of("JOURNAL_RETENTION_ENABLED", "TRUE")).journalRetentionEnabled());
        assertRejected(Map.of("JOURNAL_RETENTION_ENABLED", "off"), "JOURNAL_RETENTION_ENABLED");
    }

    // --- P16-8: database retention and the disk guard ---

    @Test
    void databaseRetentionAndDiskDefaults() {
        ExchangeConfig config = ExchangeConfig.from(Map.of());
        assertEquals(14, config.dbRetentionDays());
        assertTrue(config.dbRetentionEnabled());
        assertEquals(5_000, config.dbRetentionBatchRows());
        assertTrue(config.dbRetentionVacuum());
        assertEquals(15L * 1024 * 1024 * 1024, config.diskWarnFreeBytes());
        assertEquals(5L * 1024 * 1024 * 1024, config.diskStopFreeBytes());
        assertEquals(1024L * 1024 * 1024, config.diskStopHysteresisBytes());
        assertEquals(1024L * 1024 * 1024, config.journalLowStorageBytes());
        assertTrue(config.toString().contains("dbRetentionDays=14"));
        assertTrue(config.toString().contains("diskStopFreeBytes=5368709120"));
        assertEquals(14, ExchangeConfig.from(Map.of("DB_RETENTION_DAYS", " ")).dbRetentionDays(),
                "blank is the default (credo.env.example)");
    }

    @Test
    void databaseRetentionRanges() {
        assertEquals(0, ExchangeConfig.from(Map.of("DB_RETENTION_DAYS", "0")).dbRetentionDays());
        assertEquals(3_660, ExchangeConfig.from(Map.of("DB_RETENTION_DAYS", "3660")).dbRetentionDays());
        assertRejected(Map.of("DB_RETENTION_DAYS", "-1"), "DB_RETENTION_DAYS");
        assertRejected(Map.of("DB_RETENTION_DAYS", "3661"), "DB_RETENTION_DAYS");
        assertEquals(100, ExchangeConfig.from(Map.of("DB_RETENTION_BATCH_ROWS", "100")).dbRetentionBatchRows());
        assertRejected(Map.of("DB_RETENTION_BATCH_ROWS", "99"), "DB_RETENTION_BATCH_ROWS");
        assertRejected(Map.of("DB_RETENTION_BATCH_ROWS", "100001"), "DB_RETENTION_BATCH_ROWS");
        assertFalse(ExchangeConfig.from(Map.of("DB_RETENTION_ENABLED", "false")).dbRetentionEnabled());
        assertFalse(ExchangeConfig.from(Map.of("DB_RETENTION_VACUUM", "false")).dbRetentionVacuum());
        assertRejected(Map.of("DB_RETENTION_VACUUM", "no"), "DB_RETENTION_VACUUM");
    }

    @Test
    void diskThresholdsAreCheckedAgainstEachOther() {
        assertRejected(Map.of("DISK_WARN_FREE_BYTES", "-1"), "DISK_WARN_FREE_BYTES");
        assertRejected(Map.of("DISK_STOP_FREE_BYTES", "5GiB"), "DISK_STOP_FREE_BYTES");
        // The warning must come before the stop.
        assertRejected(Map.of("DISK_WARN_FREE_BYTES", "4000000000", "DISK_STOP_FREE_BYTES", "5000000000"),
                "DISK_STOP_FREE_BYTES");
        // The Archive's floor must be below the stop, so a venue under the stop can still start.
        assertRejected(Map.of("JOURNAL_LOW_STORAGE_BYTES", "5368709120"), "JOURNAL_LOW_STORAGE_BYTES");
        // And at least one segment.
        assertRejected(Map.of("JOURNAL_LOW_STORAGE_BYTES", "65536"), "JOURNAL_LOW_STORAGE_BYTES");
        // The stop off: the floor only needs a segment, and the warning may be anything.
        ExchangeConfig off = ExchangeConfig.from(Map.of("DISK_STOP_FREE_BYTES", "0", "DISK_WARN_FREE_BYTES", "0",
                "JOURNAL_LOW_STORAGE_BYTES", "68719476736"));
        assertEquals(0L, off.diskStopFreeBytes());
        assertEquals(68_719_476_736L, off.journalLowStorageBytes());
    }

    // --- public access limits (P16-9) ---

    @Test
    void theAccessLimitsHaveTheirDefaults() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of());
        assertEquals(10, cfg.wsChannelRate());
        assertEquals(30, cfg.wsChannelBurst());
        assertEquals(10, cfg.wsClientRate());
        assertEquals(30, cfg.wsClientBurst());
        assertEquals(20, cfg.wsPublicRate());
        assertEquals(100, cfg.wsPublicBurst());
        assertEquals(5_000, cfg.wsOverLimitCloseMillis());
        assertEquals(200, cfg.wsMaxConnections());
        assertEquals(10, cfg.wsMaxConnectionsPerClient());
        assertEquals(4_096, cfg.wsIdleClientsMax());
        assertEquals(java.util.List.of(), cfg.wsTrustedProxies());
        assertEquals(java.util.List.of("127.0.0.1", "::1"), cfg.wsExemptAddresses(),
                "loopback, so development and the existing tests are unchanged");
        assertEquals(1, cfg.resendPublicMaxPending());
        assertEquals(60_000L, cfg.resendPublicIntervalMillis());
        assertEquals(10_000, cfg.resendPublicMaxEvents());
    }

    @Test
    void theAccessLimitsCanBeSetAndAddressesAreCanonical() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.ofEntries(
                Map.entry("WS_CHANNEL_RATE", "5"), Map.entry("WS_CHANNEL_BURST", "6"),
                Map.entry("WS_CLIENT_RATE", "7"), Map.entry("WS_CLIENT_BURST", "8"),
                Map.entry("WS_PUBLIC_RATE", "9"), Map.entry("WS_PUBLIC_BURST", "10"),
                Map.entry("WS_OVER_LIMIT_CLOSE_MS", "2000"), Map.entry("WS_MAX_CONNECTIONS", "50"),
                Map.entry("WS_MAX_CONNECTIONS_PER_CLIENT", "4"), Map.entry("WS_IDLE_CLIENTS_MAX", "100"),
                Map.entry("WS_TRUSTED_PROXIES", " 172.30.0.1 "),
                Map.entry("WS_EXEMPT_ADDRESSES", "172.30.0.10, 2001:DB8:0:0::1"),
                Map.entry("RESEND_PUBLIC_MAX_PENDING", "0"), Map.entry("RESEND_PUBLIC_INTERVAL_MS", "0"),
                Map.entry("RESEND_PUBLIC_MAX_EVENTS", "500")));
        assertEquals(5, cfg.wsChannelRate());
        assertEquals(10, cfg.wsPublicBurst());
        assertEquals(2_000, cfg.wsOverLimitCloseMillis());
        assertEquals(4, cfg.wsMaxConnectionsPerClient());
        assertEquals(java.util.List.of("172.30.0.1"), cfg.wsTrustedProxies());
        assertEquals(java.util.List.of("172.30.0.10", "2001:db8::1"), cfg.wsExemptAddresses(), "canonical spelling");
        assertEquals(0, cfg.resendPublicMaxPending());
        assertEquals(0L, cfg.resendPublicIntervalMillis());
        assertEquals(500, cfg.resendPublicMaxEvents());
        assertTrue(cfg.toString().contains("wsExemptAddresses=[172.30.0.10"), cfg.toString());
        assertEquals(java.util.List.of(), ExchangeConfig.from(Map.of("WS_EXEMPT_ADDRESSES", "none")).wsExemptAddresses());
    }

    @Test
    void theAccessLimitsAreValidated() {
        assertRejected(Map.of("WS_CHANNEL_RATE", "0"), "WS_CHANNEL_RATE");
        assertRejected(Map.of("WS_PUBLIC_BURST", "1000001"), "WS_PUBLIC_BURST");
        assertRejected(Map.of("WS_OVER_LIMIT_CLOSE_MS", "999"), "WS_OVER_LIMIT_CLOSE_MS");
        assertRejected(Map.of("WS_MAX_CONNECTIONS", "5", "WS_MAX_CONNECTIONS_PER_CLIENT", "6"),
                "WS_MAX_CONNECTIONS_PER_CLIENT");
        assertRejected(Map.of("WS_IDLE_CLIENTS_MAX", "15"), "WS_IDLE_CLIENTS_MAX");
        assertRejected(Map.of("RESEND_PUBLIC_MAX_PENDING", "5"), "RESEND_PUBLIC_MAX_PENDING");
        assertRejected(Map.of("RESEND_PUBLIC_INTERVAL_MS", "86400001"), "RESEND_PUBLIC_INTERVAL_MS");
        assertRejected(Map.of("RESEND_PUBLIC_MAX_EVENTS", "0"), "RESEND_PUBLIC_MAX_EVENTS");
        // Literals only: never a host name (no DNS), a port, brackets or a zone.
        assertRejected(Map.of("WS_TRUSTED_PROXIES", "caddy"), "WS_TRUSTED_PROXIES");
        assertRejected(Map.of("WS_TRUSTED_PROXIES", "172.30.0.1:80"), "WS_TRUSTED_PROXIES");
        assertRejected(Map.of("WS_EXEMPT_ADDRESSES", "[::1]"), "WS_EXEMPT_ADDRESSES");
        assertRejected(Map.of("WS_EXEMPT_ADDRESSES", "fe80::1%eth0"), "WS_EXEMPT_ADDRESSES");
        // A per-client cap above a smaller venue cap is clamped by default, not refused.
        assertEquals(3, ExchangeConfig.from(Map.of("WS_MAX_CONNECTIONS", "3")).wsMaxConnectionsPerClient());
    }

    @Test
    void theTrustedProxyCanNeverBeExempt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExchangeConfig.from(Map.of(
                "WS_TRUSTED_PROXIES", "172.30.0.1", "WS_EXEMPT_ADDRESSES", "172.30.0.10,172.30.0.1")));
        assertTrue(e.getMessage().contains("WS_EXEMPT_ADDRESSES") && e.getMessage().contains("WS_TRUSTED_PROXIES"),
                e.getMessage());
        // Two spellings of one address still overlap.
        assertThrows(IllegalArgumentException.class, () -> ExchangeConfig.from(Map.of(
                "WS_TRUSTED_PROXIES", "::1")));   // the default exempt list holds loopback
        assertThrows(IllegalArgumentException.class, () -> ExchangeConfig.from(Map.of(
                "WS_TRUSTED_PROXIES", "0:0:0:0:0:0:0:1", "WS_EXEMPT_ADDRESSES", "::1")));
    }

    private static void assertRejected(Map<String, String> env, String variable) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExchangeConfig.from(env));
        assertTrue(e.getMessage().contains(variable), e.getMessage());
    }
}
