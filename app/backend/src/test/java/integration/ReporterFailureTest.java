package integration;

import codec.ExecutionEventCodec;
import codec.MessageHeaderEncoder;
import config.ExchangeConfig;
import db.Database;
import db.RequiresTestDatabase;
import db.TestDatabase;
import event.ExecutionEvent;
import event.ExecutionEventType;
import gateway.OrderGateway;
import journal.Journal;
import journal.RecordedPublication;
import journal.RecordingDescriptor;
import model.Side;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reporter.Reporter;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static integration.ReporterHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every failure path: nothing lost, nothing duplicated, nothing skipped, the venue untouched. */
@RequiresTestDatabase
class ReporterFailureTest {

    private static final long PRICE = 1_000_000L;

    @TempDir
    Path root;

    @BeforeEach
    void emptySchema() {
        TestDatabase.cleanAndMigrate();
    }

    /** {@code pairs} crossing orders: two commands and two events each, ids from {@code base}. */
    private static void trade(OrderGateway gateway, long base, int pairs) {
        for (int i = 0; i < pairs; i++) {
            gateway.onFrame(limit(base + i * 2L, Side.SELL, PRICE, 1L));
            gateway.onFrame(limit(base + i * 2L + 1, Side.BUY, PRICE, 1L));
        }
    }

    /** Everything an outbound range should have left: no gap, no duplicate, the last seq reached. */
    private static void assertCompleteThrough(int outboundSeq, int pairs) {
        assertEquals(Integer.toString(outboundSeq - 1), TestDatabase.queryOne("SELECT count(*) FROM executions"));
        assertEquals(Integer.toString(outboundSeq), TestDatabase.queryOne("SELECT max(outbound_seq) FROM executions"));
        assertEquals(Integer.toString(pairs * 2), TestDatabase.queryOne("SELECT count(*) FROM orders"));
        assertEquals(Integer.toString(pairs), TestDatabase.queryOne("SELECT sum(trade_count) FROM candle"));
        assertEquals(Integer.toString(outboundSeq), TestDatabase.queryOne("SELECT last_outbound_seq FROM reporter_checkpoint"));
    }

    @Test
    void aReporterKilledBetweenWriteAndCommitResumesWithNoLossOrDuplicate() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            int pairs = 20;
            trade(gateway, 1_000L, pairs);
            int last = 1 + pairs * 2;

