package benchmark;

import com.lmax.disruptor.RingBuffer;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import engine.Sessions;
import event.InboundPipeline;
import event.OrderEvent;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import integration.JournaledVenue;
import journal.JournaledPipeline;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * P15-13 questions A1b and A3: commands per second through the inbound ring and the engine, with
 * and without the journal, on the Phase 14 workload (rest, then exact cross; book held at 0 or 1).
 *
 * <ul>
 *   <li>{@code unjournaled}: the pre-P15-4 wiring (the P6-4 harness's): the inbound ring's consumer
 *       is the {@link MatchingEngineHandler} itself, publishing to the outbound and snapshot rings.
 *       Compared with {@link MatchingEngineThroughputBenchmark} (engine only), it adds the handler,
 *       the rings and the per-command BOOK snapshot.</li>
 *   <li>{@code journaled}: the production {@link JournaledPipeline} (sequencer, recorded inbound
 *       stream, D11 gate, engine agent, outbound recorder, periodic snapshots) with production
 *       journal defaults. Compared with {@code unjournaled}, it adds exactly the journal.</li>
 * </ul>
 *
 * Each op publishes one command onto the inbound ring from the benchmark thread (its only producer;
 * no FIX parse, so the gateway's parse is not counted). A full ring holds the producer, so the
 * steady-state rate is the pipeline's bottleneck. Each iteration ends with a bounded wait until every
 * published command has produced its one terminal event, so iterations do not overlap; the work in
 * flight when an iteration's clock stops is at most the rings plus one publication window (about
 * 3,000 commands), under 0.3% of an iteration. Both pipelines get the same outbound consumers: an
 * outcome counter (and, journaled, the recorder the pipeline adds); the publisher and trade logger
 * are not attached. The trial ends by checking that every command was accepted.
 *
 * Run with JDK 21 on {@code JAVA_HOME}:
 * {@code mvn jmh:benchmark -Djmh.benchmarks=PipelineThroughput}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 5, jvmArgsAppend = {BenchSupport.ADD_EXPORTS, BenchSupport.MISC})
@Warmup(iterations = 20, time = 1)
@Measurement(iterations = 10, time = 2)
public class PipelineThroughputBenchmark {

    @Param({"unjournaled", "journaled"})
    public String pipeline;

    /** Engine agent idle strategy (journaled only; D9: busy-spin is for benchmarks). */
    @Param({"busy-spin"})
    public String idle;

    private Path root;
    private JournaledVenue venue;
    private InboundPipeline inbound;
    private OutboundPipeline outbound;
    private SnapshotPipeline snapshots;
    private RingBuffer<OrderEvent> ring;
    private BenchSupport.OutcomeCounter counter;
    private BenchSupport.RestCross workload;

    @Setup(Level.Trial)
    public void setUp() {
        counter = new BenchSupport.OutcomeCounter();
        if ("journaled".equals(pipeline)) {
            root = BenchSupport.newRoot("pipeline");
            venue = JournaledVenue.create(BenchSupport.productionConfig(root, idle), JournaledPipeline.Seams.NONE);
            venue.outbound().handleEventsWith(counter);
            venue.snapshots().handleEventsWith(new BenchSupport.NoOpSnapshots());
            venue.startAndOpen(venue.gateway());
            BenchSupport.await(() -> counter.sessionEvents() == 1, "SESSION_OPENED");
            ring = venue.pipeline().inboundRing();
        } else if ("unjournaled".equals(pipeline)) {
            MatchingEngine engine = new MatchingEngine();
            outbound = new OutboundPipeline();
            snapshots = new SnapshotPipeline();
            outbound.handleEventsWith(counter);
            snapshots.handleEventsWith(new BenchSupport.NoOpSnapshots());
            MatchingEngineHandler handler =
                    new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
            engine.setExecutionListener(handler);
            Sessions.open(handler);
            inbound = new InboundPipeline(handler);
            outbound.start();
            snapshots.start();
            inbound.start();
            ring = inbound.getRingBuffer();
        } else {
            throw new IllegalArgumentException("unknown pipeline " + pipeline);
        }
        workload = new BenchSupport.RestCross(1L, System.nanoTime() + 1_000_000L);
    }

    @Benchmark
    public long publish() {
        ring.publishEvent(workload);
        return workload.published();
    }

    @TearDown(Level.Iteration)
    public void drain() {
        final long target = workload.published();
        BenchSupport.await(() -> counter.orderEvents() == target, "terminal event for command " + target);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        try {
            counter.checkAllAccepted(workload.published());
            if (venue != null) {
                JournaledPipeline p = venue.pipeline();
                BenchSupport.report(pipeline + " published=" + workload.published() + " journaledSeq="
                        + p.journaledSeq() + " appliedSeq=" + p.appliedSeq() + " snapshots written="
                        + p.snapshotWriter().writtenCount() + " archive: "
                        + BenchSupport.archiveStats(venue.config().archiveDir()) + "; " + counter.summary());
                if (p.appliedSeq() != p.journaledSeq()) {
                    throw new IllegalStateException("applied " + p.appliedSeq() + " != journaled " + p.journaledSeq());
                }
            } else {
                BenchSupport.report(pipeline + " published=" + workload.published() + "; " + counter.summary());
            }
        } finally {
            if (venue != null) {
                venue.close();
                BenchSupport.deleteTree(root);
            } else {
                inbound.shutdown();
                snapshots.shutdown();
                outbound.shutdown();
            }
        }
    }
}
