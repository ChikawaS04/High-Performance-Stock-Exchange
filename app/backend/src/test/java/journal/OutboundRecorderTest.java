package journal;

import event.CapturingExecutionHandler.Observed;
import event.ExecutionEvent;
import event.ExecutionEventType;
import io.aeron.Subscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static journal.JournalTestSupport.await;
import static journal.JournalTestSupport.config;
import static journal.OutboundRecordings.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The outbound recorder (P15-6) against a real embedded journal: every field of every event type
 * recorded, seq gap-free with gaps and repeats refused before any offer, a fatal offer that
 * commits nothing, continuation from the extended recording (D10), and back-pressure that never
 * drops or reorders an event.
 */
class OutboundRecorderTest {

    @TempDir
    Path root;

    private static OutboundRecorder open(Journal journal) {
        RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
        return OutboundRecorder.open(journal, recorded, new SequencerTest.CountingIdle(), null);
    }

    @Test
    void everyEventTypeIsRecordedWithEveryField() {
        List<ExecutionEvent> events = new ArrayList<>();
        long seq = 1;
        for (ExecutionEventType type : ExecutionEventType.values()) {
            events.add(report(seq++, type, 40 + seq));
        }
        events.add(fill(seq++));
        events.add(fill(seq));

        try (Journal journal = Journal.launch(config(root))) {
            OutboundRecorder recorder = open(journal);
            assertEquals(0, recorder.lastSeqAtOpen());
            assertNull(recorder.lastRecordedAtOpen());
            for (ExecutionEvent e : events) recorder.onEvent(e, e.seq - 1, true);
            assertEquals(events.size(), recorder.recordedSeq());

            List<Observed> recorded = readAll(journal, events.size());
            for (int i = 0; i < events.size(); i++) {
                assertEquals(observe(events.get(i)), recorded.get(i), "event " + (i + 1));
            }
        }
    }

    @Test
    void aGapOrARepeatIsFatalBeforeAnythingIsOffered() {
        try (Journal journal = Journal.launch(config(root))) {
            OutboundRecorder recorder = open(journal);

            IllegalStateException gap = assertThrows(IllegalStateException.class, () -> recorder.onEvent(fill(2), 0, true));
            assertTrue(gap.getMessage().contains("Outbound seq 2 after recorded seq 0"), gap.getMessage());
            assertEquals(0, recorder.recordedSeq());

            recorder.onEvent(fill(1), 0, true);
            IllegalStateException repeat = assertThrows(IllegalStateException.class, () -> recorder.onEvent(fill(1), 1, true));
            assertTrue(repeat.getMessage().contains("Outbound seq 1 after recorded seq 1"), repeat.getMessage());
            assertEquals(1, recorder.recordedSeq());

            List<Observed> recorded = readAll(journal, 1);
            assertEquals(observe(fill(1)), recorded.get(0), "only the in-sequence event was recorded");
        }
    }

    @Test
    void aFatalOfferThrowsWithoutCommittingTheSeq() {
        try (Journal journal = Journal.launch(config(root))) {
            RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
            OutboundRecorder recorder = new OutboundRecorder(recorded.publication(), 0L, null,
                    new SequencerTest.CountingIdle(), null);
            recorded.publication().close();

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> recorder.onEvent(fill(1), 0L, true));
            assertTrue(failure.getMessage().contains("seq=1"), failure.getMessage());
            assertEquals(0L, recorder.recordedSeq());
            // The seq is not committed: the same event is still the one expected next.
            assertThrows(IllegalStateException.class, () -> recorder.onEvent(fill(2), 1L, true));
        }
    }

    @Test
    void aRestartContinuesFromTheExtendedRecording() {
        int first = 1_000;   // about 120 KB: the tail of run 1 sits beyond its first 64 KiB term
        int second = 300;
        long recordingId;
        try (Journal journal = Journal.launch(config(root))) {
            OutboundRecorder recorder = open(journal);
            for (int i = 1; i <= first; i++) recorder.onEvent(fill(i), i - 1, true);
            readAll(journal, first);
            recordingId = journal.findRecording(Journal.OUTBOUND_STREAM_ID).recordingId();
        }
        try (Journal journal = Journal.launch(config(root))) {
            OutboundRecorder recorder = open(journal);
            assertEquals(first, recorder.lastSeqAtOpen(), "seq continues from the recording's tail");
            assertEquals(observe(fill(first)), observe(recorder.lastRecordedAtOpen()), "last event read back whole");
            for (int i = first + 1; i <= first + second; i++) recorder.onEvent(fill(i), i - 1, true);
            readAll(journal, first + second);
        }
        try (Journal journal = Journal.launch(config(root))) {
            assertEquals(1, journal.findRecordings(Journal.OUTBOUND_STREAM_ID).size(), "extended, not duplicated");
            assertEquals(recordingId, journal.findRecording(Journal.OUTBOUND_STREAM_ID).recordingId());
            List<Observed> all = readAll(journal, first + second);
            assertGapFreeFromOne(all);
            for (int i = 0; i < all.size(); i++) assertEquals(observe(fill(i + 1)), all.get(i));
        }
    }

    @Test
    void backPressureNeverDropsOrReordersAnEvent() throws InterruptedException {
        int count = 3_000;   // about 360 KB: far beyond the 64 KiB term window an unpolled subscriber allows
        try (Journal journal = Journal.launch(config(root))) {
            SequencerTest.CountingIdle idle = new SequencerTest.CountingIdle();
            RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
            OutboundRecorder recorder = OutboundRecorder.open(journal, recorded, idle, null);

            Subscription slow = journal.archive().context().aeron()
                    .addSubscription(Journal.IPC_CHANNEL, Journal.OUTBOUND_STREAM_ID);
            await(() -> slow.imageCount() > 0, "slow subscriber connected");

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    for (int i = 1; i <= count; i++) recorder.onEvent(fill(i), i - 1, true);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-recorder");
            producer.start();
            await(() -> idle.idles.get() > 0, "recorder back-pressured");
            assertTrue(recorder.recordedSeq() < count, "the slow subscriber holds the stream back");

            AtomicBoolean draining = new AtomicBoolean(true);
            AtomicLong drained = new AtomicLong();
            Thread drainer = new Thread(() -> {
                while (draining.get()) {
                    if (slow.poll((b, o, l, h) -> drained.incrementAndGet(), 256) == 0) LockSupport.parkNanos(100_000L);
                }
            }, "test-drainer");
            drainer.start();
            try {
                producer.join(TimeUnit.SECONDS.toMillis(30));
                assertFalse(producer.isAlive(), "recorder finished once the stream drained");
                assertNull(failure.get(), "recorder never failed under back-pressure");
                List<Observed> all = readAll(journal, count);
                assertGapFreeFromOne(all);
                for (int i = 0; i < count; i++) assertEquals(1_000 + i + 1, all.get(i).orderId(), "payload order");
                await(() -> drained.get() == count, "slow subscriber saw every event");
            } finally {
                draining.set(false);
                drainer.join(TimeUnit.SECONDS.toMillis(10));
                slow.close();
            }
        }
    }
}