            AtomicInteger commits = new AtomicInteger();
            Reporter reporter = reporter(venue, TestDatabase.database()::connect, () -> {
                // The third and fourth batches die after writing, before committing.
                int n = commits.incrementAndGet();
                if (n == 3 || n == 4) {
                    throw new IllegalStateException("killed between write and commit");
                }
            });
            reporter.start();
            awaitCheckpoint(reporter, last);
            assertTrue(commits.get() > 4, "the failures happened");
            assertTrue(reporter.stop());
            assertCompleteThrough(last, pairs);
        }
    }

    @Test
    void aReporterStoppedMidDayAndStartedAgainMissesNothing() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            trade(gateway, 1_000L, 10);
            Reporter first = reporter(venue);
            first.start();
            awaitCheckpoint(first, 5L);
            assertTrue(first.stop());

            trade(gateway, 2_000L, 10);
            Reporter second = reporter(venue);
            second.start();
            awaitCheckpoint(second, 41L);
            assertTrue(second.stop());
            assertCompleteThrough(41, 20);
        }
    }

    @Test
    void itCatchesUpAfterADatabaseAtAClosedPortThenTheRealOne() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            Database closed = new Database(TestDatabase.UNREACHABLE_URL, "nobody", "nothing", TestDatabase.FAST);
            Database real = TestDatabase.database();
            AtomicBoolean available = new AtomicBoolean(false);
            Reporter reporter = reporter(venue, () -> available.get() ? real.connect() : closed.connect(), null);
            reporter.start();
            awaitState(reporter, "BACKING_OFF");

            trade(gateway, 1_000L, 20);
            await("every event to be recorded", () -> venue.pipeline().recordedSeq() == 41L);
            assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM executions"));

            available.set(true);
            awaitCheckpoint(reporter, 41L);
            assertEquals("RUNNING", reporter.state());
            assertTrue(reporter.stop());
            assertCompleteThrough(41, 20);
        }
    }

    @Test
    void aConnectionLostMidDayResumesFromTheCheckpoint() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            trade(gateway, 1_000L, 10);
            AtomicBoolean drop = new AtomicBoolean(true);
            AtomicInteger commits = new AtomicInteger();
            Reporter reporter = reporter(venue, TestDatabase.database()::connect, () -> {
                if (commits.incrementAndGet() == 2 && drop.getAndSet(false)) {
                    throw new IllegalStateException("connection lost");
                }
            });
            reporter.start();
            awaitCheckpoint(reporter, 21L);
            trade(gateway, 2_000L, 10);
            awaitCheckpoint(reporter, 41L);
            assertFalse(drop.get());
            assertTrue(reporter.stop());
            assertCompleteThrough(41, 20);
        }
    }

    @Test
    void anInconsistentRowStopsTheReporterWithoutSkippingAndTheVenueTrades() {
        // A row already holding the trade id the first fill will carry, at a seq the Reporter has not reached.
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_id, trade_id, event_ts)"
                + " VALUES (999999, 'ORDER_FILLED', 1, 1, 1)");
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            trade(gateway, 1_000L, 5);
            Reporter reporter = reporter(venue);
            reporter.start();
            awaitState(reporter, "FAILED");
            assertTrue(reporter.failure().contains("23505"), reporter.failure());

            assertEquals(List.of("999999"), rows("SELECT outbound_seq FROM executions"),
                    "the failing batch rolled back whole: nothing skipped past");
            long checkpoint = Long.parseLong(TestDatabase.queryOne("SELECT last_outbound_seq FROM reporter_checkpoint"));
            assertTrue(checkpoint < 3L, "the checkpoint is not past the fill at seq 3: " + checkpoint);

            trade(gateway, 2_000L, 5);
            await("the venue to keep trading", () -> venue.pipeline().recordedSeq() == 21L);
            assertTrue(venue.pipeline().running());
            assertTrue(reporter.stop());
            assertEquals("FAILED", reporter.state());
        }
    }

    /**
     * The P15-5 rule for the checkpoint. The new journal is longer than the old one, so the position
     * check passes; it has the same recording ids, as every fresh journal does (asserted), so only the
     * start timestamp tells them apart.
     */
    @Test
    void aFreshDataDirectoryAgainstAnotherJournalsCheckpointStopsTheReporterWhileTheVenueTrades() {
        List<String> dumped;
        RecordingDescriptor oldInbound;
        RecordingDescriptor oldOutbound;
        try (JournaledVenue venue = JournaledVenue.create(root.resolve("old"))) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            trade(gateway, 1_000L, 2);
            Reporter reporter = reporter(venue);
            reporter.start();
            awaitCheckpoint(reporter, 5L);
            assertTrue(reporter.stop());
            dumped = dump();
            oldInbound = venue.journal().findRecording(Journal.INBOUND_STREAM_ID);
            oldOutbound = venue.journal().findRecording(Journal.OUTBOUND_STREAM_ID);
        }

        try (JournaledVenue venue = JournaledVenue.create(root.resolve("new"))) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            trade(gateway, 1_000L, 20);
            await("the new journal to be longer", () -> venue.pipeline().recordedSeq() == 41L);
            assertEquals(oldInbound.recordingId(), venue.journal().findRecording(Journal.INBOUND_STREAM_ID).recordingId());
            assertEquals(oldOutbound.recordingId(), venue.journal().findRecording(Journal.OUTBOUND_STREAM_ID).recordingId());

            Reporter reporter = reporter(venue);
            reporter.start();
            awaitState(reporter, "FAILED");
            assertTrue(reporter.failure().contains("belongs to another journal"), reporter.failure());
            assertEquals(dumped, dump(), "nothing written");

            trade(gateway, 3_000L, 1);
            await("the venue to keep trading", () -> venue.pipeline().recordedSeq() == 43L);
            assertTrue(venue.pipeline().running());
            assertTrue(reporter.stop());
        }
    }

    /** The checkpoint names the same journal across a restart: extending a recording keeps its start timestamp. */
    @Test
    void theSameJournalAfterARestartIsAccepted() {
        ExchangeConfig config = JournaledVenue.config(root);
        long started;
        try (JournaledVenue venue = JournaledVenue.create(config, journal.JournaledPipeline.Seams.NONE)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            trade(gateway, 1_000L, 2);
            Reporter reporter = reporter(venue);
            reporter.start();
            awaitCheckpoint(reporter, 5L);
            assertTrue(reporter.stop());
            started = venue.journal().findRecording(Journal.INBOUND_STREAM_ID).startTimestamp();
        }
        try (JournaledVenue again = JournaledVenue.create(config, journal.JournaledPipeline.Seams.NONE)) {
            OrderGateway gateway = again.gateway();
            again.startAndOpen(gateway);
            assertEquals(started, again.journal().findRecording(Journal.INBOUND_STREAM_ID).startTimestamp());
            trade(gateway, 2_000L, 2);
            Reporter reporter = reporter(again);
            reporter.start();
            awaitCheckpoint(reporter, 9L);
            assertTrue(reporter.stop());
            assertCompleteThrough(9, 4);
        }
    }

    private void runToFive(JournaledVenue venue, OrderGateway gateway) {
        venue.startAndOpen(gateway);
        trade(gateway, 1_000L, 2);
        Reporter first = reporter(venue);
        first.start();
        awaitCheckpoint(first, 5L);
        assertTrue(first.stop());
    }

    @Test
    void aCheckpointPositionBeyondTheRecordingStops() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            runToFive(venue, venue.gateway());
            TestDatabase.execute("UPDATE reporter_checkpoint SET outbound_position = 1073741824");
            Reporter reporter = reporter(venue);
            reporter.start();
            awaitState(reporter, "FAILED");
            assertTrue(reporter.failure().contains("beyond the recording's end"), reporter.failure());
        }
    }

    @Test
    void aFirstSeqThatDoesNotFollowTheCheckpointStops() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            runToFive(venue, gateway);
            TestDatabase.execute("UPDATE reporter_checkpoint SET last_outbound_seq = last_outbound_seq + 3");
            trade(gateway, 2_000L, 1);
            Reporter reporter = reporter(venue);
            reporter.start();
            awaitState(reporter, "FAILED");
            assertTrue(reporter.failure().contains("does not match this journal"), reporter.failure());
        }
    }

    @Test
    void aCheckpointRewoundByHandStops() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            runToFive(venue, venue.gateway());
            TestDatabase.execute("UPDATE reporter_checkpoint SET last_outbound_seq = 2");
            Reporter reporter = reporter(venue);
            reporter.start();
            awaitState(reporter, "FAILED");
            assertTrue(reporter.failure().contains("changed by hand"), reporter.failure());
        }
    }

    /** A journal from before P15-10: one version 0 event. The Reporter stops; nothing here is a venue. */
    @Test
    void aVersion0EventStopsTheReporter() {
        ExchangeConfig config = JournaledVenue.config(root);
        try (Journal journal = Journal.launch(config)) {
            RecordedPublication outbound = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
            RecordedPublication inbound = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);

            ExecutionEvent event = new ExecutionEvent();
            event.seq = 1L;
            event.eventType = ExecutionEventType.ORDER_REJECTED;
            event.orderId = 7L;
            event.timestamp = 1_000L;
            UnsafeBuffer buffer = new UnsafeBuffer(new byte[ExecutionEventCodec.ENCODED_LENGTH]);
            new ExecutionEventCodec().encode(event, buffer, 0);
            new MessageHeaderEncoder().wrap(buffer, 0).blockLength(94).version(0);
            int length = MessageHeaderEncoder.ENCODED_LENGTH + 94;
            await("the offer", () -> outbound.publication().offer(buffer, 0, length) > 0);

            Reporter reporter = new Reporter(TestDatabase.database()::connect, () -> { }, journal, inbound, outbound,
                    FAST, null);
            reporter.start();
            awaitState(reporter, "FAILED");
            assertTrue(reporter.failure().contains("no command seq"), reporter.failure());
            assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM executions"));
            assertTrue(reporter.stop());
        }
    }

    @Test
    void aSchemaThatIsNotReadyAtStartIsBroughtUpOnceTheDatabaseAnswers() {
        TestDatabase.clean();
        AtomicInteger steps = new AtomicInteger();
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            trade(gateway, 1_000L, 1);
            Reporter reporter = new Reporter(TestDatabase.database()::connect, () -> {
                steps.incrementAndGet();
                new db.Migrator(TestDatabase.database(), true).run();
            }, venue.journal(), venue.pipeline().inboundRecording(), venue.pipeline().outboundRecording(), FAST, null);
            reporter.start();
            awaitCheckpoint(reporter, 3L);
            assertTrue(reporter.stop());
            assertEquals(1, steps.get());
        }
    }

    @Test
    void aFailureThatIsNotDataBacksOffRatherThanStopping() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            venue.startAndOpen(venue.gateway());
            Reporter reporter = reporter(venue, () -> {
                throw new SQLException("the server is shutting down", "57P01");
            }, null);
            reporter.start();
            awaitState(reporter, "BACKING_OFF");
            assertNull(reporter.failure());
            assertTrue(reporter.stop());
        }
    }
}
