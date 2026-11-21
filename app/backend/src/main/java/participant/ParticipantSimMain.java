package participant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the five-bot simulation against a live exchange: locally as a second process (the "Bots"
 * IntelliJ run configuration), and in production as the {@code bots} container (P16-5, D16).
 *
 * <p>Settings come from flags, then {@code BOTS_*} environment variables, then the development
 * defaults ({@link BotSettings}). The open comes from the venue's {@code /market/ignition}, with
 * {@code BOTS_OPEN_PX} only when the venue has no Alpaca keys ({@link OpenPriceSource}); nothing is
 * sent until it is known. Each bot has its own connection, which reconnects and catches up by
 * itself ({@link ParticipantClient}), and its own {@link RiskGate}.
 *
 * <p>An optional read-only observer drives the {@link PriceTracker} CSV ({@code BOTS_CSV=none}
 * turns it off). Every {@code BOTS_STATUS_INTERVAL_MS} (default a minute; 0 = off) one INFO line per
 * bot gives its position, open orders, refusals and commands sent.
 *
 * <p>It runs until stopped unless {@code --duration} is given. On SIGTERM (Ctrl+C, {@code docker
 * stop}) every bot cancels its resting orders together, waits up to 3 s for the venue to confirm,
 * and the process exits within 5 s. Exit 2: a bad setting, or no open (404 with
 * {@code BOTS_OPEN_PX} unset).
 */
public final class ParticipantSimMain {

    private static final Logger log = LoggerFactory.getLogger(ParticipantSimMain.class);

    static final long STOP_ACK_WAIT_MILLIS = 3_000;
    static final long STOP_CLOSE_MILLIS = 500;

    private ParticipantSimMain() { }

    /** One bot: its strategy and the band and position limit its gate enforces. */
    record Bot(Strategy strategy, RiskLimits risk) { }

