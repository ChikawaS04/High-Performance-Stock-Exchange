package recovery;

import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import event.OutboundPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * When snapshots are taken (P15-5): every interval, on request, deferred while the writer is
 * busy, and never stopped by a failure. The engine-thread side is driven directly here; the
 * pipeline-level proof that a stalled write never blocks matching is in
 * {@code integration.RecoveryTest}.
 */
class SnapshotSchedulerTest {

    @TempDir
    Path dir;

    private final MatchingEngine engine = SnapshotCodecTest.twoSidedBook();
    private final MatchingEngineHandler handler =
            new MatchingEngineHandler(engine, new OutboundPipeline().getRingBuffer());
    private final List<Long> written = Collections.synchronizedList(new ArrayList<>());
    private SnapshotWriter writer;

    @AfterEach
    void close() {
        if (writer != null) writer.close();
    }

    private SnapshotScheduler scheduler(SnapshotSink sink, long interval) {
        writer = new SnapshotWriter(sink);
        return new SnapshotScheduler(engine, handler, writer, new SnapshotStore(dir), interval, 0L);
    }

    private SnapshotSink recording() {
        return (body, length, seq) -> written.add(seq);
    }

    @Test
    void aSnapshotIsTakenEveryInterval() {
        SnapshotScheduler scheduler = scheduler(recording(), 5);
        for (long seq = 1; seq <= 12; seq++) {
            scheduler.onApplied(seq, seq * 64);
            await(writer::isIdle, "writer idle");
        }
        assertEquals(List.of(5L, 10L), written);
    }

    @Test
    void aRequestSnapshotsAfterTheCurrentCommand() {
        SnapshotScheduler scheduler = scheduler(recording(), 1_000);
        scheduler.onApplied(1, 64);
        scheduler.requestSnapshot();   // the session-close hook, called while command 2 is applied
        scheduler.onApplied(2, 128);
        await(() -> written.size() == 1, "requested snapshot written");
        assertEquals(List.of(2L), written);
    }

    @Test
    void aBusyWriterDefersTheSnapshotWithoutWaiting() {
        Object gate = new Object();
        boolean[] release = {false};
        SnapshotSink stalled = (body, length, seq) -> {
            written.add(seq);
            synchronized (gate) {
                while (!release[0]) {
                    try { gate.wait(); } catch (InterruptedException e) { return; }
                }
            }
        };
        SnapshotScheduler scheduler = scheduler(stalled, 2);
        scheduler.onApplied(1, 64);
        scheduler.onApplied(2, 128);                       // trigger: handed to the writer, which stalls
        await(() -> written.size() == 1, "first write started");
        for (long seq = 3; seq <= 9; seq++) {
            scheduler.onApplied(seq, seq * 64);            // returns at once although the writer is stuck
        }
        assertEquals(List.of(2L), written, "nothing else handed over while busy");
        synchronized (gate) { release[0] = true; gate.notifyAll(); }
        await(writer::isIdle, "writer idle");
        scheduler.onApplied(10, 640);                      // first command after idle takes the pending snapshot
        await(() -> written.size() == 2, "deferred snapshot written");
        assertEquals(List.of(2L, 10L), written);
    }

    @Test
    void aFailedWriteDoesNotStopLaterSnapshots() {
        int[] calls = {0};
        SnapshotSink failsOnce = (body, length, seq) -> {
            if (calls[0]++ == 0) throw new IOException("disk full");
            written.add(seq);
        };
        SnapshotScheduler scheduler = scheduler(failsOnce, 1);
        scheduler.onApplied(1, 64);
        await(writer::isIdle, "writer idle");
        scheduler.onApplied(2, 128);
        await(() -> written.size() == 1, "second snapshot written");
        await(writer::isIdle, "writer idle");
        assertEquals(List.of(2L), written);
        assertEquals(1L, writer.writtenCount());
    }

    @Test
    void theFinalSnapshotIsWrittenOnTheCallerAndSkippedWhenAlreadyPresent() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        writer = new SnapshotWriter(store);
        SnapshotScheduler scheduler = new SnapshotScheduler(engine, handler, writer, store, 1_000, 0L);
        assertTrue(scheduler.writeFinal(0, 0, 1_000), "nothing applied, nothing to write");
        assertTrue(scheduler.writeFinal(7, 448, 1_000));
        assertEquals(7L, store.loadNewestValid().inboundSeq());
        assertEquals(448L, store.loadNewestValid().inboundPosition());
        assertTrue(scheduler.writeFinal(7, 448, 1_000), "already present");
        assertEquals(0L, writer.writtenCount(), "the final snapshot does not go through the writer thread");

        // The final snapshot goes to the directory even when periodic ones go to another sink.
        writer.close();
        writer = new SnapshotWriter(recording());
        SnapshotScheduler elsewhere = new SnapshotScheduler(engine, handler, writer, store, 1_000, 0L);
        assertTrue(elsewhere.writeFinal(8, 512, 1_000));
        assertTrue(store.exists(8));
        assertTrue(written.isEmpty());
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
