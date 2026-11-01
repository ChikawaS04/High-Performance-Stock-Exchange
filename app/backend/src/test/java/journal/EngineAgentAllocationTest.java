package journal;

import engine.CommandHandler;
import engine.EngineAgent;
import event.OrderEvent;
import io.aeron.Image;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import static journal.JournalTestSupport.cancel;
import static journal.JournalTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The engine agent's per-command path (gate read, bounded poll, decode into the reused carrier,
 * seq check, handler dispatch, applied-seq store) allocates nothing (SRS §5.1). The handler is a
 * no-op, so the measurement is exactly what P15-4 adds between the journal and the engine; the
 * engine's own allocation is measured by the engine benchmarks, not here. The test thread offers
 * and then runs doWork itself; only the doWork calls are counted. Same method and slack as
 * SequencerAllocationTest.
 */
class EngineAgentAllocationTest {

    private static final int WARMUP = 50_000;
    private static final int COMMANDS = 100_000;
    private static final int BATCH = 100;   // well inside the publication window
    private static final long SLACK_BYTES = 1_024;

    @TempDir
    Path root;

    @Test
    void steadyStateApplyingAllocatesNothing() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "per-thread allocation counter not available");
        com.sun.management.ThreadMXBean counter = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(counter.isThreadAllocatedMemorySupported(), "per-thread allocation counter not supported");
        counter.setThreadAllocatedMemoryEnabled(true);

        try (Journal journal = Journal.launch(config(root.resolve("data"), root.resolve("aeron"),
                256 * 1024, 16 * 1024 * 1024))) {
            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            Sequencer sequencer = Sequencer.open(journal, recorded, new BackoffIdleStrategy());
            Image image = journal.subscribe(recorded);
            RecordingPositionGate gate = journal.recordingPositionGate(recorded);

            CommandHandler noOp = command -> { };
            EngineAgent agent = new EngineAgent(image, gate, noOp, recorded.joinPosition(), 0L);
            agent.onStart();

            OrderEvent slot = cancel(1L, 999_999_999L, 0L);
            long[] allocated = {0L};
            run(sequencer, agent, slot, 0, WARMUP, counter, null);
            run(sequencer, agent, slot, WARMUP, COMMANDS, counter, allocated);

            assertEquals(WARMUP + COMMANDS, agent.appliedSeq());
            assertTrue(allocated[0] < SLACK_BYTES,
                    "engine agent allocated " + allocated[0] + " bytes over " + COMMANDS + " commands");
        }
    }

    /** Offers in batches and drains each batch through doWork, counting only doWork's allocation. */
    private static void run(Sequencer sequencer, EngineAgent agent, OrderEvent slot, int from, int count,
                            com.sun.management.ThreadMXBean counter, long[] allocated) {
        for (int i = from; i < from + count; i += BATCH) {
            for (int j = i; j < i + BATCH; j++) {
                slot.orderId = j + 1;
                slot.timestamp = j;
                sequencer.onEvent(slot, j, true);
            }
            long target = i + BATCH;
            long deadline = System.currentTimeMillis() + JournalTestSupport.TIMEOUT_MS;
            long before = counter.getCurrentThreadAllocatedBytes();
            while (agent.appliedSeq() < target) {
                agent.doWork();
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("agent stuck at " + agent.appliedSeq() + ", expected " + target);
                }
            }
            if (allocated != null) allocated[0] += counter.getCurrentThreadAllocatedBytes() - before;
        }
    }
}
