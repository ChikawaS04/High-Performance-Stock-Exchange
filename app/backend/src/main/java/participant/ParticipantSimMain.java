package participant;

import com.fasterxml.jackson.databind.ObjectMapper;
import model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the two-bot simulation against a live exchange. Start the exchange ({@code Main}) and the
 * frontend first, then run this as a second process.
 *
 * <p>It resolves the market-open anchor (Alpaca, or {@code --openPx} dollars), hands it to the
 * maker, connects a read-only observer (which drives the {@link PriceTracker} CSV), then the
 * maker (which seeds the book at the open) and the taker (which walks price up then down). After
 * the configured duration it shuts everything down and writes the run summary.
 *
 * <p>Flags (all optional): {@code --port 8080 --duration 120 --symbol ASML --openPx 149.80
 * --csv run-output/price_path.csv --envLocal ../frontend/.env.local --tickMs 500}. Nothing here
 * touches the engine, gateway or frontend: the bots are ordinary WebSocket clients.
 */
public final class ParticipantSimMain {

    private static final Logger log = LoggerFactory.getLogger(ParticipantSimMain.class);
    private static final String DEFAULT_SYMBOL = "ASML";

    private ParticipantSimMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);

        int port = Integer.parseInt(opt.getOrDefault("port", "8080"));
        long durationSec = Long.parseLong(opt.getOrDefault("duration", "120"));
        String symbol = opt.getOrDefault("symbol", DEFAULT_SYMBOL);
        long tickMs = Long.parseLong(opt.getOrDefault("tickMs", "500"));
        URI uri = URI.create("ws://localhost:" + port + "/ws");

        long openUnits = resolveOpenUnits(opt, symbol);
        log.info("Anchoring maker to open = {} units ($0.0001)", openUnits);

        Path csv = Path.of(opt.getOrDefault("csv",
                "run-output/price_path_" + LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv"));

        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();

        // Strategy tunables (spike defaults; edit here).
        MarketMakerStrategy maker = new MarketMakerStrategy(
                new MarketMakerStrategy.Config(openUnits, 5, 50, 2, 3_000, 1, 10),
                new Bots.IdSequence(Bots.MAKER_BASE));
        MomentumTakerStrategy taker = new MomentumTakerStrategy(
                new MomentumTakerStrategy.Config(1_500, 20, 3, 15_000, Side.BUY),
                new Bots.IdSequence(Bots.TAKER_BASE));

        PriceTracker tracker = new PriceTracker(csv);

        ParticipantClient observer = new ParticipantClient(uri, symbol, new NoOp(), 3_600_000, tracker, mapper);
        ParticipantClient makerClient = new ParticipantClient(uri, symbol, maker, tickMs, null, mapper);
        ParticipantClient takerClient = new ParticipantClient(uri, symbol, taker, tickMs, null, mapper);

        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(done::countDown, "sim-shutdown"));

        // Observer first (catch the snapshot-on-connect), then maker (seed), then taker (walk).
        observer.start(http);
        makerClient.start(http);
        takerClient.start(http);
        log.info("Simulation running for {}s against {}", durationSec, uri);

        done.await(durationSec, TimeUnit.SECONDS);

        log.info("Stopping simulation...");
        takerClient.stop();
        makerClient.stop();
        observer.stop();
        tracker.close();
        log.info("Done.");
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
            if (java.nio.file.Files.isReadable(Path.of(c))) {
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

    /** Non-trading strategy for the observer connection. */
    private static final class NoOp implements Strategy {
        @Override public String name() { return "OBS"; }
        @Override public long rangeBase() { return 0L; }
        @Override public List<Action> onTick(MarketView view, long nowMillis) { return List.of(); }
    }
}
