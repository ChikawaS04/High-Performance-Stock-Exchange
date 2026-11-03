package journal;

import com.lmax.disruptor.RingBuffer;
import config.ExchangeConfig;
import engine.CommandHandler;
import engine.MatchingEngine;
import engine.Sessions;
import event.CapturingExecutionHandler;
import event.ExecutionEventType;
import event.OrderEvent;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import util.FailStop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static journal.JournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The journaled pipeline's lifecycle (P15-4, recovery from P15-5): restart on a non-empty
 * journal, the engine subscribed before the first offer, graceful shutdown drained by progress, and
 * fail-stop from both the sequencer and the engine agent.
 */
class JournaledPipelineTest {

    @TempDir
    Path root;

    private record Fatal(String component, Throwable cause) { }

    private final List<Fatal> fatals = Collections.synchronizedList(new ArrayList<>());
    private final FailStop capture = (component, cause) -> fatals.add(new Fatal(component, cause));

    private static void publish(RingBuffer<OrderEvent> ring, OrderEvent command) {
        ring.publishEvent(COPY_INTO_SLOT, command);
    }

    /** P15-8: on a fresh journal the session open is inbound seq 1 and its SESSION_OPENED outbound seq 1. */
    private static final long OPEN = 1L;

    /** Publishes a session open, so later orders are matched (P15-8). */
    private static void openSession(JournaledPipeline pipeline) {
        publish(pipeline.inboundRing(), Sessions.openCommand(Sessions.BUSINESS_DATE, 0L));
    }

    @Test
    void theFirstCommandAfterStartIsApplied() {
        MatchingEngine engine = new MatchingEngine();
        JournaledPipeline pipeline = new JournaledPipeline(config(root), engine, capture);
        CapturingExecutionHandler executions = new CapturingExecutionHandler();
        pipeline.outbound().handleEventsWith(executions);
        pipeline.start();

        openSession(pipeline);                              // no pause between start and the first offers
        publish(pipeline.inboundRing(), limit(1L, 1L));
        await(() -> pipeline.appliedSeq() == OPEN + 1, "first order applied");
        assertTrue(engine.hasOpenOrder(1L));
        assertTrue(pipeline.shutdown());
        assertEquals(ExecutionEventType.SESSION_OPENED, executions.snapshot().get(0).eventType());
        assertEquals(ExecutionEventType.ORDER_ACCEPTED, executions.snapshot().get(1).eventType());
        assertTrue(fatals.isEmpty());
    }

    @Test
    void gracefulShutdownDrainsEverythingThatWasPublished() {
        int count = 3_000;   // about three rings' worth, published straight into shutdown
        ExchangeConfig config = config(root);
        JournaledPipeline pipeline = new JournaledPipeline(config, new MatchingEngine(), capture);
        CapturingExecutionHandler executions = new CapturingExecutionHandler();
        pipeline.outbound().handleEventsWith(executions);
        pipeline.start();
        openSession(pipeline);
        for (int i = 1; i <= count; i++) publish(pipeline.inboundRing(), limit(i, i));

        assertTrue(pipeline.shutdown(), "every drain completed");
        assertEquals(OPEN + count, pipeline.journaledSeq());
        assertEquals(OPEN + count, pipeline.appliedSeq());
        assertEquals(OPEN + count, executions.snapshot().size(), "the SESSION_OPENED and every ACCEPTED reached the subscriber");
        assertTrue(fatals.isEmpty());

        try (Journal reopened = Journal.launch(config)) {
            assertEquals(OPEN + count, readAll(reopened).size(), "every command is on file");
        }
    }

