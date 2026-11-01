package journal;

import codec.OrderEventCodec;
import engine.CommandGate;
import engine.CommandHandler;
import engine.EngineAgent;
import event.OrderEvent;
import io.aeron.Image;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static journal.JournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine agent against a real journal (SRS §3.3, D11). The sequencer offers on the test
 * thread and the test thread also plays the agent's thread by calling doWork directly, so each
 * test controls exactly when the agent polls.
 */
class EngineAgentTest {

    @TempDir
    Path root;

    private Journal journal;
    private RecordedPublication recorded;
    private Sequencer sequencer;
    private Image image;
    private RecordingPositionGate realGate;
    private final List<OrderEvent> applied = Collections.synchronizedList(new ArrayList<>());
    private final CommandHandler capture = command -> applied.add(copyOf(command));

    @BeforeEach
    void setUp() {
        journal = Journal.launch(config(root));
        recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
        sequencer = Sequencer.open(journal, recorded, new SequencerTest.CountingIdle());
        image = journal.subscribe(recorded);
        realGate = journal.recordingPositionGate(recorded);
    }

    @AfterEach
    void tearDown() {
        journal.close();
    }

    private EngineAgent agent(CommandGate gate, CommandHandler handler) {
        EngineAgent agent = new EngineAgent(image, gate, handler, recorded.joinPosition(), 0L);
        agent.onStart();
        return agent;
    }

    private void offer(OrderEvent command) {
        sequencer.onEvent(command, 0L, true);
    }

    /** Waits until the Archive has recorded everything offered so far. */
    private void awaitRecorded() {
        long target = recorded.publication().position();
        await(() -> realGate.limit() >= target, "recording to reach " + target);
    }

    /** Runs the agent's duty cycle on this thread until {@code n} commands are applied. */
    private void workUntilApplied(EngineAgent agent, int n) {
        await(() -> {
            agent.doWork();
            return applied.size() >= n;
        }, n + " commands applied");
    }

    /** A gate the test holds back. */
    private static final class HeldGate implements CommandGate {
        final AtomicLong limit;
        volatile boolean live = true;

        HeldGate(long limit) { this.limit = new AtomicLong(limit); }

        @Override public long limit() { return limit.get(); }
        @Override public boolean isLive() { return live; }
    }

    @Test
    void appliesJournaledCommandsInOrderAndTracksTheAppliedSeq() {
        EngineAgent agent = agent(realGate, capture);
        for (int i = 1; i <= 5; i++) offer(limit(i, 1_000L + i));

        workUntilApplied(agent, 5);
        assertEquals(5, agent.appliedSeq());
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, applied.get(i).orderId, "journal order");
        }
    }

    @Test
    void aCommandIsNeverProcessedBeforeItsRecordedPosition() {
        HeldGate gate = new HeldGate(recorded.joinPosition());
        EngineAgent agent = agent(gate, capture);

        offer(limit(1L, 1L));
        long afterFirst = recorded.publication().position();
        offer(limit(2L, 2L));
        offer(limit(3L, 3L));
        long afterThird = recorded.publication().position();
        awaitRecorded();   // the real recording holds all three

        for (int i = 0; i < 50; i++) agent.doWork();
        assertTrue(applied.isEmpty(), "nothing is applied while the gate is at the join position");
        assertEquals(0, agent.appliedSeq());
        assertEquals(recorded.joinPosition(), image.position(), "the image did not move past the gate");

        gate.limit.set(afterFirst);
        workUntilApplied(agent, 1);
        for (int i = 0; i < 50; i++) agent.doWork();
        assertEquals(1, applied.size(), "exactly the command at or below the gate is applied");
        assertEquals(afterFirst, image.position());

        gate.limit.set(afterThird);
        workUntilApplied(agent, 3);
        assertEquals(3, agent.appliedSeq());
    }

    @Test
    void anUndecodableCommandIsFatalAndNothingAfterItIsApplied() {
        EngineAgent agent = agent(realGate, capture);
        offer(limit(1L, 1L));
        long afterFirst = recorded.publication().position();
        UnsafeBuffer junk = new UnsafeBuffer(ByteBuffer.allocateDirect(64));   // schema id 0
        while (recorded.publication().offer(junk, 0, 16) < 0) Thread.onSpinWait();
        offer(limit(3L, 3L));
        awaitRecorded();

        AgentTerminationException failure = workUntilFailure(agent);
        assertTrue(failure.getCause().getMessage().contains("Foreign schema id"), failure.getCause().getMessage());
        assertEquals(1, applied.size());
        assertEquals(1, agent.appliedSeq());
        assertEquals(afterFirst, image.position(), "the image stays on the failing command");

        assertThrows(AgentTerminationException.class, agent::doWork, "it never moves past it");
        assertEquals(1, applied.size());
    }

    @Test
    void aSeqGapIsFatal() {
        EngineAgent agent = agent(realGate, capture);
        offer(limit(1L, 1L));
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(OrderEventCodec.MAX_ENCODED_LENGTH));
        int length = new OrderEventCodec().encode(limit(5L, 5L), 5L, buffer, 0);
        while (recorded.publication().offer(buffer, 0, length) < 0) Thread.onSpinWait();
        awaitRecorded();

        AgentTerminationException failure = workUntilFailure(agent);
        assertTrue(failure.getCause().getMessage().contains("Inbound seq 5 after applied seq 1"),
                failure.getCause().getMessage());
        assertEquals(1, agent.appliedSeq());
    }

    @Test
    void aHandlerExceptionIsFatalAndTheCommandIsNotSkipped() {
        CommandHandler failsOnTwo = command -> {
            if (command.orderId == 2L) throw new IllegalStateException("boom on 2");
            capture.onCommand(command);
        };
        EngineAgent agent = agent(realGate, failsOnTwo);
        offer(limit(1L, 1L));
        long afterFirst = recorded.publication().position();
        offer(limit(2L, 2L));
        offer(limit(3L, 3L));
        awaitRecorded();

        AgentTerminationException failure = workUntilFailure(agent);
        assertEquals("boom on 2", failure.getCause().getMessage());
        assertEquals(1, agent.appliedSeq());
        assertEquals(afterFirst, image.position());
        assertEquals(1, applied.size(), "command 3 is never applied");
    }

    @Test
    void aLateJoinIsFatalOnStart() {
        EngineAgent agent = new EngineAgent(image, realGate, capture, recorded.joinPosition() + 64, 0L);
        IllegalStateException failure = assertThrows(IllegalStateException.class, agent::onStart);
        assertTrue(failure.getMessage().contains("commands would be missed"), failure.getMessage());
    }

    @Test
    void aDeadGateIsFatalWhenIdle() {
        HeldGate gate = new HeldGate(recorded.joinPosition());
        EngineAgent agent = agent(gate, capture);
        assertEquals(0, agent.doWork());
        gate.live = false;
        AgentTerminationException failure = assertThrows(AgentTerminationException.class, agent::doWork);
        assertTrue(failure.getCause().getMessage().contains("no longer active"), failure.getCause().getMessage());
    }

    @Test
    void theRealGateIsLiveWhileRecording() {
        assertTrue(realGate.isLive());
        assertEquals(recorded.recordingId(), realGate.recordingId());
        assertEquals(recorded.joinPosition(), realGate.limit());
    }

    private static AgentTerminationException workUntilFailure(EngineAgent agent) {
        AgentTerminationException[] caught = new AgentTerminationException[1];
        await(() -> {
            try {
                agent.doWork();
                return false;
            } catch (AgentTerminationException e) {
                caught[0] = e;
                return true;
            }
        }, "the agent to fail");
        return caught[0];
    }
}
