package benchmark;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslator;
import config.ExchangeConfig;
import event.BookSnapshotEvent;
import event.ExecutionEvent;
import event.OrderEvent;
import event.OrderEventType;
import model.OrdType;
import model.Side;
import model.TimeInForce;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Shared pieces of the P15-13 benchmarks: scratch directories, the production-default journal
 * configuration, the steady-state rest and cross workload, an outbound outcome counter and bounded
 * waits. Test scope only; nothing here is a test (the name does not match Surefire's patterns).
 *
 * Directories. Every journal a benchmark opens lives under a fresh temporary root below
 * {@code -Dbench.dir} (default {@code java.io.tmpdir}), so each fork starts from an empty archive
 * and never touches {@code app/backend/data}. The root is deleted when the fork's trial ends.
 *
 * Configuration. Only the two directories and the engine idle strategy are set; every other value
 * is the production default (256 KiB terms, 16 MiB segments, snapshot interval 10,000).
 */
final class BenchSupport {

    /** The Agrona flag every JVM touching Agrona or Aeron needs (P15-1). */
    static final String ADD_EXPORTS = "--add-exports";
    static final String MISC = "java.base/jdk.internal.misc=ALL-UNNAMED";

    /** Bound on every wait, so a stall fails the run loudly rather than hanging it. */
    static final long TIMEOUT_MS = 30_000L;

    /** Price and quantity of the Phase 14 throughput workload. */
    static final long PRICE_UNITS = 1_500_000L;
    static final long QTY = 10L;

    private BenchSupport() { }

    static Path newRoot(String name) {
        try {
            Path base = Path.of(System.getProperty("bench.dir", System.getProperty("java.io.tmpdir")));
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "p15-13-" + name + "-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not delete benchmark root " + root, e);
        }
    }

    /** Production defaults, with the directories under {@code root} and the given engine idle strategy. */
    static ExchangeConfig productionConfig(Path root, String engineIdle) {
        return productionConfig(root, engineIdle, ExchangeConfig.ArchiveThreading.SHARED.value());
    }

    /** As {@link #productionConfig(Path, String)}, with {@code JOURNAL_ARCHIVE_THREADING} set. */
    static ExchangeConfig productionConfig(Path root, String engineIdle, String archiveThreading) {
        Map<String, String> env = new HashMap<>();
        env.put(ExchangeConfig.EXCHANGE_DATA_DIR, root.resolve("data").toString());
        env.put(ExchangeConfig.AERON_DIR, root.resolve("aeron").toString());
        env.put(ExchangeConfig.ENGINE_IDLE_STRATEGY, engineIdle);
        env.put(ExchangeConfig.JOURNAL_ARCHIVE_THREADING, archiveThreading);
        return ExchangeConfig.from(env);
    }

    /** Segment files and their total bytes under the archive directory. */
    static String archiveStats(Path archiveDir) {
        if (!Files.exists(archiveDir)) return "no archive";
        long files = 0;
        long bytes = 0;
        try (Stream<Path> paths = Files.list(archiveDir)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (p.getFileName().toString().endsWith(".rec")) {
                    files++;
                    bytes += Files.size(p);
                }
            }
        } catch (IOException e) {
            return "archive unreadable: " + e;
        }
        return files + " segment files, " + (bytes >> 20) + " MiB";
    }

    /** A new LIMIT GTC order, with every other field at the decoder's reset value. */
    static OrderEvent newOrder(long orderId, Side side, long timestamp) {
        OrderEvent e = new OrderEvent();
        writeNewOrder(e, orderId, side, timestamp);
        return e;
    }

    static void writeNewOrder(OrderEvent e, long orderId, Side side, long timestamp) {
        e.eventType = OrderEventType.NEW_ORDER;
        e.orderId = orderId;
        e.side = side;
        e.ordType = OrdType.LIMIT;
        e.tif = TimeInForce.GTC;
        e.price = PRICE_UNITS;
        e.quantity = QTY;
        e.maxFloor = 0L;
        e.timestamp = timestamp;
        e.originalOrderId = -1L;
        e.clearSessionFields();
    }

    /** A cancel of {@code originalOrderId}, with every other field at the decoder's reset value. */
    static OrderEvent cancel(long orderId, long originalOrderId, long timestamp) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.CANCEL_ORDER;
        e.orderId = orderId;
        e.side = null;
        e.ordType = null;
        e.tif = null;
        e.price = -1L;
        e.quantity = -1L;
        e.maxFloor = 0L;
        e.timestamp = timestamp;
        e.originalOrderId = originalOrderId;
        e.clearSessionFields();
        return e;
    }

    /**
     * The steady-state workload (Phase 6 and Phase 14): a BUY that rests in the empty book, then a
     * SELL at the same price and quantity that crosses it exactly, so the book holds 0 or 1 orders.
     * Ids come from a local counter; timestamps rise by one from {@code firstTimestamp}, so the
     * sequencer's D1 clamp never fires. Writes every field of the slot. Single producer thread.
     */
    static final class RestCross implements EventTranslator<OrderEvent> {
        private long nextOrderId;
        private long timestamp;
        private boolean restNext = true;
        private long published;

        RestCross(long firstOrderId, long firstTimestamp) {
            this.nextOrderId = firstOrderId;
            this.timestamp = firstTimestamp;
        }

        @Override
        public void translateTo(OrderEvent slot, long sequence) {
            writeNewOrder(slot, nextOrderId++, restNext ? Side.BUY : Side.SELL, timestamp++);
            restNext = !restNext;
            published++;
        }

        long published() {
            return published;
        }
    }

    /**
     * Counts outcomes on the outbound ring. Order outcomes and session events are counted apart, so
     * the benchmark can wait for exactly one terminal event per command it published.
     */
    static final class OutcomeCounter implements EventHandler<ExecutionEvent> {
        private long accepted;
        private long filled;
        private long rejected;
        private long other;
        private final AtomicLong orderEvents = new AtomicLong();
        private final AtomicLong sessionEvents = new AtomicLong();

        @Override
        public void onEvent(ExecutionEvent event, long sequence, boolean endOfBatch) {
            switch (event.eventType) {
                case SESSION_OPENED:
                case SESSION_CLOSED:
                    sessionEvents.lazySet(sessionEvents.get() + 1);
                    return;
                case ORDER_ACCEPTED:
                    accepted++;
                    break;
                case ORDER_FILLED:
                    filled++;
                    break;
                case ORDER_REJECTED:
                    rejected++;
                    break;
                default:
                    other++;
                    break;
            }
            orderEvents.lazySet(orderEvents.get() + 1);
        }

        long orderEvents() {
            return orderEvents.get();
        }

        long sessionEvents() {
            return sessionEvents.get();
        }

        /** Read only after {@link #orderEvents()} has been seen at its final value. */
        String summary() {
            return "accepted=" + accepted + " filled=" + filled + " rejected=" + rejected + " other=" + other;
        }

        /** Every command accepted: half rested, half filled, nothing rejected or unexpected. */
        void checkAllAccepted(long published) {
            long restExpected = (published + 1) / 2;
            long crossExpected = published / 2;
            if (rejected != 0 || other != 0 || accepted != restExpected || filled != crossExpected) {
                throw new IllegalStateException("workload not fully accepted: published=" + published + " "
                        + summary());
            }
        }
    }

    /** Drains the snapshot ring, as the market data consumers do in production. */
    static final class NoOpSnapshots implements EventHandler<BookSnapshotEvent> {
        @Override
        public void onEvent(BookSnapshotEvent event, long sequence, boolean endOfBatch) {
            // intentionally empty
        }
    }

    /** Bounded wait outside any measured region. */
    static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("timed out after " + TIMEOUT_MS + " ms waiting for " + what);
            }
            LockSupport.parkNanos(100_000L);
        }
    }

    static void report(String line) {
        System.out.println("[bench] " + line);
    }
}
