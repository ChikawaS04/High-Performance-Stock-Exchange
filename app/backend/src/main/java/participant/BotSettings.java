package participant;

import model.Prices;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Every bot setting (P16-5, D16), resolved from the command-line flags, then the environment, then
 * the development default, which is the value the bots used before Phase 16. A flag always wins;
 * an empty environment value means unset, as in {@code config.ExchangeConfig}. A bad value stops
 * the start with a message naming the flag or variable and the value it was given.
 *
 * <p>Flags kept from before (all optional): {@code --port --venueUrl --symbol --openPx --duration
 * --seed --speed --bots --tickMs --csv}. {@code --port N} means {@code http://localhost:N}. Every
 * other setting is an environment variable only; {@code deploy/credo.env.example} lists them with
 * their production values. An unknown flag is an error.
 */
public record BotSettings(
        URI venue,
        String symbol,
        long openPxUnits,                // BOTS_OPEN_PX in units, Prices.NA when unset
        long durationSeconds,            // -1 = run until stopped
        long seed,
        double speed,
        Set<String> enabled,
        long tickMillis,
        String csv,                      // null = no observer and no CSV; "" = the timestamped default
        long statusIntervalMillis,
        int bandPercent,
        long maxOrderQty,
        int maxOpenOrders,
        int makerLevels,
        int makerHalfSpreadTicks,
        int makerRequoteMoveTicks,
        long makerRequoteIntervalMillis,
        long makerMinRequoteGapMillis,
        long makerMaxPosition,           // -1 = derived from the ladder (a side plus 3,000)
        long makerSkewClip,
        int makerMaxSkewTicks,
        int makerAnchorMaxPullTicks,
        int makerAnchorPullDivisor,
        long trendQty,
        long trendMinIntervalMillis,
        long trendMaxIntervalMillis,
        long trendMaxPosition,
        long revertRequoteIntervalMillis,
        long revertMaxPosition,
        long noiseMeanArrivalMillis,
        long noiseMaxPosition,
        long instMinParent,
        long instMaxParent,
        long instMaxPosition) {

    public static final List<String> ALL_BOTS = List.of("maker", "trend", "revert", "noise", "inst");
    static final List<String> FLAGS = List.of("port", "venueUrl", "symbol", "openPx", "duration", "seed",
            "speed", "bots", "tickMs", "csv");

    /** The WebSocket endpoint: {@code ws(s)://host:port/ws}. */
    public URI wsUri() {
        String scheme = "https".equals(venue.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + venue.getRawAuthority() + "/ws");
    }

    public URI healthUri() {
        return venue.resolve("/health");
    }

    public URI ignitionUri() {
        return venue.resolve("/market/ignition");
    }

    /** The maker's position limit: the setting, or a whole side of the ladder plus 3,000. */
    public long makerMaxPositionResolved() {
        return makerMaxPosition > 0 ? makerMaxPosition
                : MarketMakerStrategy.Config.defaultMaxPosition(MarketMakerStrategy.Config.levelQtys(makerLevels));
    }

    /**
     * Resolve from flags and environment.
     *
     * @param args        the command line, {@code --name value} pairs
     * @param env         the environment ({@code System.getenv()})
     * @param defaultSeed the seed when none is given (the caller passes {@code System::nanoTime})
     */
    public static BotSettings from(String[] args, Map<String, String> env, LongSupplier defaultSeed) {
        Source s = new Source(flags(args), env);

        URI venue;
        String port = s.flag("port");
        String url = s.get("venueUrl", "BOTS_VENUE_URL");
        if (s.flag("venueUrl") == null && port != null) {
            int p = (int) s.integer("port", null, 1, 65_535, 0);
            venue = URI.create("http://localhost:" + p);
        } else {
            venue = venue(url == null ? "http://localhost:8080" : url, s.name("venueUrl", "BOTS_VENUE_URL"));
        }

        String symbol = s.get("symbol", "BOTS_SYMBOL");
        if (symbol == null) {
            symbol = "ASML";
        }
        String open = s.get("openPx", "BOTS_OPEN_PX");
        long openUnits = open == null ? Prices.NA : OpenPriceSource.parseDollars(open, s.name("openPx", "BOTS_OPEN_PX"));

        long duration = s.get("duration", "BOTS_DURATION_SECONDS") == null ? -1
                : s.integer("duration", "BOTS_DURATION_SECONDS", 1, Long.MAX_VALUE, 0);
        long seed = s.get("seed", "BOTS_SEED") == null ? defaultSeed.getAsLong()
                : s.integer("seed", "BOTS_SEED", Long.MIN_VALUE, Long.MAX_VALUE, 0);
        double speed = s.decimal("speed", "BOTS_SPEED", 1.0);

        Set<String> enabled = new LinkedHashSet<>();
        String bots = s.get("bots", "BOTS_ENABLED");
        for (String b : (bots == null ? String.join(",", ALL_BOTS) : bots).toLowerCase(Locale.ROOT).split(",")) {
            String name = b.trim();
            if (name.isEmpty()) continue;
            if (!ALL_BOTS.contains(name)) {
                throw new IllegalArgumentException(s.name("bots", "BOTS_ENABLED") + ": unknown bot '" + name
                        + "'; choose from " + ALL_BOTS);
            }
            enabled.add(name);
        }
        if (enabled.isEmpty()) {
            throw new IllegalArgumentException(s.name("bots", "BOTS_ENABLED") + " names no bot");
        }

        String csvRaw = s.get("csv", "BOTS_CSV");
        String csv = csvRaw == null ? "" : ("none".equalsIgnoreCase(csvRaw) ? null : csvRaw);

        int levels = (int) s.integer(null, "BOTS_MAKER_LEVELS", 1, 50, 20);
        long trendMin = s.integer(null, "BOTS_TREND_MIN_INTERVAL_MS", 1, Long.MAX_VALUE, 1_500);
        long trendMax = s.integer(null, "BOTS_TREND_MAX_INTERVAL_MS", 1, Long.MAX_VALUE, 3_000);
        if (trendMax < trendMin) {
            throw new IllegalArgumentException("BOTS_TREND_MAX_INTERVAL_MS (" + trendMax
                    + ") is below BOTS_TREND_MIN_INTERVAL_MS (" + trendMin + ")");
        }
        long instMin = s.integer(null, "BOTS_INST_MIN_PARENT", 1, Long.MAX_VALUE, 10_000);
        long instMax = s.integer(null, "BOTS_INST_MAX_PARENT", 1, Long.MAX_VALUE, 30_000);
        if (instMax < instMin) {
            throw new IllegalArgumentException("BOTS_INST_MAX_PARENT (" + instMax
                    + ") is below BOTS_INST_MIN_PARENT (" + instMin + ")");
        }

        return new BotSettings(
                venue, symbol, openUnits, duration, seed, speed, enabled,
                s.integer("tickMs", "BOTS_TICK_MS", 1, 60_000, 250),
                csv,
                s.integer(null, "BOTS_STATUS_INTERVAL_MS", 0, Long.MAX_VALUE, 60_000),
                (int) s.integer(null, "BOTS_BAND_PERCENT", 1, 99, RiskLimits.DEFAULT_BAND_PERCENT),
                s.integer(null, "BOTS_MAX_ORDER_QTY", 1, Long.MAX_VALUE, 30_000),
                (int) s.integer(null, "BOTS_MAX_OPEN_ORDERS", 1, 10_000, 50),
                levels,
                (int) s.integer(null, "BOTS_MAKER_HALF_SPREAD_TICKS", 1, 1_000, 2),
                (int) s.integer(null, "BOTS_MAKER_REQUOTE_MOVE_TICKS", 1, 1_000, 2),
                s.integer(null, "BOTS_MAKER_REQUOTE_INTERVAL_MS", 1, Long.MAX_VALUE, 10_000),
                s.integer(null, "BOTS_MAKER_MIN_REQUOTE_GAP_MS", 0, Long.MAX_VALUE, 0),
                s.integer(null, "BOTS_MAKER_MAX_POSITION", 1, Long.MAX_VALUE, -1),
                s.integer(null, "BOTS_MAKER_SKEW_CLIP", 1, Long.MAX_VALUE, 300),
                (int) s.integer(null, "BOTS_MAKER_MAX_SKEW_TICKS", 0, 1_000, 10),
                (int) s.integer(null, "BOTS_MAKER_ANCHOR_MAX_PULL_TICKS", 0, 1_000, 0),
                (int) s.integer(null, "BOTS_MAKER_ANCHOR_PULL_DIVISOR", 1, 1_000_000, 20),
                s.integer(null, "BOTS_TREND_QTY", 1, Long.MAX_VALUE, 50),
                trendMin, trendMax,
                s.integer(null, "BOTS_TREND_MAX_POSITION", 1, Long.MAX_VALUE, 1_500),
                s.integer(null, "BOTS_REVERT_REQUOTE_INTERVAL_MS", 1, Long.MAX_VALUE, 4_000),
                s.integer(null, "BOTS_REVERT_MAX_POSITION", 1, Long.MAX_VALUE, 2_000),
                s.integer(null, "BOTS_NOISE_MEAN_ARRIVAL_MS", 1, Long.MAX_VALUE, 1_200),
                s.integer(null, "BOTS_NOISE_MAX_POSITION", 1, Long.MAX_VALUE, 1_000),
                instMin, instMax,
                s.integer(null, "BOTS_INST_MAX_POSITION", 1, Long.MAX_VALUE, 40_000));
    }

    @Override
    public String toString() {
        return "BotSettings{venue=" + venue + ", symbol=" + symbol + ", openPx=" + (openPxUnits > 0 ? openPxUnits : "<unset>")
                + ", bots=" + enabled + ", speed=" + speed + ", tickMs=" + tickMillis
                + ", maxOrderQty=" + maxOrderQty + ", maxOpenOrders=" + maxOpenOrders + ", band=" + bandPercent + "%"
                + ", maker{levels=" + makerLevels + ", halfSpread=" + makerHalfSpreadTicks
                + ", moveTicks=" + makerRequoteMoveTicks + ", interval=" + makerRequoteIntervalMillis
                + ", minGap=" + makerMinRequoteGapMillis + ", maxPos=" + makerMaxPositionResolved()
                + ", skewClip=" + makerSkewClip + ", maxSkew=" + makerMaxSkewTicks
                + ", pullMax=" + makerAnchorMaxPullTicks + ", pullDivisor=" + makerAnchorPullDivisor + "}"
                + ", trend{qty=" + trendQty + ", interval=" + trendMinIntervalMillis + ".." + trendMaxIntervalMillis
                + ", maxPos=" + trendMaxPosition + "}"
                + ", revert{interval=" + revertRequoteIntervalMillis + ", maxPos=" + revertMaxPosition + "}"
                + ", noise{meanArrival=" + noiseMeanArrivalMillis + ", maxPos=" + noiseMaxPosition + "}"
                + ", inst{parent=" + instMinParent + ".." + instMaxParent + ", maxPos=" + instMaxPosition + "}}";
    }

    // --- parsing ---------------------------------------------------------------------------

    static Map<String, String> flags(String[] args) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--") || a.length() == 2) {
                throw new IllegalArgumentException("unexpected argument '" + a + "'; flags are --name value");
            }
            String name = a.substring(2);
            if (!FLAGS.contains(name)) {
                throw new IllegalArgumentException("unknown flag --" + name + "; flags are " + FLAGS
                        + ", every other setting is a BOTS_* environment variable");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("--" + name + " needs a value");
            }
            m.put(name, args[++i]);
        }
        return m;
    }

    private static URI venue(String text, String name) {
        URI u;
        try {
            u = new URI(text.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(name + " is not a URL: '" + text + "'");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null
                || !(path.isEmpty() || path.equals("/")) || u.getRawQuery() != null) {
            throw new IllegalArgumentException(name + " must be http(s)://host[:port] with no path, got '" + text + "'");
        }
        return URI.create(scheme + "://" + u.getRawAuthority());
    }

    /** Flag, then environment, then default; names the source in errors. */
    private record Source(Map<String, String> flags, Map<String, String> env) {

        String flag(String flag) {
            return flag == null ? null : blankToNull(flags.get(flag));
        }

        String get(String flag, String var) {
            String f = flag(flag);
            if (f != null) return f;
            return var == null ? null : blankToNull(env.get(var));
        }

        String name(String flag, String var) {
            if (flag != null && flag(flag) != null) return "--" + flag;
            return var != null ? var : "--" + flag;
        }

        long integer(String flag, String var, long min, long max, long fallback) {
            String v = get(flag, var);
            if (v == null) return fallback;
            long n;
            try {
                n = Long.parseLong(v.replace("_", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name(flag, var) + " must be a whole number, got '" + v + "'");
            }
            if (n < min || n > max) {
                throw new IllegalArgumentException(name(flag, var) + " must be from " + min + " to " + max
                        + ", got " + n);
            }
            return n;
        }

        double decimal(String flag, String var, double fallback) {
            String v = get(flag, var);
            if (v == null) return fallback;
            double d;
            try {
                d = Double.parseDouble(v);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name(flag, var) + " must be a number, got '" + v + "'");
            }
            if (!(d > 0) || Double.isInfinite(d)) {
                throw new IllegalArgumentException(name(flag, var) + " must be positive, got '" + v + "'");
            }
            return d;
        }

        private static String blankToNull(String v) {
            if (v == null) return null;
            String t = v.trim();
            return t.isEmpty() ? null : t;
        }
    }

    /** For tests and logs: the enabled bots in their fixed order. */
    List<String> enabledInOrder() {
        return Arrays.stream(ALL_BOTS.toArray(new String[0])).filter(enabled::contains).toList();
    }
}
