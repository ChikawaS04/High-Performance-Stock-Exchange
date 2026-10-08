package benchmark;

import com.lmax.disruptor.EventHandler;

import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import event.BookSnapshotEvent;
import event.ExecutionEvent;
import event.InboundPipeline;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import gateway.FixConstants;
import gateway.OrderGateway;
import model.Side;
import net.JsonToFix;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P6-4 — End-to-end pipeline latency (SRS §6.3 benchmark #2:
 * "gateway receipt to execution event publication", p50/p99/p99.9).
 *
 * <h2>Why this is not a JMH @Benchmark</h2>
 * The measured operation completes on a different thread from the one that starts it:
 * the harness (single inbound producer) hands a FIX frame to {@link OrderGateway#onFrame},
 * which crosses the inbound ring into the engine thread; the engine matches and publishes
 * the terminal {@link ExecutionEvent} on the outbound ring. Timing {@code onFrame}'s return
 * would measure only the enqueue. JMH {@code Mode.SampleTime} could recover a percentile via
 * a per-op latch, but the latch park/unpark cost then lands *inside* the reported number, and
 * pairing it with a separate histogram would be blending two measurements. So this is a
 * standalone closed-loop runner that records the true publication span into a pre-allocated
 * array and computes exact percentiles by sorting — no HdrHistogram dependency, no JMH.
 *
 * <h2>Span boundaries (SRS-literal)</h2>
 * <ul>
 *   <li><b>ingress</b> = {@code System.nanoTime()} the instant the frame is handed to
 *       {@code OrderGateway.onFrame} (≈ gateway receipt; the FIX parse is on the path).</li>
 *   <li><b>egress</b> = {@code ExecutionEvent.timestamp}, the handler's publish-instant
 *       ({@code MatchingEngineHandler.publish} stamps {@code clock.getAsLong()},
 *       default {@code System::nanoTime}) = "execution event publication".</li>
 * </ul>
 * Netty/WebSocket I/O is out (§6.3 says "publication", not client receipt; the WebSocket
 * round-trip is the separate §6.2 item covered by {@code WebSocketRoundTripTest}). Outbound-ring
 * *delivery* to the subscriber is also out: the value read is the publish-stamp carried on the
 * carrier, so the span is inbound-ring transport + matching + publish.
 *
 * <h2>No production change</h2>
 * The ingress stamp already rides {@code OrderEvent.timestamp} (gateway, Step 7); the egress
 * stamp already rides {@code ExecutionEvent.timestamp} (handler). They live on different
 * carriers and are not linked on one, so this runner correlates them by {@code orderId}.
 * Closed-loop with one order in flight collapses the correlation to a single handoff — no map,
 * no new carrier field, nothing touched in {@code src/main/java}.
 *
 * <h2>Measurement mode — honest labelling</h2>
 * Closed-loop ping-pong, one order in flight: this is <b>service-time under no queueing</b>,
 * not saturation latency. Closed-loop under-samples the tail (a slow op delays the next send),
 * so these percentiles must never be quoted as a saturation tail or an SLA. Open-loop
 * rate-paced generation with coordinated-omission correction is the more-code alternative,
 * deliberately not done here.
 *
 * <h2>Workload</h2>
 * 50/50 alternation of a passive BUY that rests into the empty book (→ exactly one
 * {@code ORDER_ACCEPTED}) and an aggressive SELL that exact-crosses and evicts it (→ exactly
 * one {@code ORDER_FILLED}). The book is held at depth 0–1, so per-op work is stationary across
 * the whole run (the same reason P6-2 chose this shape) — a drifting distribution would make
 * p99.9 meaningless. Two fixed frames are reused every round, so the harness allocates nothing
 * per round; the recorded distribution is therefore bimodal (a lighter accept mode and a
 * heavier fill mode that carries the match + Trade cost), which is reported as one blended
 * distribution per the SRS's single-number framing.
 *
 * <h2>Running it</h2>
 * Not a {@code @Benchmark}, so {@code mvn jmh:benchmark} ignores it; and the class name does not
 * match Surefire's default includes, so a normal {@code mvn test} never runs it. Invoke
 * explicitly, on a JDK-21 shell, from {@code backend/}:
 * <pre>{@code
 *   mvn -q test -Dtest=EndToEndLatencyBenchmark -De2e.latency=true
 * }</pre>
 * Optional: {@code -De2e.warmup=<rounds>} {@code -De2e.measure=<orders>}. Results print to
 * stdout and to {@code target/e2e-latency-results.txt}.
 *
 * <h2>Caveat</h2>
 * Single unpinned laptop, one JVM (no JMH cross-fork variance). Do not publish any tail
 * percentile as a hard number — same machine caveat carried through P6-1..P6-3.
 */
final class EndToEndLatencyBenchmark {

    /** Bound every wait so a dropped frame surfaces as a loud timeout, never a hang. */
    private static final long TIMEOUT_MS = 5_000L;

    /** Fixed positive ClOrdIDs (never {@code util.IDGenerator}); never open simultaneously. */
    private static final long REST_ID = 1L;
    private static final long CROSS_ID = 2L;

    private static final long PRICE_UNITS = 10_000_000L;   // $1000.00
    private static final long QTY = 100L;

    @Test
    void endToEndLatency() throws Exception {
        assumeTrue(Boolean.getBoolean("e2e.latency"),
                "Set -De2e.latency=true to run the end-to-end latency benchmark");

        final int warmup = Integer.getInteger("e2e.warmup", 200_000);
        final int measure = Integer.getInteger("e2e.measure", 1_000_000);
        final String symbol = FixConstants.SYMBOL_DISPLAY;   // must match the gateway's configured symbol

        // Two frames, built once, reused every round (off-span; zero per-round allocation).
        final byte[] restFrame = JsonToFix.newOrderSingle(REST_ID, Side.BUY, PRICE_UNITS, QTY, symbol);
        final byte[] crossFrame = JsonToFix.newOrderSingle(CROSS_ID, Side.SELL, PRICE_UNITS, QTY, symbol);

        // --- pipeline core: Main's (P4-7) wiring order, Netty stripped ---
        final MatchingEngine engine = new MatchingEngine();
        final OutboundPipeline outbound = new OutboundPipeline();
        final SnapshotPipeline snapshots = new SnapshotPipeline();

        final LatencyRecorder recorder = new LatencyRecorder();
        outbound.handleEventsWith(recorder);                 // replaces pub.executionHandler() + tradeLogger
        snapshots.handleEventsWith(new NoOpSnapshotHandler()); // replaces pub.snapshotHandler() + marketData

        final MatchingEngineHandler handler =
                new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
        engine.setExecutionListener(handler);   // ⚠ omit this and the engine matches but emits nothing

        final InboundPipeline inbound = new InboundPipeline(handler);
        final OrderGateway gateway = new OrderGateway(inbound.getRingBuffer());

        // Both outbound rings before inbound, or the engine publishes into rings with no consumer.
        outbound.start();
        snapshots.start();
        inbound.start();

        final long[] samples = new long[measure];
        try {
            // Warmup — let C2 compile engine/gateway/consumers and reach steady state; discard.
            for (int i = 0; i < warmup; i++) {
                submitAndMeasure(gateway, recorder, restFrame, REST_ID);
                submitAndMeasure(gateway, recorder, crossFrame, CROSS_ID);
            }
            // Measure — one sample per order, rest/cross alternating.
            int idx = 0;
            while (idx < measure) {
                samples[idx++] = submitAndMeasure(gateway, recorder, restFrame, REST_ID);
                if (idx < measure) {
                    samples[idx++] = submitAndMeasure(gateway, recorder, crossFrame, CROSS_ID);
                }
            }
        } finally {
            // Teardown is the strict reverse of start (drain inbound first).
            inbound.shutdown();
            snapshots.shutdown();
            outbound.shutdown();
        }

        report(samples, warmup, measure, recorder.unexpected(), symbol);
    }

    /**
     * One closed-loop round: arm the recorder, stamp ingress, hand the frame to the gateway,
     * block until the terminal execution for this order is published, return egress − ingress.
     * The arm() + ingress stamp are program-ordered before onFrame, which happens-before the
     * execution via the ring barriers, so the recorder sees the right expected id.
     */
    private static long submitAndMeasure(OrderGateway gateway, LatencyRecorder recorder,
                                         byte[] frame, long expectedOrderId) throws InterruptedException {
        recorder.arm(expectedOrderId);
        final long ingressNanos = System.nanoTime();   // ingress = frame handed to the gateway
        gateway.onFrame(frame);
        final long egressNanos = recorder.awaitEgress(TIMEOUT_MS);
        return egressNanos - ingressNanos;
    }

    // --- terminal outbound subscriber: reads the publish-stamp, hands it back, paces the loop ---

    /**
     * Runs on the outbound-ring consumer thread. Matches the execution to the in-flight order by
     * {@code orderId}, captures {@code ExecutionEvent.timestamp} (the publish instant), and
     * releases the producer. The captured stamp is the egress boundary — outbound delivery to
     * this handler is not part of the recorded value.
     */
    private static final class LatencyRecorder implements EventHandler<ExecutionEvent> {

        private final Semaphore done = new Semaphore(0);
        private volatile long expectedOrderId;
        private volatile long egressStamp;
        private volatile long unexpectedCount;

        void arm(long orderId) {
            this.expectedOrderId = orderId;
        }

        @Override
        public void onEvent(ExecutionEvent event, long sequence, boolean endOfBatch) {
            if (event.orderId == expectedOrderId) {
                egressStamp = event.timestamp;   // execution-event publication instant
                done.release();
            } else {
                unexpectedCount++;               // should stay 0 in strict closed-loop
            }
        }

        /** @return the egress publish nanoTime for the armed order, or throws on timeout. */
        long awaitEgress(long timeoutMs) throws InterruptedException {
            if (!done.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("No execution for orderId=" + expectedOrderId
                        + " within " + timeoutMs + " ms — dropped frame (symbol mismatch?) or wiring gap");
            }
            return egressStamp;
        }

        long unexpected() {
            return unexpectedCount;
        }
    }

    /** Drains the snapshot ring so the engine thread never blocks on a full snapshot buffer. */
    private static final class NoOpSnapshotHandler implements EventHandler<BookSnapshotEvent> {
        @Override
        public void onEvent(BookSnapshotEvent event, long sequence, boolean endOfBatch) {
            // intentionally empty
        }
    }

    // --- reporting ---

    private static void report(long[] samples, int warmup, int measure, long unexpected, String symbol) {
        final long[] sorted = samples.clone();
        Arrays.sort(sorted);
        final int n = sorted.length;

        long sum = 0;
        for (long v : sorted) {
            sum += v;
        }
        final double meanNs = (double) sum / n;

        final StringBuilder sb = new StringBuilder(1024);
        sb.append("=== P6-4 end-to-end latency (gateway receipt -> execution publication) ===\n");
        sb.append("mode       : closed-loop ping-pong, one order in flight (service-time, NOT saturation)\n");
        sb.append("span       : nanoTime at OrderGateway.onFrame  ->  ExecutionEvent.timestamp (publish)\n");
        sb.append("workload   : 50/50 rest(ACCEPTED)/exact-cross(FILLED), symbol=").append(symbol)
                .append(", book held 0-1 levels\n");
        sb.append("warmup     : ").append(warmup).append(" rounds (x2 orders, discarded)\n");
        sb.append("measured   : ").append(n).append(" orders\n");
        sb.append("unexpected : ").append(unexpected).append(" (orderId mismatches; expect 0)\n");
        sb.append(fmt("min", sorted[0]));
        sb.append(String.format("%-11s: %,.0f ns  (%.3f us)\n", "mean", meanNs, meanNs / 1000.0));
        sb.append(fmt("p50", pct(sorted, 50.0)));
        sb.append(fmt("p90", pct(sorted, 90.0)));
        sb.append(fmt("p99", pct(sorted, 99.0)));
        sb.append(fmt("p99.9", pct(sorted, 99.9)));
        sb.append(fmt("p99.99", pct(sorted, 99.99)));
        sb.append(fmt("max", sorted[n - 1]));

        final String out = sb.toString();
        System.out.print(out);

        try {
            final Path p = Path.of("target", "e2e-latency-results.txt");
            Files.createDirectories(p.getParent());
            Files.writeString(p, out);
            System.out.println("(written to " + p.toAbsolutePath() + ")");
        } catch (IOException e) {
            System.out.println("(could not write results file: " + e.getMessage() + ")");
        }
    }

    private static String fmt(String label, long ns) {
        return String.format("%-11s: %,d ns  (%.3f us)\n", label, ns, ns / 1000.0);
    }

    /** Nearest-rank percentile on an ascending-sorted array; p in [0,100]. */
    private static long pct(long[] sortedAsc, double p) {
        final int n = sortedAsc.length;
        int rank = (int) Math.ceil(p / 100.0 * n);   // 1-based
        if (rank < 1) {
            rank = 1;
        } else if (rank > n) {
            rank = n;
        }
        return sortedAsc[rank - 1];
    }
}