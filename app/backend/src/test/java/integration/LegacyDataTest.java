package integration;

import config.ExchangeConfig;
import event.CapturingExecutionHandler;
import event.ExecutionEventType;
import event.InboundPipeline;
import gateway.OrderGateway;
import journal.Journal;
import journal.OutboundRecorder;
import journal.OutboundRecordings;
import journal.RecordedPublication;
import journal.Sequencer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import recovery.RecoveryException;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static integration.RecoveryScenario.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Data written before the session lifecycle (P15-8, decision 6). Such a journal holds orders matched
 * with no session open, which this build would reject on replay, so startup refuses it with exit 2 and
 * a remedy rather than a generic divergence error. The legacy data is built behind the engine's back,
 * exactly as an older build left it: NEW commands journaled by the sequencer with no SESSION_OPEN, and
 * (when the outbound recording exists) executions recorded with no SESSION_OPENED before them.
 *
 * Data written with sessions but with the session closed when the first orders arrived (auto-open
 * off) holds only rejects before its first open, and must not be refused.
 */
class LegacyDataTest {

    @TempDir
    Path root;

    /** NEW commands journaled with no session command, as a build before P15-8 journaled them. */
    private static void journalOrdersWithoutASession(Journal journal) {
        Sequencer sequencer = Sequencer.open(journal);
        InboundPipeline inbound = new InboundPipeline(sequencer);
        inbound.start();
        OrderGateway gateway = new OrderGateway(inbound.getRingBuffer(), RecoveryScenario.countingClock(1_000L));
        List<byte[]> frames = RecoveryScenario.frames().subList(0, 5);
        for (byte[] frame : frames) gateway.onFrame(frame);
        await(() -> sequencer.journaledSeq() == frames.size(), "legacy commands journaled");
        inbound.halt();
    }

    /** Executions recorded with no SESSION_OPENED before them. */
    private static void recordFills(Journal journal, int count) {
        RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
        OutboundRecorder recorder = OutboundRecorder.open(journal, recorded, new BackoffIdleStrategy(), null);
        for (long seq = 1; seq <= count; seq++) recorder.onEvent(OutboundRecordings.fill(seq), seq, true);
    }

    private void assertRefusedAsLegacy(String evidence) {
        ExchangeConfig config = JournaledVenue.config(root);
        RecoveryException refused = assertThrows(RecoveryException.class, () -> JournaledVenue.create(root));
        String message = refused.getMessage();
        assertTrue(message.contains("written before the session lifecycle"), message);
        assertTrue(message.contains(evidence), message);
        assertTrue(message.contains("Move " + config.dataDir() + " aside"), message);
        assertTrue(message.contains("ff64995"), message);
        try (Journal journal = Journal.launch(config)) {
            assertNotNull(journal.findRecording(Journal.INBOUND_STREAM_ID), "the refusal closed the journal cleanly");
        }
    }

    @Test
    void ordersMatchedWithoutASessionAreRefusedFromTheOutboundRecording() {
        try (Journal journal = Journal.launch(JournaledVenue.config(root))) {
            journalOrdersWithoutASession(journal);
            recordFills(journal, 3);
        }
        assertRefusedAsLegacy("the outbound recording holds ORDER_PARTIALLY_FILLED before any SESSION_OPENED");
    }

    @Test
    void ordersWithoutASessionAndNoOutboundRecordingAreRefusedFromTheInboundJournal() {
        try (Journal journal = Journal.launch(JournaledVenue.config(root))) {
            journalOrdersWithoutASession(journal);
        }
        assertRefusedAsLegacy("the inbound journal holds a NEW before any SESSION_OPEN");
    }

    @Test
    void dataWithSessionsWhoseFirstOrdersCameWhileClosedIsNotRefused() {
        Map<String, String> env = new HashMap<>();
        env.put(ExchangeConfig.EXCHANGE_DATA_DIR, root.resolve("data").toString());
        env.put(ExchangeConfig.AERON_DIR, root.resolve("aeron").toString());
        env.put(ExchangeConfig.JOURNAL_TERM_LENGTH, Integer.toString(64 * 1024));
        env.put(ExchangeConfig.JOURNAL_SEGMENT_LENGTH, Integer.toString(64 * 1024));
        env.put(ExchangeConfig.SESSION_AUTO_OPEN, "false");
        ExchangeConfig closedAtStart = ExchangeConfig.from(env);

        try (JournaledVenue venue = JournaledVenue.create(closedAtStart, null, null)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);   // auto-open off: stays closed
            gateway.onFrame(FixMessages.limit(1, '1', "150.00", 10));   // rejected: no session
            await(() -> venue.pipeline().appliedSeq() == 1, "the early order applied");
            venue.sessionController(gateway).open(JournaledVenue.BUSINESS_DATE);
            gateway.onFrame(FixMessages.limit(2, '1', "150.00", 10));
            await(() -> venue.pipeline().appliedSeq() == 3, "the order after the open applied");
        }
        RecoveryScenario.deleteTree(closedAtStart.snapshotDir());   // no snapshot: the startup check runs

        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        try (JournaledVenue again = JournaledVenue.create(closedAtStart, null, null)) {
            again.outbound().handleEventsWith(execs);
            again.start();
            assertEquals(3, again.pipeline().replayedCommands());
            assertFalse(again.engine().hasOpenOrder(1), "rejected while closed, again on replay");
            assertTrue(again.engine().hasOpenOrder(2));
        }
        assertTrue(execs.snapshot().stream().noneMatch(o -> o.eventType() == ExecutionEventType.ORDER_ACCEPTED),
                "nothing regenerated above the recording");
    }
}
