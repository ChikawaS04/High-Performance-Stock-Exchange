package journal;

import event.ExecutionEvent;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import static journal.JournalTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The outbound recorder's per-event path (gap check, encode, offer, back-pressure retry)
 * allocates nothing (SRS §5.1). Same method and slack as {@link SequencerAllocationTest}.
 */
class OutboundRecorderAllocationTest {

    private static final int WARMUP = 50_000;
    private static final int EVENTS = 100_000;
    private static final long SLACK_BYTES = 1_024;

    @TempDir
    Path root;

    @Test
    void steadyStateRecordingAllocatesNothing() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "per-thread allocation counter not available");
        com.sun.management.ThreadMXBean counter = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(counter.isThreadAllocatedMemorySupported(), "per-thread allocation counter not supported");
        counter.setThreadAllocatedMemoryEnabled(true);

        // 256 KiB terms and 16 MiB segments: 150,000 events of 128 bytes fit in two segments.
        try (Journal journal = Journal.launch(config(root.resolve("data"), root.resolve("aeron"),
                256 * 1024, 16 * 1024 * 1024))) {
            RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
            OutboundRecorder recorder = OutboundRecorder.open(journal, recorded, new BackoffIdleStrategy(), null);
            ExecutionEvent slot = OutboundRecordings.fill(1);

            run(recorder, slot, 0, WARMUP);
            long before = counter.getCurrentThreadAllocatedBytes();
            run(recorder, slot, WARMUP, EVENTS);
            long allocated = counter.getCurrentThreadAllocatedBytes() - before;

            assertEquals(WARMUP + EVENTS, recorder.recordedSeq());
            assertTrue(allocated < SLACK_BYTES, "recorder allocated " + allocated + " bytes over " + EVENTS + " events");
        }
    }

    private static void run(OutboundRecorder recorder, ExecutionEvent slot, int from, int count) {
        for (int i = from; i < from + count; i++) {
            slot.seq = i + 1;
            slot.orderId = i + 1;
            recorder.onEvent(slot, i, true);
        }
    }
}
