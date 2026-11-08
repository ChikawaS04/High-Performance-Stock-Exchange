package benchmark;

import com.lmax.disruptor.EventHandler;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import engine.Sessions;
import event.ExecutionEvent;
import event.ExecutionEventType;
import event.InboundPipeline;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import gateway.FixConstants;
import gateway.OrderGateway;
import integration.JournaledVenue;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.FragmentHandler;
import journal.Journal;
import journal.JournaledPipeline;
import model.Side;
import net.JsonToFix;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P15-13 questions A4 and A6: end-to-end latency, gateway receipt to execution event publication
 * (SRS §6.3, p50, p99, p99.9), with the journal in the path. The span is the P15-2 span of
 * {@link EndToEndLatencyBenchmark}: {@code System.nanoTime()} when the frame is handed to
 * {@link OrderGateway#onFrame} (the FIX parse is inside), to {@code System.nanoTime()} when the
 * terminal {@link ExecutionEvent} reaches an outbound subscriber. EXEC frames do not wait for the
 * outbound recorder (P15-6), so the subscriber here runs beside the recorder, as the publisher does.
 *
 * <h2>Configurations (system properties)</h2>
 * <ul>
 *   <li>{@code e2e.pipeline}: {@code journaled} (default; the production {@link JournaledPipeline} on
 *       production journal defaults) or {@code unjournaled} (the {@link EndToEndLatencyBenchmark}
 *       wiring).</li>
 *   <li>{@code e2e.mode}: {@code closed} (default) or {@code open}.</li>
 *   <li>{@code e2e.idle}: the engine agent's idle strategy, default {@code busy-spin} (D9).</li>
 *   <li>{@code e2e.archive}: {@code JOURNAL_ARCHIVE_THREADING}, {@code shared} (default) or
 *       {@code dedicated}; journaled only.</li>
 *   <li>{@code e2e.rate}: open loop orders per second, default 20,000.</li>
 *   <li>{@code e2e.warmup}, {@code e2e.measure}: default 200,000 rounds and 1,000,000 orders closed;
 *       120,000 and 600,000 orders open.</li>
 *   <li>{@code e2e.replayLoad}: journaled only. Before warmup, {@code e2e.backlog} commands
 *       (default 2,000,000) are sent unpaced; then a load thread replays both recordings from the
 *       start on the Reporter's replay streams, over and over, discarding what it reads, until the
 *       measurement ends. It reads as fast as the Archive serves, so it is an upper bound on the
 *       replay pressure of a catching-up Reporter, whose reads the database slows down.</li>
 *   <li>{@code e2e.tag}: a label for the result file under {@code bench.results}
 *       (default {@code ../../bench-results/p15-13}).</li>
 * </ul>
 *
 * <h2>Modes</h2>
 * Closed loop is the {@link EndToEndLatencyBenchmark} method (one order in flight, the same two
 * reused frames): service time with no queueing. It does not avoid coordinated omission, since a slow
 * op delays the next send, so its tail is never a saturation tail.
 *
 * Open loop avoids coordinated omission. Order k is due at {@code t0 + k / rate}; the sender spins
 * until it is due and hands it to the gateway, and its ingress is the due time, not the send time, so
 * time spent behind schedule counts against latency. The pipeline is FIFO from one producer and every
 * order yields exactly one terminal event (ACCEPTED for the rest, FILLED for the cross; the passive
 * side gets none), so the k-th terminal event is order k's: the subscriber stores egress by arrival
 * index and checks the order id. Ids cycle through 4,096 rest and cross pairs; at most a few thousand
 * orders can be in flight (the rings and one publication window), so an id is never reused while its
 * order rests, and a reuse would surface as a rejection and fail the run.
 *
 * <h2>Running it</h2>
 * Not a JMH benchmark and not run by a plain {@code mvn test} (the class name does not match
 * Surefire's patterns). From {@code app/backend}, on JDK 21:
 * <pre>{@code
 *   mvn -q test -Dtest=JournaledEndToEndLatencyBenchmark -De2e.latency=true -De2e.mode=open
 * }</pre>
 * {@code -Dtest} selects this class alone, so no other test shares the JVM.
 */
final class JournaledEndToEndLatencyBenchmark {

    private static final long TIMEOUT_MS = 30_000L;
    private static final long CLOSED_REST_ID = 1L;
    private static final long CLOSED_CROSS_ID = 2L;
    private static final long PRICE_UNITS = 10_000_000L;   // $1000.00, as in EndToEndLatencyBenchmark
    private static final long QTY = 100L;
    private static final int PAIRS = 4_096;
    private static final long FIRST_OPEN_ID = 1_000_000L;

    @Test
    void endToEndLatency() throws Exception {
        assumeTrue(Boolean.getBoolean("e2e.latency"),
                "Set -De2e.latency=true to run the journaled end-to-end latency benchmark");

        final String pipelineName = System.getProperty("e2e.pipeline", "journaled");
        final String mode = System.getProperty("e2e.mode", "closed");
        final String idle = System.getProperty("e2e.idle", "busy-spin");
        final String archive = System.getProperty("e2e.archive", "shared");
        final boolean open = "open".equals(mode);
        final boolean journaled = "journaled".equals(pipelineName);
        final boolean replayLoad = Boolean.getBoolean("e2e.replayLoad");
        if (!open && !"closed".equals(mode)) throw new IllegalArgumentException("e2e.mode " + mode);
        if (!journaled && !"unjournaled".equals(pipelineName)) throw new IllegalArgumentException("e2e.pipeline " + pipelineName);
        if (replayLoad && !journaled) throw new IllegalArgumentException("e2e.replayLoad needs the journaled pipeline");
        final int rate = Integer.getInteger("e2e.rate", 20_000);
        final int warmup = Integer.getInteger("e2e.warmup", open ? 120_000 : 200_000);
        final int measure = Integer.getInteger("e2e.measure", open ? 600_000 : 1_000_000);
        final int backlog = replayLoad ? Integer.getInteger("e2e.backlog", 2_000_000) : 0;
        final String symbol = FixConstants.SYMBOL_DISPLAY;

        final String timer = timerReport();

        Venue venue = journaled ? Venue.journaled(idle, archive) : Venue.unjournaled();
        String extra = "";
        long[] samples;
        long unexpected;
        try {
            if (open) {
                OpenLoop run = new OpenLoop(venue, symbol, backlog, warmup, measure, rate);
                samples = run.run(replayLoad);
                unexpected = run.recorder.mismatches();
                extra = "sender max lag behind schedule: " + run.maxLagNanos + " ns\n" + run.loadReport;
            } else {
                samples = closedLoop(venue, symbol, warmup, measure);
                unexpected = venue.closedRecorder.unexpected();
            }
        } finally {
            venue.close();
        }

        String config = "pipeline=" + pipelineName + " mode=" + mode
                + (open ? " rate=" + rate + "/s" : "")
                + (journaled ? " idle=" + idle + " archive=" + archive + " replayLoad=" + replayLoad + (replayLoad ? " backlog=" + backlog : "") : "");
        report(config, timer, extra, samples, warmup, unexpected);
    }

    // --- closed loop: the EndToEndLatencyBenchmark method, on either pipeline ---

    private static long[] closedLoop(Venue venue, String symbol, int warmup, int measure) throws InterruptedException {
        final byte[] restFrame = JsonToFix.newOrderSingle(CLOSED_REST_ID, Side.BUY, PRICE_UNITS, QTY, symbol);
        final byte[] crossFrame = JsonToFix.newOrderSingle(CLOSED_CROSS_ID, Side.SELL, PRICE_UNITS, QTY, symbol);
        final ClosedRecorder recorder = venue.closedRecorder;
        final OrderGateway gateway = venue.gateway;
        for (int i = 0; i < warmup; i++) {
            submitAndMeasure(gateway, recorder, restFrame, CLOSED_REST_ID);
            submitAndMeasure(gateway, recorder, crossFrame, CLOSED_CROSS_ID);
        }
        final long[] samples = new long[measure];
        int idx = 0;
        while (idx < measure) {
            samples[idx++] = submitAndMeasure(gateway, recorder, restFrame, CLOSED_REST_ID);
            if (idx < measure) {
                samples[idx++] = submitAndMeasure(gateway, recorder, crossFrame, CLOSED_CROSS_ID);
            }
        }
        return samples;
    }

    private static long submitAndMeasure(OrderGateway gateway, ClosedRecorder recorder,
                                         byte[] frame, long expectedOrderId) throws InterruptedException {
        recorder.arm(expectedOrderId);
        final long ingressNanos = System.nanoTime();
        gateway.onFrame(frame);
        final long egressNanos = recorder.awaitEgress(TIMEOUT_MS);
        return egressNanos - ingressNanos;
    }

    /** The {@link EndToEndLatencyBenchmark} recorder; session events are not orders and are skipped. */
    private static final class ClosedRecorder implements EventHandler<ExecutionEvent> {
        private final Semaphore done = new Semaphore(0);
        private volatile long expectedOrderId = Long.MIN_VALUE;
        private volatile long egressStamp;
        private volatile long unexpectedCount;

        void arm(long orderId) {
            this.expectedOrderId = orderId;
        }

        @Override
        public void onEvent(ExecutionEvent event, long sequence, boolean endOfBatch) {
            if (isSession(event)) return;
            if (event.orderId == expectedOrderId) {
                egressStamp = System.nanoTime();
                done.release();
            } else {
                unexpectedCount++;
            }
        }

        long awaitEgress(long timeoutMs) throws InterruptedException {
            if (!done.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("No execution for orderId=" + expectedOrderId + " within " + timeoutMs + " ms");
            }
            return egressStamp;
        }

        long unexpected() {
            return unexpectedCount;
        }
    }

    // --- open loop: fixed-rate injection, ingress = due time ---

    private static final class OpenLoop {
        final Venue venue;
        final byte[][] restFrames = new byte[PAIRS][];
        final byte[][] crossFrames = new byte[PAIRS][];
        final int backlog;
        final int warmup;
        final int measure;
        final double periodNanos;
        final OpenRecorder recorder;
        long maxLagNanos;
        String loadReport = "";

        OpenLoop(Venue venue, String symbol, int backlog, int warmup, int measure, int rate) {
            this.venue = venue;
            for (int i = 0; i < PAIRS; i++) {
                restFrames[i] = JsonToFix.newOrderSingle(restId(i), Side.BUY, PRICE_UNITS, QTY, symbol);
                crossFrames[i] = JsonToFix.newOrderSingle(restId(i) + 1, Side.SELL, PRICE_UNITS, QTY, symbol);
            }
            this.backlog = backlog;
            this.warmup = warmup;
            this.measure = measure;
            this.periodNanos = 1_000_000_000.0 / rate;
            this.recorder = venue.openRecorder;
            recorder.allocate(backlog + warmup + measure);
        }

        static long restId(int pair) {
            return FIRST_OPEN_ID + 2L * pair;
        }

        byte[] frame(long k) {
            int pair = (int) ((k >>> 1) % PAIRS);
            return (k & 1) == 0 ? restFrames[pair] : crossFrames[pair];
        }

        long[] run(boolean replayLoad) {
            final OrderGateway gateway = venue.gateway;
            long k = 0;
            for (; k < backlog; k++) {
                gateway.onFrame(frame(k));
            }
            if (backlog > 0) {
                final long target = backlog;
                awaitCount(() -> recorder.count() >= target, "the backlog's terminal events");
            }
            ReplayLoad load = replayLoad ? new ReplayLoad(venue.venue) : null;
            if (load != null) load.start();
            final long total = (long) backlog + warmup + measure;
            final long first = k;
            final long t0 = System.nanoTime() + 1_000_000L;
            long maxLag = 0;
            try {
                for (; k < total; k++) {
                    final long due = t0 + (long) ((k - first) * periodNanos);
                    long now;
                    while ((now = System.nanoTime()) < due) {
                        Thread.onSpinWait();
                    }
                    if (now - due > maxLag) maxLag = now - due;
                    gateway.onFrame(frame(k));
                }
                awaitCount(() -> recorder.count() >= total, "every terminal event");
            } finally {
                if (load != null) loadReport = load.stop();
            }
            maxLagNanos = maxLag;
            final long[] egress = recorder.egress();
            final long[] samples = new long[measure];
            final long measuredFrom = first + warmup;
            for (int i = 0; i < measure; i++) {
                long index = measuredFrom + i;
                samples[i] = egress[(int) index] - (t0 + (long) ((index - first) * periodNanos));
            }
            return samples;
        }
    }

    /** Stores egress by arrival index and checks that arrival k is order k's terminal event. */
    private static final class OpenRecorder implements EventHandler<ExecutionEvent> {
        private long[] egress = new long[0];
        private final AtomicLong count = new AtomicLong();
        private volatile long mismatches;

        void allocate(int total) {
            egress = new long[total];
        }

        @Override
        public void onEvent(ExecutionEvent event, long sequence, boolean endOfBatch) {
            if (isSession(event)) return;
            final long now = System.nanoTime();
            final long k = count.get();
            final int pair = (int) ((k >>> 1) % PAIRS);
            final boolean rest = (k & 1) == 0;
            final long expectedId = OpenLoop.restId(pair) + (rest ? 0 : 1);
            final ExecutionEventType expectedType = rest ? ExecutionEventType.ORDER_ACCEPTED : ExecutionEventType.ORDER_FILLED;
            if (event.orderId != expectedId || event.eventType != expectedType) {
                mismatches++;
            }
            if (k < egress.length) egress[(int) k] = now;
            count.lazySet(k + 1);
        }

        long count() {
            return count.get();
        }

        long[] egress() {
            return egress;
        }

        long mismatches() {
            return mismatches;
        }
    }

    /**
     * Replays both recordings from the start on the Reporter's replay streams, again and again, and
     * discards the bytes: the Archive's replay work for a Reporter catching up, without the database.
     */
    private static final class ReplayLoad {
        private final Journal journal;
        private final long inboundId;
        private final long outboundId;
        private final Thread thread;
        private volatile boolean stop;
        private volatile Throwable failure;
        private long bytes;
        private long passes;
        private long startedNanos;
        private long stoppedNanos;

        ReplayLoad(JournaledVenue venue) {
            this.journal = venue.journal();
            this.inboundId = venue.pipeline().inboundRecording().recordingId();
            this.outboundId = venue.pipeline().outboundRecording().recordingId();
            this.thread = new Thread(this::run, "replay-load");
            this.thread.setDaemon(true);
        }

        void start() {
            startedNanos = System.nanoTime();
            thread.start();
        }

        String stop() {
            stop = true;
            try {
                thread.join(TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stoppedNanos = System.nanoTime();
            if (thread.isAlive()) throw new IllegalStateException("replay load thread did not stop");
            if (failure != null) throw new IllegalStateException("replay load failed", failure);
            double seconds = (stoppedNanos - startedNanos) / 1e9;
            return String.format("replay load: %d passes over both recordings, %,d MiB in %.1f s, %.0f MiB/s%n",
                    passes, bytes >> 20, seconds, (bytes >> 20) / seconds);
        }

        private void run() {
            final AeronArchive archive = journal.archive();
            final FragmentHandler discard = (buffer, offset, length, header) -> bytes += length;
            final IdleStrategy idle = new BackoffIdleStrategy();
            try {
                while (!stop) {
                    long inboundStart = archive.getStartPosition(inboundId);
                    long outboundStart = archive.getStartPosition(outboundId);
                    long inboundEnd = archive.getRecordingPosition(inboundId);
                    long outboundEnd = archive.getRecordingPosition(outboundId);
                    Subscription in = archive.replay(inboundId, inboundStart, inboundEnd - inboundStart,
                            Journal.IPC_CHANNEL, Journal.REPORTER_INBOUND_STREAM_ID);
                    Subscription out = archive.replay(outboundId, outboundStart, outboundEnd - outboundStart,
                            Journal.IPC_CHANNEL, Journal.REPORTER_OUTBOUND_STREAM_ID);
                    try {
                        Image inImage = awaitImage(in);
                        Image outImage = awaitImage(out);
                        boolean inDone = false;
                        boolean outDone = false;
                        while (!stop && !(inDone && outDone)) {
                            int work = 0;
                            if (!inDone) {
                                work += inImage.poll(discard, 1024);
                                inDone = inImage.position() >= inboundEnd || inImage.isClosed();
                            }
                            if (!outDone) {
                                work += outImage.poll(discard, 1024);
                                outDone = outImage.position() >= outboundEnd || outImage.isClosed();
                            }
                            idle.idle(work);
                        }
                        if (inDone && outDone) passes++;
                    } finally {
                        in.close();
                        out.close();
                    }
                }
            } catch (Throwable t) {
                failure = t;
            }
        }

        private Image awaitImage(Subscription subscription) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (subscription.imageCount() == 0) {
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("no replay image");
                LockSupport.parkNanos(100_000L);
            }
            return subscription.imageAtIndex(0);
        }
    }

    // --- the two pipelines ---

    private static final class Venue {
        final ClosedRecorder closedRecorder = new ClosedRecorder();
        final OpenRecorder openRecorder = new OpenRecorder();
        OrderGateway gateway;
        JournaledVenue venue;
        Path root;
        InboundPipeline inbound;
        OutboundPipeline outbound;
        SnapshotPipeline snapshots;

        static Venue journaled(String idle, String archive) {
            Venue v = new Venue();
            v.root = BenchSupport.newRoot("e2e");
            v.venue = JournaledVenue.create(BenchSupport.productionConfig(v.root, idle, archive),
                    JournaledPipeline.Seams.NONE);
            v.venue.outbound().handleEventsWith(v.closedRecorder, v.openRecorder);
            v.venue.snapshots().handleEventsWith(new BenchSupport.NoOpSnapshots());
            v.gateway = v.venue.gateway();   // System::nanoTime receipt stamp, as the unjournaled harness
            v.venue.startAndOpen(v.gateway);
            return v;
        }

        static Venue unjournaled() {
            Venue v = new Venue();
            MatchingEngine engine = new MatchingEngine();
            v.outbound = new OutboundPipeline();
            v.snapshots = new SnapshotPipeline();
            v.outbound.handleEventsWith(v.closedRecorder, v.openRecorder);
            v.snapshots.handleEventsWith(new BenchSupport.NoOpSnapshots());
            MatchingEngineHandler handler =
                    new MatchingEngineHandler(engine, v.outbound.getRingBuffer(), v.snapshots.getRingBuffer());
            engine.setExecutionListener(handler);
            Sessions.open(handler);
            v.inbound = new InboundPipeline(handler);
            v.gateway = new OrderGateway(v.inbound.getRingBuffer());
            v.outbound.start();
            v.snapshots.start();
            v.inbound.start();
            return v;
        }

        void close() {
            if (venue != null) {
                try {
                    BenchSupport.report("archive: " + BenchSupport.archiveStats(venue.config().archiveDir()));
                    venue.close();
                } finally {
                    BenchSupport.deleteTree(root);
                }
            } else {
                inbound.shutdown();
                snapshots.shutdown();
                outbound.shutdown();
            }
        }
    }

    private static boolean isSession(ExecutionEvent event) {
        return event.eventType == ExecutionEventType.SESSION_OPENED || event.eventType == ExecutionEventType.SESSION_CLOSED;
    }

    private static void awaitCount(java.util.function.BooleanSupplier done, String what) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for " + what);
            LockSupport.parkNanos(100_000L);
        }
    }

    // --- timer facts for the report header ---

    /** The smallest nonzero step of System.nanoTime, and what a 1 microsecond park really takes. */
    private static String timerReport() {
        long minStep = Long.MAX_VALUE;
        long last = System.nanoTime();
        for (int i = 0; i < 2_000_000; i++) {
            long now = System.nanoTime();
            long step = now - last;
            if (step > 0 && step < minStep) minStep = step;
            last = now;
        }
        final int parks = 200;
        long[] took = new long[parks];
        for (int i = 0; i < parks; i++) {
            long start = System.nanoTime();
            LockSupport.parkNanos(1_000L);
            took[i] = System.nanoTime() - start;
        }
        Arrays.sort(took);
        return String.format("nanoTime granularity: %d ns; parkNanos(1 us) took median %,d ns, min %,d ns, max %,d ns%n",
                minStep, took[parks / 2], took[0], took[parks - 1]);
    }

    // --- reporting ---

    private static void report(String config, String timer, String extra, long[] samples, int warmup, long unexpected) {
        final long[] sorted = samples.clone();
        Arrays.sort(sorted);
        final int n = sorted.length;
        long sum = 0;
        for (long v : sorted) sum += v;
        final double meanNs = (double) sum / n;

        final StringBuilder sb = new StringBuilder(1024);
        sb.append("=== P15-13 end-to-end latency (gateway receipt -> execution publication) ===\n");
        sb.append("config     : ").append(config).append('\n');
        sb.append("jvm        : ").append(System.getProperty("java.vm.version")).append('\n');
        sb.append("timer      : ").append(timer);
        sb.append("warmup     : ").append(warmup).append(" (discarded)\n");
        sb.append("measured   : ").append(n).append(" orders\n");
        sb.append("unexpected : ").append(unexpected).append(" (expect 0)\n");
        sb.append(extra);
        sb.append(fmt("min", sorted[0]));
        sb.append(String.format("%-11s: %,.0f ns%n", "mean", meanNs));
        sb.append(fmt("p50", pct(sorted, 50.0)));
        sb.append(fmt("p90", pct(sorted, 90.0)));
        sb.append(fmt("p99", pct(sorted, 99.0)));
        sb.append(fmt("p99.9", pct(sorted, 99.9)));
        sb.append(fmt("p99.99", pct(sorted, 99.99)));
        sb.append(fmt("max", sorted[n - 1]));
        final String out = sb.toString();
        System.out.print(out);

        final String tag = System.getProperty("e2e.tag", "run");
        try {
            Path dir = Path.of(System.getProperty("bench.results", "../../bench-results/p15-13"));
            Files.createDirectories(dir);
            Path p = dir.resolve("e2e-" + tag + ".txt");
            Files.writeString(p, out);
            System.out.println("(written to " + p.toAbsolutePath().normalize() + ")");
        } catch (IOException e) {
            System.out.println("(could not write results file: " + e.getMessage() + ")");
        }
        if (unexpected != 0) throw new AssertionError(unexpected + " unexpected events");
    }

    private static String fmt(String label, long ns) {
        return String.format("%-11s: %,d ns%n", label, ns);
    }

    /** Nearest-rank percentile on an ascending-sorted array. */
    private static long pct(long[] sortedAsc, double p) {
        final int n = sortedAsc.length;
        int rank = (int) Math.ceil(p / 100.0 * n);
        if (rank < 1) rank = 1; else if (rank > n) rank = n;
        return sortedAsc[rank - 1];
    }
}
