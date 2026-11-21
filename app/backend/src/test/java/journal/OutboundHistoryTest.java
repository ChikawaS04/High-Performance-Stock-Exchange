package journal;

import event.CapturingExecutionHandler.Observed;
import event.ExecutionEvent;
import event.ExecutionHistory;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static journal.JournalTestSupport.config;
import static journal.OutboundRecordings.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the outbound recording back for RESEND (D16): exactly the recorded events from
 * {@code fromSeq}, out-of-range bounds, waiting for an event published but not yet recorded, and a
 * visitor that stops the replay.
 */
class OutboundHistoryTest {

    @TempDir
    Path root;

    /** A journal with an outbound recorder and the history over it. */
    private record Fixture(Journal journal, OutboundRecorder recorder, OutboundHistory history, AtomicLong published)
            implements AutoCloseable {
        void record(int fromSeq, int toSeq) {
            for (int i = fromSeq; i <= toSeq; i++) {
                recorder.onEvent(fill(i), i - 1, true);
                published.set(i);
            }
        }

        @Override
        public void close() {
            journal.close();
        }
    }

    private Fixture fixture() {
        Journal journal = Journal.launch(config(root));
        RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
        OutboundRecorder recorder = OutboundRecorder.open(journal, recorded, new BackoffIdleStrategy(), null);
        AtomicLong published = new AtomicLong();
        OutboundHistory history = new OutboundHistory(journal.archive(), recorded,
                journal.recordingPositionGate(recorded), published::get);
        return new Fixture(journal, recorder, history, published);
    }

    private static final class Collecting implements ExecutionHistory.Visitor {
        final List<Observed> seen = new ArrayList<>();
        final int stopAfter;

        Collecting(int stopAfter) {
            this.stopAfter = stopAfter;
        }

        @Override
        public boolean onEvent(ExecutionEvent event) {
            seen.add(observe(event));
            return seen.size() < stopAfter;
        }
    }

    /** The seq and count fields of a result (the read statistics are checked on their own). */
    private static void assertRead(long lastSeq, long count, boolean stopped, long firstSeq,
                                   ExecutionHistory.Result result) {
        assertEquals(lastSeq, result.lastSeq(), "lastSeq");
        assertEquals(count, result.count(), "count");
        assertEquals(stopped, result.stopped(), "stopped");
        assertEquals(firstSeq, result.firstSeq(), "firstSeq");
    }

    private static long recordedPosition(Fixture f) {
        long recordingId = f.journal().findRecording(Journal.OUTBOUND_STREAM_ID).recordingId();
        return f.journal().archive().getRecordingPosition(recordingId);
    }

    /**
     * P16-12: the linear read goes through the recording from its start whatever {@code fromSeq} is, and
     * the result says how far: every event read (sent or skipped) and the bytes from the start position.
     */
    @Test
    void reportsEventsAndBytesReadFromTheStart() {
        try (Fixture f = fixture()) {
            f.record(1, 700);
            readAll(f.journal(), 700);
            ExecutionHistory.Result tail = f.history().replay(691, 700, new Collecting(Integer.MAX_VALUE));

            assertRead(700, 10, false, 1, tail);
            assertEquals(700, tail.scanned(), "a request for the last 10 still reads all 700");
            assertEquals(0, tail.startPosition());
            assertEquals(recordedPosition(f), tail.bytesRead(), "read to the recorded end");

            ExecutionHistory.Result empty = new ExecutionHistory.Result(0, 0, false);
            assertEquals(-1, empty.startPosition(), "the short constructors report no read");
            assertEquals(0, empty.scanned());
        }
    }

    @Test
    void anEmptyHistoryReadsNothing() {
        try (Fixture f = fixture()) {
            ExecutionHistory.Result result = f.history().replay(1, 0, new Collecting(Integer.MAX_VALUE));
            assertEquals(0, result.scanned());
            assertEquals(0, result.bytesRead());
            assertEquals(0, result.startPosition());
        }
    }

    @Test
    void returnsExactlyTheRecordedEventsFromFromSeq() {
        try (Fixture f = fixture()) {
            f.record(1, 700);   // several 64 KiB terms
            readAll(f.journal(), 700);
            Collecting visitor = new Collecting(Integer.MAX_VALUE);
            ExecutionHistory.Result result = f.history().replay(42, f.history().publishedSeq(), visitor);

            assertRead(700, 659, false, 1, result);
            for (int i = 0; i < visitor.seen.size(); i++) assertEquals(observe(fill(42 + i)), visitor.seen.get(i));
        }
    }