    @Test
    void startupRecoversARecordingThatAlreadyHoldsCommands() {
        ExchangeConfig config = config(root);
        JournaledPipeline first = new JournaledPipeline(config, new MatchingEngine(), capture);
        first.start();
        openSession(first);
        publish(first.inboundRing(), limit(1L, 1L));
        await(() -> first.appliedSeq() == OPEN + 1, "first run applied its commands");
        assertTrue(first.shutdown());

        // P15-5 replaced the P15-4 refusal: the second run recovers the resting order and continues.
        MatchingEngine engine = new MatchingEngine();
        JournaledPipeline second = new JournaledPipeline(config, engine, capture);
        assertEquals(OPEN + 1, second.appliedSeq(), "recovered to the journal's last seq");
        assertTrue(engine.hasOpenOrder(1L), "the resting order is back");
        second.start();
        publish(second.inboundRing(), limit(2L, 2L));   // the recovered session is still open
        await(() -> second.appliedSeq() == OPEN + 2, "the next command follows on");
        assertTrue(engine.hasOpenOrder(2L), "matched in the recovered session");
        assertTrue(second.shutdown());
        assertTrue(fatals.isEmpty());

        try (Journal again = Journal.launch(config)) {
            assertEquals(OPEN + 2, readAll(again).size(), "one continuous journal across both runs");
        }
    }

    @Test
    void aSequencerFailureReachesFailStopAndTheSlotIsNotConsumed() {
        JournaledPipeline pipeline = new JournaledPipeline(config(root), new MatchingEngine(), capture);
        pipeline.start();
        openSession(pipeline);
        publish(pipeline.inboundRing(), limit(1L, 1L));
        await(() -> pipeline.appliedSeq() == OPEN + 1, "first commands applied");

        OrderEvent unencodable = limit(2L, 2L);
        unencodable.eventType = null;   // the encoder cannot journal it
        publish(pipeline.inboundRing(), unencodable);
        publish(pipeline.inboundRing(), limit(3L, 3L));

        await(() -> !fatals.isEmpty(), "fail-stop reported");
        assertEquals("sequencer", fatals.get(0).component());
        assertEquals(OPEN + 1, pipeline.journaledSeq(), "nothing after the failing command was journaled");
        assertFalse(pipeline.shutdown(), "shutdown after a fatal is not clean");
        assertEquals(OPEN + 1, pipeline.appliedSeq());
        assertEquals(1, fatals.size());
    }

    @Test
    void anEngineAgentFailureReachesFailStopWithItsCause() {
        CommandHandler tap = command -> {
            if (command.orderId == 2L) throw new IllegalStateException("tap fails on 2");
        };
        MatchingEngine engine = new MatchingEngine();
        JournaledPipeline pipeline = new JournaledPipeline(config(root), engine, capture, tap);
        pipeline.start();
        openSession(pipeline);
        for (int i = 1; i <= 3; i++) publish(pipeline.inboundRing(), limit(i, i));

        await(() -> !fatals.isEmpty(), "fail-stop reported");
        assertEquals("engine-agent", fatals.get(0).component());
        assertEquals("tap fails on 2", fatals.get(0).cause().getMessage(), "the cause is unwrapped");
        await(() -> pipeline.journaledSeq() == OPEN + 3, "the sequencer journaled all four");
        assertEquals(OPEN + 1, pipeline.appliedSeq());
        assertTrue(engine.hasOpenOrder(1L), "order 1 was applied in the open session");
        assertFalse(engine.hasOpenOrder(3L), "command 3 was never applied");
        assertFalse(pipeline.shutdown());
        assertEquals(1, fatals.size(), "the stopped agent reports once");
    }

    @Test
    void everyEngineIdleSettingMapsToItsStrategy() {
        assertInstanceOf(BackoffIdleStrategy.class, JournaledPipeline.idleStrategy(ExchangeConfig.EngineIdle.BACKOFF));
        assertInstanceOf(SleepingMillisIdleStrategy.class, JournaledPipeline.idleStrategy(ExchangeConfig.EngineIdle.SLEEPING));
        assertInstanceOf(YieldingIdleStrategy.class, JournaledPipeline.idleStrategy(ExchangeConfig.EngineIdle.YIELDING));
        assertInstanceOf(BusySpinIdleStrategy.class, JournaledPipeline.idleStrategy(ExchangeConfig.EngineIdle.BUSY_SPIN));
    }
}
