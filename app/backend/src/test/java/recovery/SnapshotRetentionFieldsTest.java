package recovery;

import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import engine.Sessions;
import event.OutboundPipeline;
import org.agrona.ExpandableDirectByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snapshot schema version 2 and the store's close snapshots (P16-7, R3): the retention fields round
 * trip, version 1 files still load, close snapshots are recognised and kept beside the newest few, a
 * valid file is never rewritten, a snapshot below the journal's start is skipped, and the scheduler
 * marks close snapshots (deferred or at shutdown) and writes -1 for the outbound position during
 * replay.
 */
class SnapshotRetentionFieldsTest {

    private static final int DAY = 20261102;

    @TempDir
    Path dir;

    private final MatchingEngine engine = SnapshotCodecTest.twoSidedBook();
    private SnapshotWriter writer;

    @AfterEach
    void close() {
        if (writer != null) writer.close();
    }

    private void write(SnapshotStore store, long seq, int closeDate) throws IOException {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(256);
        int length = new SnapshotCodec().encode(buffer, engine, seq, seq * 64, 0L, seq, seq, false, -1,
                closeDate > 0 ? closeDate : DAY, Sessions.symbol(), 1L, seq * 32, closeDate);
        store.write(buffer, length, seq);
    }

    private Path file(long seq) {
        return dir.resolve(SnapshotStore.fileName(seq));
    }

    @Test
    void theRetentionFieldsRoundTrip() throws Exception {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(256);
        int length = new SnapshotCodec().encode(buffer, engine, 42L, 4_096L, 7L, 99L, 1L, false, -1, DAY,
                Sessions.symbol(), 8L, 12_345L, DAY);
        RestoredSnapshot s = SnapshotCodec.decode(buffer, length);
        assertEquals(2, s.schemaVersion());
        assertEquals(8L, s.outboundRecordingId());
        assertEquals(12_345L, s.outboundPosition());
        assertEquals(DAY, s.closeDate());
        assertTrue(s.isCloseSnapshot());
        assertEquals(DAY, SnapshotCodec.peekCloseDate(buffer, length));

        length = new SnapshotCodec().encode(buffer, engine, 42L, 4_096L, 7L, 99L, 1L);   // the older form
        s = SnapshotCodec.decode(buffer, length);
        assertEquals(-1L, s.outboundPosition());
        assertEquals(-1, s.closeDate());
        assertFalse(s.isCloseSnapshot());
        assertEquals(-1, SnapshotCodec.peekCloseDate(buffer, length));
    }

    @Test
    void anInvalidCloseDateIsRejected() {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(256);
        int length = new SnapshotCodec().encode(buffer, engine, 42L, 4_096L, 7L, 99L, 1L, false, -1, DAY,
                Sessions.symbol(), 8L, 12_345L, 20261399);
        InvalidSnapshotException e = assertThrows(InvalidSnapshotException.class, () -> SnapshotCodec.decode(buffer, length));
        assertTrue(e.getMessage().contains("close date"), e.getMessage());
    }

