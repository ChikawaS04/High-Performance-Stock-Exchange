package integration;

import config.ExchangeConfig;
import event.CapturingExecutionHandler;
import event.CapturingExecutionHandler.Observed;
import event.InboundPipeline;
import gateway.OrderGateway;
import journal.Journal;
import journal.JournaledPipeline;
import journal.OutboundRecorder;
import journal.OutboundRecordings;
import journal.RecordedPublication;
import journal.Sequencer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import recovery.RecoveryException;
import recovery.SnapshotStore;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static integration.RecoveryScenario.await;
import static journal.OutboundRecordings.assertGapFreeFromOne;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The recorded outbound stream through the real {@link JournaledPipeline} (P15-6, SRS §3.5,
 * §3.9, §3.10, D13): the recording equals what was published; a recorder failure is fatal while
 * the publisher carries on; events produced but never recorded are published and recorded exactly
 * once on recovery; a graceful restart publishes nothing; the mismatch cases refuse or rebuild as
 * planned; and shutdown after a recorder fatal returns even with the outbound ring full.
 */
class OutboundRecordingTest {

    @TempDir
    Path root;

    /** A recorder that fails at {@code seq}, as a fatal offer would. */
    private static LongConsumer failAt(long seq) {
        return s -> {
            if (s == seq) throw new IllegalStateException("recorder fails at seq " + seq + " (test)");
        };
    }

    private record Run(List<Observed> published, int commands) { }

    /** Runs the recovery scenario with the given recorder seam; graceful close unless the recorder fails. */
    private static Run runScenario(ExchangeConfig config, LongConsumer beforeRecord) {
        List<byte[]> frames = RecoveryScenario.frames();
        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        JournaledVenue venue = JournaledVenue.create(config, new JournaledPipeline.Seams(null, null, beforeRecord));
        venue.outbound().handleEventsWith(execs);
        OrderGateway gateway = venue.gateway(RecoveryScenario.countingClock(1_000L));
        venue.startAndOpen(gateway);   // P15-8: SESSION_OPENED is the first published event, seq 1
        for (byte[] frame : frames) gateway.onFrame(frame);
        await(() -> venue.pipeline().appliedSeq() == RecoveryScenario.commands(), "scenario applied");
        // Every event is on the ring once its command is applied; wait for the capture to see them all
        // (draining by the ring is impossible once the recorder has died).
        await(() -> execs.snapshot().size() == venue.pipeline().publishedSeq(), "events captured");
        if (beforeRecord == null) {
            venue.close();
        } else {
            await(() -> !venue.fatals().isEmpty(), "the recorder failure reported");
            venue.closeAfterFatal();
        }
        return new Run(execs.snapshot(), RecoveryScenario.commands());
    }

    private static List<Observed> recording(ExchangeConfig config) {
        try (Journal journal = Journal.launch(config)) {
            return OutboundRecordings.readAll(journal);
        }
    }

    /** A restarted venue whose recovery-published events are captured; started, not closed. */
    private static JournaledVenue restart(ExchangeConfig config, CapturingExecutionHandler captured) {
        JournaledVenue venue = JournaledVenue.create(config, null, null);
        venue.outbound().handleEventsWith(captured);
        venue.start();   // returns once the recovered events reached every outbound subscriber
        return venue;
    }

    @Test
    void theRecordingEqualsThePublishedEventsInSeqOrderWithNoGaps() {
        ExchangeConfig config = JournaledVenue.config(root);
        Run run = runScenario(config, null);

        List<Observed> recorded = recording(config);
        assertTrue(run.published().size() > run.commands(), "the scenario trades: " + run.published().size());
        assertGapFreeFromOne(recorded);
        assertEquals(run.published(), recorded, "every published event recorded once, every field, in seq order");
        List<Observed> accepts = recorded.stream()
                .filter(o -> o.eventType() == event.ExecutionEventType.ORDER_ACCEPTED).toList();
        assertTrue(!accepts.isEmpty() && accepts.stream().allMatch(o -> o.aggressorSide() != null),
                "every recorded ACCEPTED keeps its order's side through the SBE encoder and decoder");
    }

