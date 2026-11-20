package participant;

import model.Prices;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotSettingsTest {

    private static BotSettings parse(Map<String, String> env, String... args) {
        return BotSettings.from(args, env, () -> 7L);
    }

    private static BotSettings parse(String... args) {
        return parse(Map.of(), args);
    }

    private static String error(Map<String, String> env, String... args) {
        return assertThrows(IllegalArgumentException.class, () -> parse(env, args)).getMessage();
    }

    @Test
    void theDefaultsAreTheDevelopmentValuesFromBeforePhase16() {
        BotSettings s = parse();
        assertEquals(URI.create("http://localhost:8080"), s.venue());
        assertEquals(URI.create("ws://localhost:8080/ws"), s.wsUri());
        assertEquals(URI.create("http://localhost:8080/health"), s.healthUri());
        assertEquals(URI.create("http://localhost:8080/market/ignition"), s.ignitionUri());
        assertEquals("ASML", s.symbol());
        assertEquals(Prices.NA, s.openPxUnits());
        assertEquals(-1, s.durationSeconds());
        assertEquals(7L, s.seed());
        assertEquals(1.0, s.speed());
        assertEquals(BotSettings.ALL_BOTS, List.copyOf(s.enabled()));
        assertEquals(250, s.tickMillis());
        assertEquals("", s.csv(), "the timestamped CSV under run-output, as before");
        assertEquals(60_000, s.statusIntervalMillis());
        assertEquals(20, s.bandPercent());
        assertEquals(30_000, s.maxOrderQty());
        assertEquals(50, s.maxOpenOrders());
        assertEquals(20, s.makerLevels());
        assertEquals(2, s.makerHalfSpreadTicks());
        assertEquals(2, s.makerRequoteMoveTicks());
        assertEquals(10_000, s.makerRequoteIntervalMillis());
        assertEquals(0, s.makerMinRequoteGapMillis());
        assertEquals(18_500, s.makerMaxPositionResolved());
        assertEquals(300, s.makerSkewClip());
        assertEquals(10, s.makerMaxSkewTicks());
        assertEquals(0, s.makerAnchorMaxPullTicks());
        assertEquals(50, s.trendQty());
        assertEquals(1_500, s.trendMinIntervalMillis());
        assertEquals(3_000, s.trendMaxIntervalMillis());
        assertEquals(1_500, s.trendMaxPosition());
        assertEquals(4_000, s.revertRequoteIntervalMillis());
        assertEquals(2_000, s.revertMaxPosition());
        assertEquals(1_200, s.noiseMeanArrivalMillis());
        assertEquals(1_000, s.noiseMaxPosition());
        assertEquals(10_000, s.instMinParent());
        assertEquals(30_000, s.instMaxParent());
        assertEquals(40_000, s.instMaxPosition());
    }

    @Test
    void everySettingIsReadFromTheEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("BOTS_VENUE_URL", "http://exchange:8080");
        env.put("BOTS_SYMBOL", "ASML");
        env.put("BOTS_OPEN_PX", "1405.00");
        env.put("BOTS_ENABLED", "maker,noise");
        env.put("BOTS_SEED", "42");
        env.put("BOTS_SPEED", "2");
        env.put("BOTS_TICK_MS", "500");
        env.put("BOTS_CSV", "none");
        env.put("BOTS_STATUS_INTERVAL_MS", "0");
        env.put("BOTS_BAND_PERCENT", "15");
        env.put("BOTS_MAX_ORDER_QTY", "6000");
        env.put("BOTS_MAX_OPEN_ORDERS", "30");
        env.put("BOTS_MAKER_LEVELS", "10");
        env.put("BOTS_MAKER_HALF_SPREAD_TICKS", "3");
        env.put("BOTS_MAKER_REQUOTE_MOVE_TICKS", "4");
        env.put("BOTS_MAKER_REQUOTE_INTERVAL_MS", "30000");
        env.put("BOTS_MAKER_MIN_REQUOTE_GAP_MS", "1000");
        env.put("BOTS_MAKER_MAX_POSITION", "8500");
        env.put("BOTS_MAKER_SKEW_CLIP", "250");
        env.put("BOTS_MAKER_MAX_SKEW_TICKS", "8");
        env.put("BOTS_MAKER_ANCHOR_MAX_PULL_TICKS", "5");
        env.put("BOTS_MAKER_ANCHOR_PULL_DIVISOR", "25");
        env.put("BOTS_TREND_QTY", "40");
        env.put("BOTS_TREND_MIN_INTERVAL_MS", "3000");
        env.put("BOTS_TREND_MAX_INTERVAL_MS", "6000");
        env.put("BOTS_TREND_MAX_POSITION", "1200");
        env.put("BOTS_REVERT_REQUOTE_INTERVAL_MS", "8000");
        env.put("BOTS_REVERT_MAX_POSITION", "1800");
        env.put("BOTS_NOISE_MEAN_ARRIVAL_MS", "2400");
        env.put("BOTS_NOISE_MAX_POSITION", "900");
        env.put("BOTS_INST_MIN_PARENT", "2000");
        env.put("BOTS_INST_MAX_PARENT", "6000");
        env.put("BOTS_INST_MAX_POSITION", "10000");
        env.put("BOTS_DURATION_SECONDS", "3600");
        BotSettings s = parse(env);
        assertEquals(URI.create("ws://exchange:8080/ws"), s.wsUri());
        assertEquals(URI.create("http://exchange:8080/market/ignition"), s.ignitionUri());
        assertEquals(14_050_000L, s.openPxUnits());
        assertEquals(List.of("maker", "noise"), List.copyOf(s.enabled()));
        assertEquals(42, s.seed());
        assertEquals(2.0, s.speed());
        assertEquals(500, s.tickMillis());
        assertNull(s.csv());
        assertEquals(0, s.statusIntervalMillis());
        assertEquals(15, s.bandPercent());
        assertEquals(6_000, s.maxOrderQty());
        assertEquals(30, s.maxOpenOrders());
        assertEquals(10, s.makerLevels());
        assertEquals(3, s.makerHalfSpreadTicks());
        assertEquals(4, s.makerRequoteMoveTicks());
        assertEquals(30_000, s.makerRequoteIntervalMillis());
        assertEquals(1_000, s.makerMinRequoteGapMillis());
        assertEquals(8_500, s.makerMaxPositionResolved());
        assertEquals(250, s.makerSkewClip());
        assertEquals(8, s.makerMaxSkewTicks());
        assertEquals(5, s.makerAnchorMaxPullTicks());
        assertEquals(25, s.makerAnchorPullDivisor());
        assertEquals(40, s.trendQty());
        assertEquals(3_000, s.trendMinIntervalMillis());
        assertEquals(6_000, s.trendMaxIntervalMillis());
        assertEquals(1_200, s.trendMaxPosition());
        assertEquals(8_000, s.revertRequoteIntervalMillis());
        assertEquals(1_800, s.revertMaxPosition());
        assertEquals(2_400, s.noiseMeanArrivalMillis());
        assertEquals(900, s.noiseMaxPosition());
        assertEquals(2_000, s.instMinParent());
        assertEquals(6_000, s.instMaxParent());
        assertEquals(10_000, s.instMaxPosition());
        assertEquals(3_600, s.durationSeconds());
    }

    @Test
    void aFlagBeatsTheEnvironment() {
        Map<String, String> env = Map.of("BOTS_VENUE_URL", "http://exchange:8080", "BOTS_OPEN_PX", "1405.00",
                "BOTS_TICK_MS", "500", "BOTS_ENABLED", "maker");
        BotSettings s = parse(env, "--venueUrl", "https://venue.test", "--openPx", "1400.10", "--tickMs", "100",
                "--bots", "trend");
        assertEquals(URI.create("wss://venue.test/ws"), s.wsUri());
        assertEquals(14_001_000L, s.openPxUnits());
        assertEquals(100, s.tickMillis());
        assertEquals(List.of("trend"), List.copyOf(s.enabled()));
    }

    @Test
    void portMeansLocalhostAndBeatsTheEnvironmentButNotVenueUrl() {
        assertEquals(URI.create("ws://localhost:18080/ws"),
                parse(Map.of("BOTS_VENUE_URL", "http://exchange:8080"), "--port", "18080").wsUri());
        assertEquals(URI.create("ws://exchange:9000/ws"),
                parse(Map.of(), "--port", "18080", "--venueUrl", "http://exchange:9000").wsUri());
    }

    @Test
    void aBlankValueMeansTheDefault() {
        BotSettings s = parse(Map.of("BOTS_TICK_MS", "  ", "BOTS_OPEN_PX", "", "BOTS_MAKER_LEVELS", ""));
        assertEquals(250, s.tickMillis());
        assertEquals(Prices.NA, s.openPxUnits());
        assertEquals(20, s.makerLevels());
    }

    @Test
    void aDerivedMakerLimitFollowsTheLadder() {
        assertEquals(5_500 + 3_000, parse(Map.of("BOTS_MAKER_LEVELS", "10")).makerMaxPositionResolved());
    }

    @Test
    void eachBadValueNamesItsVariableOrFlag() {
        assertTrue(error(Map.of("BOTS_MAKER_LEVELS", "ten")).startsWith("BOTS_MAKER_LEVELS"));
        assertTrue(error(Map.of("BOTS_MAKER_LEVELS", "0")).startsWith("BOTS_MAKER_LEVELS"));
        assertTrue(error(Map.of("BOTS_MAX_OPEN_ORDERS", "-1")).startsWith("BOTS_MAX_OPEN_ORDERS"));
        assertTrue(error(Map.of("BOTS_SPEED", "0")).startsWith("BOTS_SPEED"));
        assertTrue(error(Map.of("BOTS_SPEED", "fast")).startsWith("BOTS_SPEED"));
        assertTrue(error(Map.of("BOTS_OPEN_PX", "1405.005")).startsWith("BOTS_OPEN_PX"));
        assertTrue(error(Map.of(), "--openPx", "abc").startsWith("--openPx"));
        assertTrue(error(Map.of(), "--tickMs", "0").startsWith("--tickMs"));
        assertTrue(error(Map.of("BOTS_VENUE_URL", "exchange:8080")).startsWith("BOTS_VENUE_URL"));
        assertTrue(error(Map.of("BOTS_VENUE_URL", "http://exchange:8080/ws")).startsWith("BOTS_VENUE_URL"));
        assertTrue(error(Map.of("BOTS_ENABLED", "maker,whale")).startsWith("BOTS_ENABLED"));
        assertTrue(error(Map.of("BOTS_ENABLED", ",")).startsWith("BOTS_ENABLED"));
        assertTrue(error(Map.of("BOTS_TREND_MIN_INTERVAL_MS", "5000", "BOTS_TREND_MAX_INTERVAL_MS", "4000"))
                .startsWith("BOTS_TREND_MAX_INTERVAL_MS"));
        assertTrue(error(Map.of("BOTS_INST_MIN_PARENT", "7000", "BOTS_INST_MAX_PARENT", "6000"))
                .startsWith("BOTS_INST_MAX_PARENT"));
    }

    @Test
    void unknownFlagsAndStrayArgumentsAreErrors() {
        assertTrue(error(Map.of(), "--envLocal", "../frontend/.env.local").contains("unknown flag --envLocal"));
        assertTrue(error(Map.of(), "maker").contains("unexpected argument"));
        assertTrue(error(Map.of(), "--seed").contains("needs a value"));
    }

    @Test
    void underscoresAreAllowedInNumbers() {
        assertEquals(30_000, parse(Map.of("BOTS_MAKER_REQUOTE_INTERVAL_MS", "30_000")).makerRequoteIntervalMillis());
    }

    @Test
    void theSettingsNeverPrintAnAlpacaKey() {
        // The bots read no Alpaca variable at all: even when the environment has them, nothing echoes them.
        BotSettings s = parse(Map.of("ALPACA_KEY_ID", "placeholder-id", "ALPACA_SECRET_KEY", "placeholder-secret"));
        assertTrue(!s.toString().contains("placeholder"));
    }
}