    public static void main(String[] args) throws Exception {
        BotSettings cfg;
        try {
            cfg = BotSettings.from(args, System.getenv(), System::nanoTime);
        } catch (IllegalArgumentException e) {
            log.error("Bots not started: {}", e.getMessage());
            System.exit(2);
            return;
        }
        log.info("Bots starting: {}", cfg);

        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        long openUnits;
        try {
            openUnits = OpenPriceSource.resolve(OpenPriceSource.http(http, cfg.ignitionUri()), cfg.openPxUnits(),
                    mapper, ms -> TimeUnit.MILLISECONDS.sleep(ms), System::currentTimeMillis);
        } catch (OpenPriceSource.NoOpenPriceException e) {
            log.error("Bots not started: {}", e.getMessage());
            System.exit(2);
            return;
        }
        log.info("Anchoring bots to open = {} units ($0.0001), seed {}, speed {}", openUnits, cfg.seed(), cfg.speed());

        long now = System.currentTimeMillis();
        List<Bot> bots = bots(cfg, openUnits, now);

        PriceTracker tracker = null;
        ParticipantClient observer = null;
        if (cfg.csv() != null) {
            Path csv = Path.of(cfg.csv().isEmpty()
                    ? "run-output/price_path_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv"
                    : cfg.csv());
            tracker = new PriceTracker(csv);
            observer = new ParticipantClient(cfg.wsUri(), cfg.healthUri(), cfg.symbol(), new Observer(), null,
                    3_600_000, tracker, mapper);
        }
        List<ParticipantClient> clients = new ArrayList<>();
        for (Bot b : bots) {
            RiskGate gate = new RiskGate(b.strategy().owner(), b.risk(), cfg.maxOrderQty(), cfg.maxOpenOrders());
            clients.add(new ParticipantClient(cfg.wsUri(), cfg.healthUri(), cfg.symbol(), b.strategy(), gate,
                    cfg.tickMillis(), null, mapper, cfg.rejectBackoffMaxMillis()));
        }

        // The maker re-reads the open at each live session open, on its own thread.
        ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bots-background");
            t.setDaemon(true);
            return t;
        });
        AnchorRefresh refresh = new AnchorRefresh(OpenPriceSource.http(http, cfg.ignitionUri()), mapper, background);
        for (ParticipantClient c : clients) {
            if (c.strategy() instanceof MarketMakerStrategy) {
                c.setSessionOpenListener(client -> refresh.request(client.name(), client::post, client.strategy()));
            }
        }

        List<ParticipantClient> all = new ArrayList<>(clients);
        if (observer != null) {
            all.add(observer);
        }
        AtomicBoolean stopped = new AtomicBoolean();
        PriceTracker trackerRef = tracker;
        Runnable stop = () -> {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            log.info("Stopping bots and cancelling their resting orders...");
            ParticipantClient.stopAll(all, STOP_ACK_WAIT_MILLIS, STOP_CLOSE_MILLIS);
            background.shutdownNow();
            if (trackerRef != null) {
                trackerRef.close();
            }
            log.info("Done.");
        };
        Runtime.getRuntime().addShutdownHook(new Thread(stop, "bots-shutdown"));

        if (observer != null) {
            observer.start(http);
        }
        for (ParticipantClient c : clients) {
            c.start(http);
        }
        log.info("Bots {} running against {}", cfg.enabled(), cfg.wsUri());

        if (cfg.statusIntervalMillis() > 0) {
            background.scheduleAtFixedRate(() -> {
                for (ParticipantClient c : clients) {
                    log.info("STATUS {}", c.status());
                }
            }, cfg.statusIntervalMillis(), cfg.statusIntervalMillis(), TimeUnit.MILLISECONDS);
        }

        CountDownLatch forever = new CountDownLatch(1);
        if (cfg.durationSeconds() > 0) {
            forever.await(cfg.durationSeconds(), TimeUnit.SECONDS);
            stop.run();
        } else {
            forever.await();
        }
    }

    /** Build the enabled bots, in a fixed order, each with its own seeded random stream. */
    static List<Bot> bots(BotSettings cfg, long openUnits, long nowMillis) {
        SplittableRandom root = new SplittableRandom(cfg.seed());
        double speed = cfg.speed();
        int band = cfg.bandPercent();
        List<Bot> out = new ArrayList<>();
        if (cfg.enabled().contains("maker")) {
            long[] qtys = MarketMakerStrategy.Config.levelQtys(cfg.makerLevels());
            RiskLimits risk = new RiskLimits(openUnits, band, cfg.makerMaxPositionResolved());
            MarketMakerStrategy.Config d = MarketMakerStrategy.Config.defaults(openUnits);
            MarketMakerStrategy.Config c = new MarketMakerStrategy.Config(openUnits, cfg.makerHalfSpreadTicks(),
                    qtys, d.volMultiplier(), cfg.makerRequoteMoveTicks(), scaled(cfg.makerRequoteIntervalMillis(), speed),
                    cfg.makerSkewClip(), cfg.makerMaxSkewTicks(), risk,
                    cfg.makerMinRequoteGapMillis() == 0 ? 0 : scaled(cfg.makerMinRequoteGapMillis(), speed),
                    cfg.makerAnchorMaxPullTicks(), cfg.makerAnchorPullDivisor());
            out.add(new Bot(new MarketMakerStrategy(c, ids(Bots.Owner.MAKER, nowMillis)), risk));
        }
        SplittableRandom trendRnd = root.split();
        if (cfg.enabled().contains("trend")) {
            RiskLimits risk = new RiskLimits(openUnits, band, cfg.trendMaxPosition());
            TrendFollowerStrategy.Config d = TrendFollowerStrategy.Config.defaults(openUnits);
            TrendFollowerStrategy.Config c = new TrendFollowerStrategy.Config(openUnits, d.fastHalfLife(),
                    d.slowHalfLife(), d.signalTicks(), d.warmupTrades(), cfg.trendQty(), d.throughTicks(),
                    scaled(cfg.trendMinIntervalMillis(), speed), scaled(cfg.trendMaxIntervalMillis(), speed), risk);
            out.add(new Bot(new TrendFollowerStrategy(c, ids(Bots.Owner.TREND, nowMillis), trendRnd), risk));
        }
        if (cfg.enabled().contains("revert")) {
            RiskLimits risk = new RiskLimits(openUnits, band, cfg.revertMaxPosition());
            MeanReversionStrategy.Config d = MeanReversionStrategy.Config.defaults(openUnits);
            MeanReversionStrategy.Config c = new MeanReversionStrategy.Config(openUnits, d.fairHalfLife(),
                    d.entryTicks(), d.exitTicks(), d.qtyPerStep(), d.maxStepQty(),
                    scaled(cfg.revertRequoteIntervalMillis(), speed), risk);
            out.add(new Bot(new MeanReversionStrategy(c, ids(Bots.Owner.REVERT, nowMillis)), risk));
        }
        SplittableRandom noiseRnd = root.split();
        if (cfg.enabled().contains("noise")) {
            RiskLimits risk = new RiskLimits(openUnits, band, cfg.noiseMaxPosition());
            NoiseTraderStrategy.Config d = NoiseTraderStrategy.Config.defaults(openUnits);
            NoiseTraderStrategy.Config c = new NoiseTraderStrategy.Config(openUnits,
                    scaled(cfg.noiseMeanArrivalMillis(), speed), d.minQty(), d.maxQty(), d.qtyStep(),
                    d.passiveProbability(), d.cancelProbability(), d.maxResting(), d.maxBehindTicks(), risk);
            out.add(new Bot(new NoiseTraderStrategy(c, ids(Bots.Owner.NOISE, nowMillis), noiseRnd), risk));
        }
        SplittableRandom instRnd = root.split();
        if (cfg.enabled().contains("inst")) {
            RiskLimits risk = new RiskLimits(openUnits, band, cfg.instMaxPosition());
            InstitutionalStrategy.Config d = InstitutionalStrategy.Config.defaults(openUnits);
            InstitutionalStrategy.Config c = new InstitutionalStrategy.Config(openUnits, cfg.instMinParent(),
                    cfg.instMaxParent(), d.maxFloor(), d.repegTicks(), scaled(d.targetMillis(), speed),
                    d.catchUpFraction(), scaled(d.maxCampaignMillis(), speed), scaled(d.minPauseMillis(), speed),
                    scaled(d.maxPauseMillis(), speed), scaled(d.firstDelayMillis(), speed), risk);
            out.add(new Bot(new InstitutionalStrategy(c, ids(Bots.Owner.INST, nowMillis), instRnd), risk));
        }
        return out;
    }

    private static Bots.IdSequence ids(Bots.Owner owner, long nowMillis) {
        return new Bots.IdSequence(owner, Bots.runStart(owner, nowMillis));
    }

    static long scaled(long millis, double speed) {
        return Math.max(1L, Math.round(millis / speed));
    }

    /** Non-trading strategy for the observer connection. Never ticked into an order. */
    private static final class Observer implements Strategy {
        @Override public String name() { return "OBS"; }
        @Override public Bots.Owner owner() { return Bots.Owner.OTHER; }
        @Override public List<Action> onTick(MarketView view, long nowMillis) { return List.of(); }
    }
}