    @Test
    void aRecorderFailureIsFatalWhileThePublisherCarriesOn() {
        ExchangeConfig config = JournaledVenue.config(root);
        Run run = runScenario(config, failAt(3));

        assertGapFreeFromOne(run.published());
        assertTrue(run.published().size() > 3, "the publisher kept going after the recorder stopped");
        assertEquals(run.published().subList(0, 2), recording(config), "the recording stops before the failed event");
    }

    @Test
    void eventsProducedButNotRecordedArePublishedAndRecordedOnceOnRecovery() {
        ExchangeConfig config = JournaledVenue.config(root);
        Run crashed = runScenario(config, failAt(5));
        List<Observed> before = crashed.published();
        int n = before.size();
        assertEquals(before.subList(0, 4), recording(config), "events 5 to n were broadcast but never recorded");

        CapturingExecutionHandler recovered = new CapturingExecutionHandler();
        JournaledVenue venue = restart(config, recovered);
        assertEquals(4, venue.pipeline().outboundFloor());
        assertEquals(before.subList(4, n), recovered.snapshot(),
                "exactly the unrecorded tail is republished, identical in every field");
        assertEquals(n, venue.pipeline().publishedSeq(), "publishedSeq is the highest EXEC seq published");
        venue.close();

        List<Observed> recorded = recording(config);
        assertGapFreeFromOne(recorded);
        assertEquals(before, recorded, "the recording is complete, with nothing recorded twice");

        CapturingExecutionHandler again = new CapturingExecutionHandler();
        JournaledVenue third = restart(config, again);
        assertTrue(again.snapshot().isEmpty(), "a later restart republishes nothing");
        third.close();
        assertEquals(before, recording(config));
    }

    @Test
    void aGracefulRestartPublishesNothing() {
        ExchangeConfig config = JournaledVenue.config(root);
        Run run = runScenario(config, null);
        int n = run.published().size();

        CapturingExecutionHandler captured = new CapturingExecutionHandler();
        JournaledVenue venue = restart(config, captured);
        assertTrue(captured.snapshot().isEmpty(), "nothing published on recovery");
        assertEquals(n, venue.pipeline().outboundFloor());
        assertEquals(n, venue.pipeline().publishedSeq());
        venue.close();
        assertEquals(n, recording(config).size(), "the recording is unchanged");
    }

    @Test
    void publishedSeqIsTheFloorBeforeAnyEventAndFollowsTheRing() {
        JournaledVenue venue = JournaledVenue.create(root);
        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        venue.outbound().handleEventsWith(execs);
        OrderGateway gateway = venue.gateway();
        assertEquals(0, venue.pipeline().publishedSeq(), "fresh run, before start");
        venue.start();
        assertEquals(0, venue.pipeline().publishedSeq(), "fresh run, no event yet");
        venue.sessionController(gateway).startup();
        assertEquals(JournaledVenue.OPEN_SEQ, venue.pipeline().publishedSeq(), "the session open is the first event");
        gateway.onFrame(FixMessages.limit(1, '1', "150.00", 10));
        gateway.onFrame(FixMessages.limit(2, '2', "150.00", 4));   // fill: two more events at most
        execs.awaitAtLeast(3, 10_000);
        await(() -> venue.pipeline().appliedSeq() == JournaledVenue.OPEN_SEQ + 2, "both applied");
        assertEquals(execs.snapshot().size(), venue.pipeline().publishedSeq());
        venue.close();
    }

