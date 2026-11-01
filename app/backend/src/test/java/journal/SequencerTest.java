package journal;

import com.lmax.disruptor.RingBuffer;
import config.ExchangeConfig;
import event.InboundPipeline;
import event.OrderEvent;
import io.aeron.Subscription;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import org.agrona.concurrent.IdleStrategy;
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

import static journal.JournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The sequencer (SRS §3.8) as the inbound ring's consumer, against a real embedded journal:
 * every field journaled, seq gap-free from 1, the D1 timestamp clamp, continuation across a
 * restart from the extended recording (D10), and back-pressure that never drops a command.
 */
class SequencerTest {

    @TempDir
    Path root;

    /** Publishes through a real inbound ring whose consumer is {@code sequencer}, then drains it. */
    private static void sequence(Sequencer sequencer, List<OrderEvent> commands) {
        // A consumer that throws stops silently under the Disruptor's default handler, so the
        // first failure is captured here and fails the test with its cause.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong consumed = new AtomicLong();
        InboundPipeline pipeline = new InboundPipeline((slot, sequence, endOfBatch) -> {
            try {
                sequencer.onEvent(slot, sequence, endOfBatch);
                consumed.incrementAndGet();
            } catch (RuntimeException e) {
                failure.compareAndSet(null, e);
                throw e;
            }
        });
        pipeline.start();
        try {
            RingBuffer<OrderEvent> ring = pipeline.getRingBuffer();
            for (OrderEvent command : commands) {
                ring.publishEvent(COPY_INTO_SLOT, command);
            }
            // Disruptor.shutdown() ignores a consumer whose thread has not started yet, so it is
            // not a drain on its own: wait for every command to be consumed (or the consumer to fail).
            await(() -> consumed.get() == commands.size() || failure.get() != null, "sequencer consumed every command");
        } finally {
            pipeline.shutdown();
        }
        if (failure.get() != null) throw new AssertionError("sequencer failed", failure.get());
    }

