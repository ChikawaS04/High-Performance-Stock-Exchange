package participant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the five-bot simulation against a live exchange. Start the exchange ({@code Main}) and the
 * frontend first, then run this as a second process (the "Bots" IntelliJ run configuration).
 *
 * <p>It resolves the market-open anchor (Alpaca, or {@code --openPx} dollars), connects a
 * read-only observer (which drives the {@link PriceTracker} CSV), then the bots. It runs until
 * stopped (Ctrl+C or the IDE's stop button) unless {@code --duration} is given; on the way out
 * every bot cancels what it has resting, so the book is left with only other participants'
 * orders.
 *
 * <p>Flags (all optional): {@code --port 8080 --symbol ASML --openPx 149.80 --duration 600
 * --seed 42 --speed 1.0 --bots maker,trend,revert,noise,inst --tickMs 250
 * --csv run-output/price_path.csv --envLocal ../frontend/.env.local}. {@code --speed 2} halves every
 * interval in every strategy; {@code --seed} makes a run's random choices repeatable.
 */
public final class ParticipantSimMain {

    private static final Logger log = LoggerFactory.getLogger(ParticipantSimMain.class);
    private static final String DEFAULT_SYMBOL = "ASML";
    private static final List<String> ALL_BOTS = List.of("maker", "trend", "revert", "noise", "inst");

    private ParticipantSimMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);

        int port = Integer.parseInt(opt.getOrDefault("port", "8080"));
        String symbol = opt.getOrDefault("symbol", DEFAULT_SYMBOL);
        long tickMs = Long.parseLong(opt.getOrDefault("tickMs", "250"));
        long seed = Long.parseLong(opt.getOrDefault("seed", Long.toString(System.nanoTime())));
        double speed = Double.parseDouble(opt.getOrDefault("speed", "1.0"));
        if (!(speed > 0)) {
            throw new IllegalArgumentException("--speed must be positive");
        }
        Set<String> enabled = new LinkedHashSet<>(Arrays.asList(
                opt.getOrDefault("bots", String.join(",", ALL_BOTS)).toLowerCase().split(",")));
        URI uri = URI.create("ws://localhost:" + port + "/ws");

        long openUnits = resolveOpenUnits(opt, symbol);
        log.info("Anchoring bots to open = {} units ($0.0001), seed {}, speed {}", openUnits, seed, speed);

        Path csv = Path.of(opt.getOrDefault("csv",
                "run-output/price_path_" + LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv"));

        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        long now = System.currentTimeMillis();

        List<Strategy> strategies = strategies(enabled, openUnits, seed, speed, now);
        PriceTracker tracker = new PriceTracker(csv);
        ParticipantClient observer = new ParticipantClient(uri, symbol, new Observer(), 3_600_000, tracker, mapper);
        List<ParticipantClient> clients = new ArrayList<>();
        for (Strategy s : strategies) {
            clients.add(new ParticipantClient(uri, symbol, s, tickMs, null, mapper));
        }

        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch cleanedUp = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopped.countDown();
            try {
                cleanedUp.await(10, TimeUnit.SECONDS);   // let main cancel the bots' orders first
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sim-shutdown"));

        // Observer first (catch the snapshot-on-connect), then the maker (seeds the book), then the rest.
        observer.start(http);
        for (ParticipantClient c : clients) {
            c.start(http);
        }
        log.info("Bots {} running against {}", enabled, uri);

        if (opt.containsKey("duration")) {
            stopped.await(Long.parseLong(opt.get("duration")), TimeUnit.SECONDS);
        } else {
            stopped.await();
        }

        log.info("Stopping bots and cancelling their resting orders...");
        for (ParticipantClient c : clients) {
            c.stop();
        }
        observer.stop();
        tracker.close();
        cleanedUp.countDown();
        log.info("Done.");
    }

    /** Build the enabled strategies, in a fixed order, each with its own seeded random stream. */
    static List<Strategy> strategies(Set<String> enabled, long openUnits, long seed, double speed, long nowMillis) {
        for (String name : enabled) {
            if (!ALL_BOTS.contains(name)) {
                throw new IllegalArgumentException("unknown bot '" + name + "'; choose from " + ALL_BOTS);
            }
        }
        SplittableRandom root = new SplittableRandom(seed);
        List<Strategy> out = new ArrayList<>();
        if (enabled.contains("maker")) {
            MarketMakerStrategy.Config d = MarketMakerStrategy.Config.defaults(openUnits);
            MarketMakerStrategy.Config c = new MarketMakerStrategy.Config(d.openUnits(), d.baseHalfSpreadTicks(),
                    d.levelQtys(), d.volMultiplier(), d.requoteMoveTicks(), scaled(d.requoteIntervalMillis(), speed),
                    d.skewClipQty(), d.maxSkewTicks(), d.risk());
            out.add(new MarketMakerStrategy(c, ids(Bots.Owner.MAKER, nowMillis)));
        }
        SplittableRandom trendRnd = root.split();
        if (enabled.contains("trend")) {
            TrendFollowerStrategy.Config d = TrendFollowerStrategy.Config.defaults(openUnits);
            TrendFollowerStrategy.Config c = new TrendFollowerStrategy.Config(d.openUnits(), d.fastHalfLife(),
                    d.slowHalfLife(), d.signalTicks(), d.warmupTrades(), d.qty(), d.throughTicks(),
                    scaled(d.minIntervalMillis(), speed), scaled(d.maxIntervalMillis(), speed), d.risk());
            out.add(new TrendFollowerStrategy(c, ids(Bots.Owner.TREND, nowMillis), trendRnd));
        }
        if (enabled.contains("revert")) {
            MeanReversionStrategy.Config d = MeanReversionStrategy.Config.defaults(openUnits);
            MeanReversionStrategy.Config c = new MeanReversionStrategy.Config(d.openUnits(), d.fairHalfLife(),
                    d.entryTicks(), d.exitTicks(), d.qtyPerStep(), d.maxStepQty(),
                    scaled(d.requoteIntervalMillis(), speed), d.risk());
            out.add(new MeanReversionStrategy(c, ids(Bots.Owner.REVERT, nowMillis)));
        }
        SplittableRandom noiseRnd = root.split();
        if (enabled.contains("noise")) {
            NoiseTraderStrategy.Config d = NoiseTraderStrategy.Config.defaults(openUnits);
            NoiseTraderStrategy.Config c = new NoiseTraderStrategy.Config(d.openUnits(),
                    scaled(d.meanArrivalMillis(), speed), d.minQty(), d.maxQty(), d.qtyStep(),
                    d.passiveProbability(), d.cancelProbability(), d.maxResting(), d.maxBehindTicks(), d.risk());
            out.add(new NoiseTraderStrategy(c, ids(Bots.Owner.NOISE, nowMillis), noiseRnd));
        }
        SplittableRandom instRnd = root.split();
        if (enabled.contains("inst")) {
            InstitutionalStrategy.Config d = InstitutionalStrategy.Config.defaults(openUnits);
            InstitutionalStrategy.Config c = new InstitutionalStrategy.Config(d.openUnits(), d.minParentQty(),
                    d.maxParentQty(), d.maxFloor(), d.repegTicks(), scaled(d.targetMillis(), speed),
                    d.catchUpFraction(), scaled(d.maxCampaignMillis(), speed), scaled(d.minPauseMillis(), speed),
                    scaled(d.maxPauseMillis(), speed), scaled(d.firstDelayMillis(), speed), d.risk());
            out.add(new InstitutionalStrategy(c, ids(Bots.Owner.INST, nowMillis), instRnd));
        }
        return out;
    }

    private static Bots.IdSequence ids(Bots.Owner owner, long nowMillis) {
        return new Bots.IdSequence(owner, Bots.runStart(owner, nowMillis));
    }

    static long scaled(long millis, double speed) {
        return Math.max(1L, Math.round(millis / speed));
    }

    /** Explicit --openPx dollars wins; otherwise fetch from Alpaca. Fails loudly if neither works. */
    private static long resolveOpenUnits(Map<String, String> opt, String symbol) throws Exception {
        if (opt.containsKey("openPx")) {
            OptionalLong u = OpenPriceSource.openUnits(Double.parseDouble(opt.get("openPx")));
            if (u.isEmpty()) {
                throw new IllegalArgumentException("--openPx must be a positive dollar amount");
            }
            return u.getAsLong();
        }
        Path envLocal = Path.of(opt.getOrDefault("envLocal", defaultEnvLocal()));
        Optional<OpenPriceSource.Credentials> creds = OpenPriceSource.loadCredentials(envLocal);
        if (creds.isEmpty()) {
            throw new IllegalStateException(
                    "No Alpaca credentials (ALPACA_KEY_ID / ALPACA_SECRET_KEY in the environment or "
                    + envLocal + "). Pass --openPx <dollars> to run without a live open, "
                    + "e.g. --openPx 149.80");
        }
        return new OpenPriceSource().fetchOpenUnits(symbol, creds.get());
    }

    /** Best guess at the frontend's .env.local relative to however this is launched. */
    private static String defaultEnvLocal() {
        String cwd = System.getProperty("user.dir", ".");
        List<String> candidates = List.of(
                cwd + "/../frontend/.env.local",      // run from app/backend
                cwd + "/app/frontend/.env.local",     // run from repo root
                cwd + "/frontend/.env.local");         // run from app
        for (String c : candidates) {
            if (Files.isReadable(Path.of(c))) {
                return c;
            }
        }
        return candidates.get(0);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                m.put(args[i].substring(2), args[i + 1]);
                i++;
            }
        }
        return m;
    }

    /** Non-trading strategy for the observer connection. Never ticked into an order. */
    private static final class Observer implements Strategy {
        @Override public String name() { return "OBS"; }
        @Override public Bots.Owner owner() { return Bots.Owner.OTHER; }
        @Override public List<Action> onTick(MarketView view, long nowMillis) { return List.of(); }
    }
}
