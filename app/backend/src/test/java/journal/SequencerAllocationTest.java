package journal;

import event.OrderEvent;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import static journal.JournalTestSupport.config;
import static journal.JournalTestSupport.limit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sequencer's per-command path (seq, clamp, encode, offer, back-pressure retry) allocates
 * nothing (SRS §5.1). The test thread calls onEvent directly against a live journal, so the
 * thread's allocated-byte counter sees exactly that path. Same method and slack as
 * CodecAllocationTest: one 16-byte allocation per command would show as megabytes.
 */
class SequencerAllocationTest {

    private static final int WARMUP = 50_000;
    private static final int COMMANDS = 100_000;
    private static final long SLACK_BYTES = 1_024;

    @TempDir
    Path root;

    @Test
    void steadyStateSequencingAllocatesNothing() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "per-thread allocation counter not available");
        com.sun.management.ThreadMXBean counter = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(counter.isThreadAllocatedMemorySupported(), "per-thread allocation counter not supported");
        counter.setThreadAllocatedMemoryEnabled(true);

        // 256 KiB terms and 16 MiB segments: 150,000 commands of 96 bytes fit in one segment.
        try (Journal journal = Journal.launch(config(root.resolve("data"), root.resolve("aeron"),
                256 * 1024, 16 * 1024 * 1024))) {
            Sequencer sequencer = Sequencer.open(journal, new BackoffIdleStrategy());
            OrderEvent slot = limit(0L, 0L);

            run(sequencer, slot, 0, WARMUP);
            long before = counter.getCurrentThreadAllocatedBytes();
            run(sequencer, slot, WARMUP, COMMANDS);
            long allocated = counter.getCurrentThreadAllocatedBytes() - before;

            assertEquals(WARMUP + COMMANDS, sequencer.lastSeq());
            assertTrue(allocated < SLACK_BYTES,
                    "sequencer allocated " + allocated + " bytes over " + COMMANDS + " commands");
        }
    }

    private static void run(Sequencer sequencer, OrderEvent slot, int from, int count) {
        for (int i = from; i < from + count; i++) {
            slot.orderId = i + 1;
            slot.timestamp = (i & 1) == 0 ? i : i - 2;   // every other command exercises the clamp
            sequencer.onEvent(slot, i, true);
        }
    }
}