    private static List<OrderEvent> limits(long firstId, int count, long firstTimestamp) {
        List<OrderEvent> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(limit(firstId + i, firstTimestamp + i));
        }
        return out;
    }

    @Test
    void everyCommandTypeIsJournaledWithEveryField() {
        List<OrderEvent> commands = List.of(
                sessionOpen(20261102, "XNAS", 1_000L),
                newOrder(1L, Side.BUY, OrdType.LIMIT, TimeInForce.GTC, 1_502_500L, 100L, 0L, 1_001L),
                newOrder(2L, Side.SELL, OrdType.PEG_MID, TimeInForce.GTC, -1L, 40L, 0L, 1_002L),
                newOrder(3L, Side.SELL, OrdType.LIMIT, TimeInForce.GTC, 1_503_000L, 500L, 50L, 1_003L),
                newOrder(4L, Side.BUY, OrdType.LIMIT, TimeInForce.IOC, 1_503_000L, 20L, 0L, 1_004L),
                newOrder(5L, Side.BUY, OrdType.LIMIT, TimeInForce.FOK, 1_503_100L, 30L, 0L, 1_005L),
                newOrder(6L, Side.SELL, OrdType.PEG_MID, TimeInForce.IOC, -1L, 15L, 0L, 1_006L),
                cancel(7L, 1L, 1_007L),
                sessionClose(20261102, 1_008L));

        try (Journal journal = Journal.launch(config(root))) {
            Sequencer sequencer = Sequencer.open(journal);
            sequence(sequencer, commands);

            List<Journaled> journaled = readAll(journal, commands.size());
            for (int i = 0; i < commands.size(); i++) {
                assertEquals(i + 1, journaled.get(i).seq(), "seq of command " + i);
                assertSameFields(commands.get(i), journaled.get(i).event(), "command " + i);
            }
            assertEquals(commands.size(), sequencer.lastSeq());
            assertEquals(1_008L, sequencer.lastTimestamp());
        }
    }

    @Test
    void seqIsGapFreeFromOne() {
        int count = 5_000;   // about 480 KB: crosses many 64 KiB terms and segments
        try (Journal journal = Journal.launch(config(root))) {
            sequence(Sequencer.open(journal), limits(1L, count, 1L));

            List<Journaled> journaled = readAll(journal, count);
            for (int i = 0; i < count; i++) {
                assertEquals(i + 1, journaled.get(i).seq());
                assertEquals(i + 1, journaled.get(i).event().orderId, "journal order is ring order");
            }
        }
    }

    @Test
    void anEarlierTimestampIsClampedToTheLastJournaledOne() {
        List<OrderEvent> commands = List.of(
                limit(1L, 1_000L), limit(2L, 500L), limit(3L, 2_000L), limit(4L, 1_500L),
                limit(5L, 2_000L), limit(6L, 3_000L));
        long[] expected = {1_000L, 1_000L, 2_000L, 2_000L, 2_000L, 3_000L};

        try (Journal journal = Journal.launch(config(root))) {
            Sequencer sequencer = Sequencer.open(journal);
            sequence(sequencer, commands);

            List<Journaled> journaled = readAll(journal, commands.size());
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], journaled.get(i).event().timestamp, "timestamp of command " + i);
            }
            assertEquals(3_000L, sequencer.lastTimestamp());
        }
    }

    @Test
    void theClampIsWrittenToTheSlotBeforeEncoding() {
        try (Journal journal = Journal.launch(config(root))) {
            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            Sequencer sequencer = new Sequencer(recorded.publication(), 41L, 9_000L, new CountingIdle());

            OrderEvent slot = limit(1L, 8_000L);
            sequencer.onEvent(slot, 0L, true);

            assertEquals(9_000L, slot.timestamp);
            assertEquals(42L, sequencer.lastSeq());
            Journaled only = readAll(journal, 1).get(0);
            assertEquals(42L, only.seq());
            assertEquals(9_000L, only.event().timestamp);
        }
    }

    @Test
    void aRestartContinuesSeqAndTheTimestampFloorFromTheExtendedRecording() {
        ExchangeConfig cfg = config(root);
        int first = 1_000;    // about 96 KB: the tail of run 1 sits in its second term
        int second = 700;
        long recordingId;

        try (Journal journal = Journal.launch(cfg)) {
            Sequencer sequencer = Sequencer.open(journal);
            assertEquals(0L, sequencer.lastSeq());
            assertEquals(Sequencer.NO_TIMESTAMP, sequencer.lastTimestamp());
            sequence(sequencer, limits(1L, first, 10_000L));
            recordingId = journal.findRecording(Journal.INBOUND_STREAM_ID).recordingId();
        }

        long floor = 10_000L + first - 1;
        try (Journal journal = Journal.launch(cfg)) {
            Sequencer sequencer = Sequencer.open(journal);
            assertEquals(first, sequencer.lastSeq(), "seq continues from the recording");
            assertEquals(floor, sequencer.lastTimestamp(), "timestamp floor continues from the recording");
            // A clock behind the previous run: every one of these is earlier than the floor.
            sequence(sequencer, limits(1L + first, second, 5_000L));
        }

        try (Journal journal = Journal.launch(cfg)) {
            assertEquals(1, journal.findRecordings(Journal.INBOUND_STREAM_ID).size(), "extended, not duplicated");
            assertEquals(recordingId, journal.findRecording(Journal.INBOUND_STREAM_ID).recordingId());

            Sequencer sequencer = Sequencer.open(journal);
            assertEquals(first + second, sequencer.lastSeq());
            assertEquals(floor, sequencer.lastTimestamp());

            List<Journaled> journaled = readAll(journal, first + second);
            long previous = Long.MIN_VALUE;
            for (int i = 0; i < journaled.size(); i++) {
                Journaled j = journaled.get(i);
                assertEquals(i + 1, j.seq());
                assertEquals(i + 1, j.event().orderId);
                assertTrue(j.event().timestamp >= previous, "timestamps never decrease");
                previous = j.event().timestamp;
            }
            assertEquals(floor, journaled.get(first).event().timestamp, "first command of run 2 is clamped");
        }
    }

    @Test
    void backPressureNeverDropsOrReordersACommand() throws InterruptedException {
        int count = 3_000;   // about 290 KB: far beyond a 64 KiB term window plus the 1,024-slot ring
        try (Journal journal = Journal.launch(config(root))) {
            CountingIdle idle = new CountingIdle();
            Sequencer sequencer = Sequencer.open(journal, idle);

            // An unpolled subscriber caps the IPC publisher limit, so the sequencer is back-pressured.
            Subscription slow = journal.archive().context().aeron()
                    .addSubscription(Journal.IPC_CHANNEL, Journal.INBOUND_STREAM_ID);
            await(() -> slow.imageCount() > 0, "slow subscriber connected");

            InboundPipeline pipeline = new InboundPipeline(sequencer);
            pipeline.start();
            Thread producer = new Thread(() -> {
                RingBuffer<OrderEvent> ring = pipeline.getRingBuffer();
                for (OrderEvent command : limits(1L, count, 1L)) {
                    ring.publishEvent(COPY_INTO_SLOT, command);
                }
            }, "test-producer");
            producer.start();

            await(() -> idle.idles.get() > 0, "sequencer back-pressured");
            int recordedWhileBlocked = readAll(journal).size();
            assertTrue(recordedWhileBlocked < count,
                    "the slow subscriber must hold the stream back, recorded " + recordedWhileBlocked);

            AtomicBoolean draining = new AtomicBoolean(true);
            AtomicLong drained = new AtomicLong();
            Thread drainer = new Thread(() -> {
                while (draining.get()) {
                    if (slow.poll((b, o, l, h) -> drained.incrementAndGet(), 256) == 0) {
                        LockSupport.parkNanos(100_000L);
                    }
                }
            }, "test-drainer");
            drainer.start();
            try {
                producer.join(TimeUnit.SECONDS.toMillis(30));
                assertFalse(producer.isAlive(), "producer finished once the stream drained");
                pipeline.shutdown();

                List<Journaled> journaled = readAll(journal, count);
                for (int i = 0; i < count; i++) {
                    assertEquals(i + 1, journaled.get(i).seq());
                    assertEquals(i + 1, journaled.get(i).event().orderId, "payloads stay in publish order");
                }
                await(() -> drained.get() == count, "slow subscriber saw every command");
            } finally {
                draining.set(false);
                drainer.join(TimeUnit.SECONDS.toMillis(10));
                slow.close();
            }
            assertEquals(count, sequencer.lastSeq());
        }
    }

    @Test
    void aFatalOfferThrowsWithoutCommittingTheSeq() {
        try (Journal journal = Journal.launch(config(root))) {
            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            Sequencer sequencer = new Sequencer(recorded.publication(), 0L, Sequencer.NO_TIMESTAMP, new CountingIdle());
            recorded.publication().close();

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> sequencer.onEvent(limit(1L, 1L), 0L, true));
            assertTrue(failure.getMessage().contains("seq=1"), failure.getMessage());
            assertEquals(0L, sequencer.lastSeq());
            assertEquals(Sequencer.NO_TIMESTAMP, sequencer.lastTimestamp());
        }
    }

    /** Counts every back-off so a test can see the sequencer retried. Thread-safe for that read. */
    static final class CountingIdle implements IdleStrategy {
        final AtomicLong idles = new AtomicLong();

        @Override public void idle(int workCount) { if (workCount <= 0) idle(); }
        @Override public void idle() { idles.incrementAndGet(); LockSupport.parkNanos(10_000L); }
        @Override public void reset() { }
    }
}
