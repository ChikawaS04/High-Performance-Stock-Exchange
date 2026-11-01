package integration;

import config.ExchangeConfig;
import engine.CommandHandler;
import engine.MatchingEngine;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import gateway.OrderGateway;
import journal.Journal;
import journal.JournaledPipeline;
import recovery.SnapshotSink;
import util.FailStop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

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
 */
public final class JournaledVenue implements AutoCloseable {

    /** 64 KiB terms and segments, as in the journal tests. */
    private static final String SMALL = Integer.toString(64 * 1024);

    private final MatchingEngine engine = new MatchingEngine();
    private final List<Throwable> fatals = Collections.synchronizedList(new ArrayList<>());
    private final JournaledPipeline pipeline;
    private boolean closed;

    private JournaledVenue(ExchangeConfig config, CommandHandler tap, SnapshotSink snapshotSink) {
        FailStop capture = (component, cause) -> fatals.add(new AssertionError(component + " failed", cause));
        this.pipeline = new JournaledPipeline(config, engine, capture, tap, snapshotSink);
    }

    /** A venue over {@code root} (a {@code @TempDir}). */
    public static JournaledVenue create(Path root) {
        return new JournaledVenue(config(root), null, null);
    }

    /** A venue on the given configuration, with an optional tap and periodic-snapshot sink. */
    public static JournaledVenue create(ExchangeConfig config, CommandHandler tap, SnapshotSink snapshotSink) {
        return new JournaledVenue(config, tap, snapshotSink);
    }

    /** As {@link #create}, with a tap that sees every decoded command before the engine. */
    public static JournaledVenue create(Path root, CommandHandler tap) {
        return new JournaledVenue(config(root), tap, null);
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
