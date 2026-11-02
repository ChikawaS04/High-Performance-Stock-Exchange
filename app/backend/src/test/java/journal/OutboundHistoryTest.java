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

    @Test
    void returnsExactlyTheRecordedEventsFromFromSeq() {
        try (Fixture f = fixture()) {
            f.record(1, 700);   // several 64 KiB terms
            readAll(f.journal(), 700);
            Collecting visitor = new Collecting(Integer.MAX_VALUE);
            ExecutionHistory.Result result = f.history().replay(42, f.history().publishedSeq(), visitor);

            assertEquals(new ExecutionHistory.Result(700, 659, false), result);
            for (int i = 0; i < visitor.seen.size(); i++) assertEquals(observe(fill(42 + i)), visitor.seen.get(i));
        }
    }

    @Test
    void outOfRangeFromSeqReplaysWhatExists() {
        try (Fixture f = fixture()) {
            f.record(1, 20);
            readAll(f.journal(), 20);

            Collecting fromStart = new Collecting(Integer.MAX_VALUE);
            assertEquals(new ExecutionHistory.Result(20, 20, false), f.history().replay(1, 20, fromStart));
            assertEquals(1, fromStart.seen.get(0).seq());

            Collecting beyond = new Collecting(Integer.MAX_VALUE);
            assertEquals(new ExecutionHistory.Result(20, 0, false), f.history().replay(21, 20, beyond));
            assertTrue(beyond.seen.isEmpty());
        }
    }

    @Test
    void anEmptyHistoryAnswersAtOnce() {
        try (Fixture f = fixture()) {
            Collecting visitor = new Collecting(Integer.MAX_VALUE);
            assertEquals(new ExecutionHistory.Result(0, 0, false), f.history().replay(1, 0, visitor));
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

            assertEquals(new ExecutionHistory.Result(15, 11, false), result);
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
            assertEquals(new ExecutionHistory.Result(50, 41, false), f.history().replay(10, 50, again));
        }
    }
}