    @Test
    void aVersion1FileStillLoadsWithTheNewFieldsUnknown() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        write(store, 10, DAY);
        RestoredSnapshot v2 = store.loadNewestValid();
        Files.write(file(10), SnapshotFiles.asVersion1(Files.readAllBytes(file(10))));
        RestoredSnapshot v1 = store.loadNewestValid();
        assertNotNull(v1, "a version 1 file is valid");
        assertEquals(1, v1.schemaVersion());
        assertEquals(-1L, v1.outboundPosition());
        assertEquals(-1L, v1.outboundRecordingId());
        assertEquals(-1, v1.closeDate());
        assertEquals(v2.inboundSeq(), v1.inboundSeq());
        assertEquals(v2.inboundPosition(), v1.inboundPosition());
        assertEquals(v2.orders().size(), v1.orders().size());
        assertEquals(v2.lastClosedBusinessDate(), v1.lastClosedBusinessDate());
        assertEquals(-1, SnapshotStore.peekCloseDate(file(10)), "a version 1 file is never a close snapshot");
    }

    @Test
    void pruningKeepsTheNewestThreeAndTheWindowsCloseSnapshots() throws IOException {
        SnapshotStore store = new SnapshotStore(dir, 3, 2);
        write(store, 10, DAY);         // close of day 1
        write(store, 15, DAY);         // the same date again (deferred or replayed): not the close snapshot
        write(store, 20, DAY + 1);     // close of day 2
        write(store, 30, DAY + 2);     // close of day 3
        for (long seq = 31; seq <= 36; seq++) write(store, seq, -1);
        assertFalse(Files.exists(file(10)), "day 1 is outside the two newest closes");
        assertFalse(Files.exists(file(15)));
        assertTrue(Files.exists(file(20)), "day 2's close snapshot kept");
        assertTrue(Files.exists(file(30)), "day 3's close snapshot kept");
        assertTrue(Files.exists(file(34)) && Files.exists(file(35)) && Files.exists(file(36)), "the newest three");
        assertFalse(Files.exists(file(31)) || Files.exists(file(32)) || Files.exists(file(33)));

        SnapshotStore plain = new SnapshotStore(dir);   // no close snapshots kept: as before P16-7
        write(plain, 37, -1);
        assertFalse(Files.exists(file(20)) || Files.exists(file(30)));
    }

    @Test
    void aValidFileOfTheSameSeqIsNotRewritten() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        write(store, 10, DAY);
        byte[] first = Files.readAllBytes(file(10));
        write(store, 10, -1);   // as a replay passing seq 10 again
        assertArrayEquals(first, Files.readAllBytes(file(10)), "the live file, with its close date, is kept");

        Files.write(file(10), new byte[] {1, 2, 3});   // a torn file is replaced
        write(store, 10, -1);
        assertEquals(-1, store.loadNewestValid().closeDate());
    }

    @Test
    void aSnapshotBelowTheJournalsStartIsSkipped() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        write(store, 10, -1);   // position 640
        write(store, 20, -1);   // position 1,280
        assertEquals(20L, store.loadNewestValid(Long.MAX_VALUE, 1_000L).inboundSeq());
        assertEquals(10L, store.loadNewestValid(15L, 0L).inboundSeq(), "rule 3 alone");
        assertNull(store.loadNewestValid(15L, 1_000L), "rule 3 leaves only one below the start");
    }

    // --- the scheduler ---

    private final MatchingEngineHandler handler =
            new MatchingEngineHandler(engine, new OutboundPipeline().getRingBuffer());

    @Test
    void aCloseSnapshotIsMarkedWithItsDateEvenWhenDeferredAndWakesTheListener() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        Object gate = new Object();
        boolean[] holding = {true};
        SnapshotSink slow = (body, length, seq) -> {
            synchronized (gate) {
                while (holding[0]) {
                    try {
                        gate.wait(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            store.write(body, length, seq);
        };
        writer = new SnapshotWriter(slow);
        AtomicInteger closes = new AtomicInteger();
        writer.onCloseSnapshot(closes::incrementAndGet);
        AtomicLong recorded = new AtomicLong(5_000L);
        SnapshotScheduler scheduler = new SnapshotScheduler(engine, handler, writer, store, 1_000, 0L, 1L,
                recorded::get);

        handler.restoreSession(true, DAY, -1, Sessions.symbol());
        scheduler.requestSnapshot();       // an ordinary request: no close date
        scheduler.onApplied(1, 64);        // the writer is now held
        handler.restoreSession(false, -1, DAY, Sessions.symbol());
        scheduler.requestSnapshot();       // the close hook
        scheduler.onApplied(2, 128);       // writer busy: deferred
        handler.restoreSession(true, DAY + 1, DAY, Sessions.symbol());   // the next day opens meanwhile
        scheduler.onApplied(3, 192);       // still busy
        synchronized (gate) {
            holding[0] = false;
            gate.notifyAll();
        }
        await(writer::isIdle, "the first write");
        scheduler.onApplied(4, 256);       // the deferred close snapshot, at seq 4
        await(() -> closes.get() == 1, "the close listener");
        RestoredSnapshot s = store.loadNewestValid();
        assertEquals(4L, s.inboundSeq());
        assertEquals(DAY, s.closeDate(), "the closed date, though the next day is open by now");
        assertEquals(5_000L, s.outboundPosition());
        assertEquals(1L, s.outboundRecordingId());
    }

    @Test
    void duringReplayTheOutboundPositionIsUnknown() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        writer = new SnapshotWriter(store);
        SnapshotScheduler scheduler = new SnapshotScheduler(engine, handler, writer, store, 1, 0L, 1L, () -> 9_999L);
        handler.beginReplay(Long.MAX_VALUE);
        scheduler.onApplied(1, 64);
        await(() -> store.exists(1), "the replay-time snapshot");
        assertEquals(-1L, store.loadNewestValid().outboundPosition());
        handler.endReplay(false);
        scheduler.onApplied(2, 128);
        await(() -> store.exists(2), "the live snapshot");
        assertEquals(9_999L, store.loadNewestValid().outboundPosition());
    }

    @Test
    void aShutdownSnapshotCarriesAStillPendingClose() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        writer = new SnapshotWriter(store);
        SnapshotScheduler scheduler = new SnapshotScheduler(engine, handler, writer, store, 1_000, 0L, 1L, () -> 64L);
        handler.restoreSession(false, -1, DAY, Sessions.symbol());
        scheduler.requestSnapshot();   // the close hook; the engine stops before the next onApplied
        assertTrue(scheduler.writeFinal(7, 448, 5_000));
        assertEquals(DAY, store.loadNewestValid().closeDate());
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