    @Test
    void aMissingOutboundRecordingIsRebuiltFromSeqOne() {
        ExchangeConfig config = JournaledVenue.config(root);
        Run run = runScenario(config, null);
        try (Journal journal = Journal.launch(config)) {   // data from before P15-6, or the recording removed
            journal.archive().purgeRecording(journal.findRecording(Journal.OUTBOUND_STREAM_ID).recordingId());
            assertNull(journal.findRecording(Journal.OUTBOUND_STREAM_ID));
        }

        CapturingExecutionHandler captured = new CapturingExecutionHandler();
        JournaledVenue venue = restart(config, captured);
        assertEquals(-1, venue.pipeline().recoveredSnapshotSeq(), "the snapshot is ahead of an empty recording");
        assertEquals(run.commands(), venue.pipeline().replayedCommands());
        assertEquals(run.published(), captured.snapshot(), "the whole history is republished once");
        venue.close();
        assertEquals(run.published(), recording(config), "the outbound record is rebuilt from seq 1");
    }

    @Test
    void aSnapshotAheadOfTheRecordingIsSkippedAndTheGapRegenerated() throws Exception {
        ExchangeConfig config = JournaledVenue.config(root, 5);   // periodic snapshots every 5 commands
        Run crashed = runScenario(config, failAt(2));
        SnapshotStore store = new SnapshotStore(config.snapshotDir());
        assertNotNull(store.loadNewestValid(), "periodic snapshots were taken while the recorder was down");
        assertTrue(store.loadNewestValid().outboundLastSeq() > 1);

        CapturingExecutionHandler captured = new CapturingExecutionHandler();
        JournaledVenue venue = restart(config, captured);
        assertEquals(-1, venue.pipeline().recoveredSnapshotSeq(), "every snapshot is ahead of the recording");
        assertEquals(crashed.published().subList(1, crashed.published().size()), captured.snapshot());
        venue.close();
        assertEquals(crashed.published(), recording(config));
    }

    /** Commands journaled behind the engine's back: a gateway, ring and sequencer on the journal only. */
    private static void journalCommands(Journal journal, List<byte[]> frames, long clockStart) {
        Sequencer sequencer = Sequencer.open(journal);
        long target = sequencer.lastSeq() + frames.size();
        InboundPipeline inbound = new InboundPipeline(sequencer);
        inbound.start();
        OrderGateway gateway = new OrderGateway(inbound.getRingBuffer(), RecoveryScenario.countingClock(clockStart));
        for (byte[] frame : frames) gateway.onFrame(frame);
        await(() -> sequencer.journaledSeq() == target, "commands journaled");
        inbound.halt();
    }

    /** Executions recorded behind the engine's back. */
    private static void recordExecutions(Journal journal, long fromSeq, long toSeq) {
        RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
        OutboundRecorder recorder = OutboundRecorder.open(journal, recorded, new BackoffIdleStrategy(), null);
        for (long seq = fromSeq; seq <= toSeq; seq++) recorder.onEvent(OutboundRecordings.fill(seq), seq, true);
    }

    @Test
    void anOutboundRecordingAheadOfTheJournalRefusesStartup() {
        ExchangeConfig config = JournaledVenue.config(root);
        Run run = runScenario(config, null);
        int n = run.published().size();
        RecoveryScenario.deleteTree(config.snapshotDir());
        try (Journal journal = Journal.launch(config)) {
            recordExecutions(journal, n + 1, n + 3);   // executions no journaled command explains
        }

        JournaledVenue venue = JournaledVenue.create(root);
        RecoveryException refused = assertThrows(RecoveryException.class, venue::start);
        assertTrue(refused.getMessage().contains("outbound recording ends at seq " + (n + 3)), refused.getMessage());
        assertTrue(refused.getMessage().contains("regenerates only up to seq " + n), refused.getMessage());
        assertTrue(refused.getMessage().contains("Do not delete the archive"), refused.getMessage());
        assertEquals(n + 3, recording(config).size(), "the refusal closed the journal and recorded nothing");
    }

    @Test
    void anOutboundRecordingWithoutAJournalRefusesStartup() {
        ExchangeConfig config = JournaledVenue.config(root);
        runScenario(config, null);
        RecoveryScenario.deleteTree(config.snapshotDir());
        try (Journal journal = Journal.launch(config)) {
            journal.archive().purgeRecording(journal.findRecording(Journal.INBOUND_STREAM_ID).recordingId());
        }

        RecoveryException refused = assertThrows(RecoveryException.class, () -> JournaledVenue.create(root));
        assertTrue(refused.getMessage().contains("inbound journal"), refused.getMessage());
        assertTrue(refused.getMessage().contains("missing or empty"), refused.getMessage());
    }