    @Test
    void outOfRangeFromSeqReplaysWhatExists() {
        try (Fixture f = fixture()) {
            f.record(1, 20);
            readAll(f.journal(), 20);

            Collecting fromStart = new Collecting(Integer.MAX_VALUE);
            assertRead(20, 20, false, 1, f.history().replay(1, 20, fromStart));
            assertEquals(1, fromStart.seen.get(0).seq());

            Collecting beyond = new Collecting(Integer.MAX_VALUE);
            assertRead(20, 0, false, 1, f.history().replay(21, 20, beyond));
            assertTrue(beyond.seen.isEmpty());
        }
    }

    @Test
    void anEmptyHistoryAnswersAtOnce() {
        try (Fixture f = fixture()) {
            Collecting visitor = new Collecting(Integer.MAX_VALUE);
            assertRead(0, 0, false, 0, f.history().replay(1, 0, visitor));
            assertTrue(visitor.seen.isEmpty());
        }
    }

    @Test
    void waitsForAnEventPublishedButNotYetRecorded() throws InterruptedException {
        try (Fixture f = fixture()) {
            f.record(1, 10);
            readAll(f.journal(), 10);
            // Seq 15 counts as broadcast already; the recorder writes 11 to 15 only after the replay starts.
            Thread late = new Thread(() -> {
                java.util.concurrent.locks.LockSupport.parkNanos(200_000_000L);
                f.record(11, 15);
            }, "test-late-recorder");
            late.start();
            Collecting visitor = new Collecting(Integer.MAX_VALUE);
            ExecutionHistory.Result result = f.history().replay(5, 15, visitor);
            late.join(10_000);

            assertRead(15, 11, false, 1, result);
            for (int i = 0; i < visitor.seen.size(); i++) assertEquals(5 + i, visitor.seen.get(i).seq());
        }
    }

    @Test
    void aVisitorCanStopTheReplay() {
        try (Fixture f = fixture()) {
            f.record(1, 50);
            readAll(f.journal(), 50);
            Collecting visitor = new Collecting(3);
            ExecutionHistory.Result result = f.history().replay(10, 50, visitor);

            assertTrue(result.stopped());
            assertEquals(2, result.count(), "the event that stopped it is not counted as sent");
            assertEquals(3, visitor.seen.size());
            // The next replay is unaffected.
            Collecting again = new Collecting(Integer.MAX_VALUE);
            assertRead(50, 41, false, 1, f.history().replay(10, 50, again));
        }
    }

    /**
     * P16-7, R5: the start is read for every request, so after a purge moved it the replay begins at
     * the new start, and the first event there is whole: its seq is the earliest the history holds.
     */
    @Test
    void afterAPurgeTheReplayStartsAtTheNewStartWithTheEarliestEvent() {
        try (Fixture f = fixture()) {
            f.record(1, 1500);
            readAll(f.journal(), 1500);
            long recordingId = f.journal().findRecording(Journal.OUTBOUND_STREAM_ID).recordingId();
            f.journal().archive().purgeSegments(recordingId, 2L * JournalTestSupport.SMALL);

            Collecting visitor = new Collecting(Integer.MAX_VALUE);
            ExecutionHistory.Result result = f.history().replay(1, 1500, visitor);
            assertTrue(result.firstSeq() > 1, "the oldest events are gone");
            assertEquals(1500, result.lastSeq());
            assertEquals(1500 - result.firstSeq() + 1, result.count());
            assertEquals(2L * JournalTestSupport.SMALL, result.startPosition(), "read from the new start");
            assertEquals(result.count(), result.scanned(), "nothing below the start is read");
            assertEquals(recordedPosition(f) - result.startPosition(), result.bytesRead());
            for (int i = 0; i < visitor.seen.size(); i++) {
                assertEquals(observe(fill(result.firstSeq() + i)), visitor.seen.get(i));
            }
            // A request inside the retained history is unaffected.
            Collecting tail = new Collecting(Integer.MAX_VALUE);
            ExecutionHistory.Result fromInside = f.history().replay(1400, 1500, tail);
            assertEquals(101, fromInside.count());
            assertEquals(result.firstSeq(), fromInside.firstSeq());
        }
    }
}
