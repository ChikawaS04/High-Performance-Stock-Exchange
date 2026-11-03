package integration;

import config.ExchangeConfig;
import engine.CommandHandler;
import engine.MatchingEngine;
import event.CapturingExecutionHandler;
import event.ExecutionEventType;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import gateway.OrderGateway;
import journal.Journal;
import journal.JournaledPipeline;
import recovery.SnapshotSink;
import session.SessionController;
import util.FailStop;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared test harness for every test that assembles the venue: the production
 * {@link JournaledPipeline} (journal, sequencer, recorded inbound stream, gated engine agent,
 * outbound and snapshot rings) over a temporary data and driver directory per test, with a
 * capturing {@link FailStop} in place of the halt.
 *
 * Usage mirrors Main: create, register subscribers on {@link #outbound()} and
 * {@link #snapshots()}, build a gateway, {@link #start()}, drive input, then {@link #close()},
 * which runs the real graceful shutdown and fails the test if a fatal was reported or a drain
 * did not complete.
 *
 * Restart (P15-5): a new venue on the same {@code root} recovers from the previous run's
 * snapshot and journal, exactly as Main does.
 *
 * Sessions (P15-8): a NEW is matched only while a session is open. {@link #startAndOpen} starts the
 * venue, then runs the production {@link SessionController#startup()} on the calling thread through
 * the test's own gateway, so the test thread stays the gateway's only producer and the open's
 * receipt stamp comes from the test's clock. On a fresh venue the open is inbound seq 1 and outbound
 * seq 1 ({@link #OPEN_SEQ}); on a restart the recovered session resumes and nothing is issued.
 * The business date is {@link #BUSINESS_DATE}, from a fixed clock. Tests of the closed state use
 * {@link #start()}.
 */
public final class JournaledVenue implements AutoCloseable {

    /** 64 KiB terms and segments, as in the journal tests. */
    private static final String SMALL = Integer.toString(64 * 1024);

    /** The business date {@link #startAndOpen} opens: the date of {@link #CLOCK} in the default time zone. */
    public static final int BUSINESS_DATE = 20261102;

    /** 17:00 UTC on {@link #BUSINESS_DATE}: noon, the same calendar date, in New York (the default session zone). */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T17:00:00Z"), ZoneOffset.UTC);

    /** On a fresh venue the session open takes inbound seq 1 and its SESSION_OPENED outbound seq 1. */
    public static final long OPEN_SEQ = 1L;

    private final MatchingEngine engine = new MatchingEngine();
    private final ExchangeConfig config;
    private final List<Throwable> fatals = Collections.synchronizedList(new ArrayList<>());
    private final JournaledPipeline pipeline;
    private boolean closed;

    private JournaledVenue(ExchangeConfig config, JournaledPipeline.Seams seams) {
        FailStop capture = (component, cause) -> fatals.add(new AssertionError(component + " failed", cause));
        this.config = config;
        this.pipeline = new JournaledPipeline(config, engine, capture, seams);
    }

    /** A venue over {@code root} (a {@code @TempDir}). */
    public static JournaledVenue create(Path root) {
        return new JournaledVenue(config(root), JournaledPipeline.Seams.NONE);
    }

    /** A venue on the given configuration, with an optional tap and periodic-snapshot sink. */
    public static JournaledVenue create(ExchangeConfig config, CommandHandler tap, SnapshotSink snapshotSink) {
        return new JournaledVenue(config, new JournaledPipeline.Seams(tap, snapshotSink, null));
    }

    /** A venue on the given configuration with the given test seams (P15-6: the recorder seam). */
    public static JournaledVenue create(ExchangeConfig config, JournaledPipeline.Seams seams) {
        return new JournaledVenue(config, seams);
    }

    /** As {@link #create}, with a tap that sees every decoded command before the engine. */
    public static JournaledVenue create(Path root, CommandHandler tap) {
        return new JournaledVenue(config(root), new JournaledPipeline.Seams(tap, null, null));
    }

    /** Data and driver directories under {@code root}, small journal buffers. */
    public static ExchangeConfig config(Path root) {
        return ExchangeConfig.from(env(root));
    }

    /** As {@link #config(Path)}, with a periodic snapshot every {@code snapshotInterval} applied commands. */
    public static ExchangeConfig config(Path root, long snapshotInterval) {
        Map<String, String> env = env(root);
        env.put(ExchangeConfig.SNAPSHOT_INTERVAL, Long.toString(snapshotInterval));
        return ExchangeConfig.from(env);
    }

    private static Map<String, String> env(Path root) {
        Map<String, String> env = new HashMap<>();
        env.put(ExchangeConfig.EXCHANGE_DATA_DIR, root.resolve("data").toString());
        env.put(ExchangeConfig.AERON_DIR, root.resolve("aeron").toString());
        env.put(ExchangeConfig.JOURNAL_TERM_LENGTH, SMALL);
        env.put(ExchangeConfig.JOURNAL_SEGMENT_LENGTH, SMALL);
        return env;
    }

    public MatchingEngine engine() { return engine; }

    public OutboundPipeline outbound() { return pipeline.outbound(); }

    public SnapshotPipeline snapshots() { return pipeline.snapshots(); }

    public JournaledPipeline pipeline() { return pipeline; }

    public Journal journal() { return pipeline.journal(); }

    /** A gateway on the inbound ring with the default {@code System::nanoTime} receipt stamp. */
    public OrderGateway gateway() {
        return new OrderGateway(pipeline.inboundRing());
    }

    /** A gateway on the inbound ring with the given receipt clock. */
    public OrderGateway gateway(LongSupplier clock) {
        return new OrderGateway(pipeline.inboundRing(), clock);
    }

    public void start() {
        pipeline.start();
    }

    /**
     * {@link #start()}, then the session controller's startup with a direct executor: the calling
     * thread must be {@code gateway}'s only caller. Opens a session on {@link #BUSINESS_DATE} unless
     * the recovered one is already open.
     */
    public void startAndOpen(OrderGateway gateway) {
        start();
        sessionController(gateway).startup();
    }

    /** The production controller on this venue, publishing through {@code gateway} on the calling thread. */
    public SessionController sessionController(OrderGateway gateway) {
        return SessionController.fromConfig(config, CLOCK, gateway, Runnable::run, pipeline);
    }

    public ExchangeConfig config() { return config; }

    /**
     * Waits for the fresh venue's SESSION_OPENED (outbound seq {@link #OPEN_SEQ}) on {@code captured},
     * checks it, then clears {@code captured}, so the test asserts its own events from index 0.
     */
    public static void awaitOpenAndClear(CapturingExecutionHandler captured) {
        List<CapturingExecutionHandler.Observed> seen = captured.awaitAtLeast(1, 5_000);
        assertEquals(1, seen.size(), "exactly the session open before any test command");
        assertEquals(ExecutionEventType.SESSION_OPENED, seen.get(0).eventType());
        assertEquals(OPEN_SEQ, seen.get(0).seq());
        assertEquals(BUSINESS_DATE, seen.get(0).businessDate());
        captured.clear();
    }

    /** Fatal errors reported so far. */
    public List<Throwable> fatals() {
        synchronized (fatals) {
            return new ArrayList<>(fatals);
        }
    }

    /** Graceful shutdown; fails if anything fatal was reported or a drain timed out. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        boolean clean = pipeline.shutdown();
        List<Throwable> seen = fatals();
        if (!seen.isEmpty()) {
            AssertionError failure = new AssertionError("venue reported " + seen.size() + " fatal error(s)", seen.get(0));
            for (int i = 1; i < seen.size(); i++) failure.addSuppressed(seen.get(i));
            throw failure;
        }
        assertTrue(clean, "graceful shutdown did not drain every stage");
    }

    /** Shutdown for a test that expects a fatal: no assertion on cleanliness. */
    public void closeAfterFatal() {
        if (closed) return;
        closed = true;
        pipeline.shutdown();
        if (fatals().isEmpty()) fail("expected a fatal error, none was reported");
    }
}