    @Test
    void aRecordedEventThatReplayDoesNotRegenerateRefusesStartup() {
        ExchangeConfig config = JournaledVenue.config(root);
        Run run = runScenario(config, null);
        int n = run.published().size();
        RecoveryScenario.deleteTree(config.snapshotDir());
        try (Journal journal = Journal.launch(config)) {
            // One more command (its replay regenerates ORDER_ACCEPTED at seq n+1), but a different
            // event recorded at that seq: the recordings no longer describe one history.
            journalCommands(journal, List.of(FixMessages.limit(900, '1', "1.00", 1)), 9_000L);
            recordExecutions(journal, n + 1, n + 1);
        }

        JournaledVenue venue = JournaledVenue.create(root);
        RecoveryException refused = assertThrows(RecoveryException.class, venue::start);
        assertTrue(refused.getMessage().contains("different event at outbound seq " + (n + 1)), refused.getMessage());
        assertTrue(refused.getMessage().contains("run the build that wrote it"), refused.getMessage());
    }

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)   // a hang fails, never wedges the suite
    void shutdownAfterARecorderFatalReturnsWithTheOutboundRingFull() {
        // One ACCEPTED each: just past the 1,024-slot outbound ring, and well inside what the inbound
        // ring and stream hold behind the stalled engine, so this thread (the gateway) never blocks.
        int orders = 1_100;
        AtomicLong entered = new AtomicLong();   // commands the engine has started to process
        JournaledVenue venue = JournaledVenue.create(JournaledVenue.config(root),
                new JournaledPipeline.Seams(command -> entered.incrementAndGet(), null, failAt(1)));
        OrderGateway gateway = venue.gateway();
        // The session open is command 1 and its SESSION_OPENED the event the recorder fails at; every
        // order after it is one ACCEPTED, so the counts below are unchanged by the open.
        venue.startAndOpen(gateway);
        for (int i = 1; i <= orders; i++) gateway.onFrame(FixMessages.limit(i, '1', "100.00", 1));
        await(() -> !venue.fatals().isEmpty(), "the recorder failure reported");
        // The dead recorder never frees a slot: the engine fills the 1,024-slot ring, then parks in
        // next() inside command 1,025. Waiting for that command to be entered, not just for the ring to
        // be full, rules out an engine that merely caught up with the journal and is idle.
        await(() -> entered.get() == 1_025, "the engine inside command 1,025");
        assertEquals(1_024, venue.pipeline().publishedSeq(), "the outbound ring is full");
        assertEquals(1_024, venue.pipeline().appliedSeq(), "the engine is held mid-command by the full ring");

        long start = System.nanoTime();
        venue.closeAfterFatal();
        long tookMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(tookMs < 30_000, "shutdown returned after " + tookMs + " ms");
        // The engine thread was abandoned inside an image poll, so the journal was left open rather
        // than unmapped under it (production exits here; the next start recovers as after a crash).
        assertFalse(venue.journal().isClosed(), "the journal is not closed under an abandoned engine thread");

        // Test cleanup standing in for process exit: safe here only because the abandoned thread is
        // parked for good in next() on a ring whose consumers are halted, so it never reads again.
        venue.journal().close();

        // Every journaled command is applied and its event published and recorded on recovery.
        CapturingExecutionHandler captured = new CapturingExecutionHandler();
        JournaledVenue recovered = restart(JournaledVenue.config(root), captured);
        long applied = recovered.pipeline().appliedSeq();
        assertTrue(applied >= 1_024, "at least the commands applied before the fatal: " + applied);
        assertEquals(applied, captured.snapshot().size(), "one ACCEPTED per journaled command, all published");
        assertGapFreeFromOne(captured.snapshot());
        recovered.close();
        assertEquals(captured.snapshot(), recording(JournaledVenue.config(root)));
    }
}
