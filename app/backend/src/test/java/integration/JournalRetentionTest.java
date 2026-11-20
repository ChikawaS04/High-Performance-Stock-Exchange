package integration;

import config.ExchangeConfig;
import event.CapturingExecutionHandler;
import event.ExecutionEvent;
import event.ExecutionHistory;
import gateway.OrderGateway;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import journal.Journal;
import journal.JournaledPipeline;
import journal.RecordingDescriptor;
import journal.RetentionTiming;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import recovery.EngineStates;
import recovery.RecoveryException;
import recovery.RestoredSnapshot;
import recovery.SnapshotFiles;
import recovery.SnapshotStore;
import util.JournalStatus;
import util.ReporterStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static integration.RecoveryScenario.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Journal retention through the real pipeline (P16-7, D14, R1 to R8): segments of 64 KiB, so a few
 * hundred commands roll them; business days opened and closed through the production session
 * controller; retention run on demand ({@link RetentionTiming#ON_REQUEST}) and, after every close,
 * by the close snapshot. Deletes are asynchronous in the Archive, so every check that files are gone
 * waits for the segment listing, bounded.
 */
class JournalRetentionTest {

    private static final int SEG = 64 * 1024;
    private static final int FIRST_DAY = 20261102;
    private static final Map<String, String> OFF = Map.of(ExchangeConfig.JOURNAL_RETENTION_ENABLED, "false");

    @TempDir
    Path root;

    @TempDir
    Path other;

    // --- harness ---

    /** A Reporter stand-in whose state and committed positions the test sets. */
    static final class FakeReporter implements ReporterStatus {
        volatile String state = "RUNNING";
        volatile long inbound = -1L;
        volatile long outbound = -1L;

        @Override public String state() { return state; }
        @Override public long inboundSeq() { return 0; }
        @Override public long outboundSeq() { return 0; }
        @Override public long checkpointInboundPosition() { return inbound; }
        @Override public long checkpointOutboundPosition() { return outbound; }
    }

    /** One venue run: a gateway on a counting clock, the controller, and every EXEC captured. */
    static final class Run implements AutoCloseable {
        final JournaledVenue venue;
        final OrderGateway gateway;
        final CapturingExecutionHandler execs = new CapturingExecutionHandler();
        long nextId;

        Run(ExchangeConfig config, long clockStart, long firstId) {
            venue = JournaledVenue.create(config, new JournaledPipeline.Seams(null, null, null,
                    RetentionTiming.ON_REQUEST));
            venue.outbound().handleEventsWith(execs);
            gateway = venue.gateway(RecoveryScenario.countingClock(clockStart));
            nextId = firstId;
            venue.start();
        }

        JournaledPipeline pipeline() {
            return venue.pipeline();
        }

        void open(int date) {
            venue.sessionController(gateway).open(date);
        }

        void close(int date) {
            venue.sessionController(gateway).close(date);
            SnapshotStore store = new SnapshotStore(venue.config().snapshotDir());
            await(() -> closeSnapshot(store, date) != null, "the close snapshot of " + date);
        }

        /** {@code pairs} crossing pairs (a resting buy filled by a sell) and one resting GTC bid. */
        void trade(int pairs) {
            for (int i = 0; i < pairs; i++) {
                gateway.onFrame(FixMessages.limit(nextId++, '1', "100.00", 1));
                gateway.onFrame(FixMessages.limit(nextId++, '2', "100.00", 1));
            }
            gateway.onFrame(FixMessages.limit(nextId++, '1', "90.00", 3));
            long target = pipeline().journaledSeq();
            await(() -> pipeline().appliedSeq() >= target && pipeline().journaledSeq() == pipeline().appliedSeq(),
                    "the day's commands applied");
        }

        void day(int date, int pairs) {
            open(date);
            trade(pairs);
            close(date);
        }

        /** Two runs, each requested after the previous ended, so the second sees everything on disk now. */
        void runRetention() {
            for (int i = 0; i < 2; i++) {
                long before = pipeline().retention().completedRuns();
                pipeline().retention().requestRun();
                await(() -> pipeline().retention().completedRuns() > before, "a retention run");
            }
        }

        JournalStatus status() {
            return pipeline().journalRetention();
        }

        long inboundId() {
            return pipeline().inboundRecording().recordingId();
        }

        long outboundId() {
            return pipeline().outboundRecording().recordingId();
        }

        @Override
        public void close() {
            venue.close();
        }
    }

    private static ExchangeConfig config(Path root, int days) {
        return config(root, days, Map.of());
    }

    private static ExchangeConfig config(Path root, int days, Map<String, String> extra) {
        Map<String, String> env = new HashMap<>(extra);
        env.put(ExchangeConfig.JOURNAL_RETENTION_DAYS, Integer.toString(days));
        return JournaledVenue.config(root, env);
    }

    static RestoredSnapshot closeSnapshot(SnapshotStore store, int date) {
        try {
            RestoredSnapshot found = null;
            for (RestoredSnapshot s : store.listValid()) {
                if (s.closeDate() == date && (found == null || s.inboundSeq() < found.inboundSeq())) found = s;
            }
            return found;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<RestoredSnapshot> valid(SnapshotStore store) {
        try {
            return store.listValid();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long minPosition(SnapshotStore store) throws IOException {
        return store.listValid().stream().mapToLong(RestoredSnapshot::inboundPosition).min().orElseThrow();
    }

    private static long base(long position) {
        return AeronArchive.segmentFileBasePosition(0, position, SEG, SEG);
    }

    private static void awaitNoSegmentBelow(Path archiveDir, long recordingId, long start) {
        await(() -> {
            SortedMap<Long, Long> files = Journal.segmentFiles(archiveDir, recordingId);
            return files.isEmpty() || files.firstKey() >= start;
        }, "segment files of recording " + recordingId + " below " + start + " to be deleted");
    }

    private static RecordingDescriptor describe(ExchangeConfig config, int streamId) {
        try (Journal journal = Journal.launch(config)) {
            return journal.findRecording(streamId);
        }
    }

    // --- tests ---

    /** The production default (amendment 5): two days kept; the window starts at the third most recent close. */
    @Test
    void twoDaysKeepTheLastTwoClosedDaysAndTheCurrentDay() {
        try (Run run = new Run(config(root, 2), 1_000L, 1)) {
            SnapshotStore store = new SnapshotStore(run.venue.config().snapshotDir());
            run.day(FIRST_DAY, 400);
            run.day(FIRST_DAY + 1, 400);
            run.runRetention();
            assertEquals(0, run.status().inbound().startPosition(), "two closes: no window yet");
            assertEquals(0, run.status().outbound().startPosition());
            assertEquals(2, run.status().closesSeen());

            run.day(FIRST_DAY + 2, 400);
            RestoredSnapshot first = closeSnapshot(store, FIRST_DAY);
            assertTrue(first.inboundPosition() > SEG && first.outboundPosition() > SEG, "day one fills a segment");
            run.runRetention();
            // Three closes: the third most recent is the first day's, so the first day goes (R1).
            assertEquals(base(first.inboundPosition()), run.status().inbound().startPosition());
            assertEquals(base(first.outboundPosition()), run.status().outbound().startPosition());

            run.day(FIRST_DAY + 3, 400);
            RestoredSnapshot second = closeSnapshot(store, FIRST_DAY + 1);
            run.runRetention();
            assertEquals(3, run.status().closesSeen(), "the first day's close snapshot is pruned (R3)");
            assertNull(closeSnapshot(store, FIRST_DAY));
            assertEquals(base(second.inboundPosition()), run.status().inbound().startPosition(),
                    "four closes: the window starts at the third most recent close");
            assertEquals(base(second.outboundPosition()), run.status().outbound().startPosition());
            assertNotEquals(JournalStatus.SKIPPED, run.status().outcome());

            Path archive = run.venue.config().archiveDir();
            awaitNoSegmentBelow(archive, run.inboundId(), base(second.inboundPosition()));
            awaitNoSegmentBelow(archive, run.outboundId(), base(second.outboundPosition()));
        }
    }

    /** Purge, stop, restart from the retained close snapshot (amendment 6: L, E at L, then L + 1). */
    @Test
    void aPurgedJournalRestartsToAnIdenticalStateFromARetainedSnapshot() throws IOException {
        List<String> before;
        CapturingExecutionHandler.Observed last;
        long firstClose;
        try (Run run = new Run(config(root, 0), 1_000L, 1)) {
            run.day(FIRST_DAY, 500);
            run.open(FIRST_DAY + 1);
            run.trade(50);
            run.runRetention();
            assertTrue(run.status().inbound().startPosition() > 0, "the inbound start moved");
            assertTrue(run.status().outbound().startPosition() > 0, "the outbound start moved");
            firstClose = closeSnapshot(new SnapshotStore(run.venue.config().snapshotDir()), FIRST_DAY).inboundSeq();
            run.trade(20);
            List<CapturingExecutionHandler.Observed> seen = run.execs.snapshot();
            last = seen.get(seen.size() - 1);
            run.close();   // graceful: drains, then a shutdown snapshot (removed below)
            before = EngineStates.describe(run.venue.engine());
        }
        // As after a crash: only the close snapshot is left, so recovery replays the second day's commands.
        Path dir = JournaledVenue.config(root).snapshotDir();
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                if (!f.getFileName().toString().equals(SnapshotStore.fileName(firstClose))) Files.delete(f);
            }
        }

        try (Run again = new Run(config(root, 0), 10_000_000L, 1_000_000)) {
            assertEquals(firstClose, again.pipeline().recoveredSnapshotSeq());
            assertTrue(again.pipeline().replayedCommands() > 70, "the second day was replayed");
            assertEquals(before, EngineStates.describe(again.venue.engine()), "identical state");
            // L and the event E at L, from the tail of the purged outbound recording.
            assertEquals(last.seq(), again.pipeline().outboundFloor());
            ExecutionEvent e = again.pipeline().lastRecordedAtOpen();
            assertEquals(last.seq(), e.seq);
            assertEquals(last.eventType(), e.eventType);
            assertEquals(last.orderId(), e.orderId);
            assertEquals(last.timestamp(), e.timestamp);
            // The next live event is L + 1.
            again.gateway.onFrame(FixMessages.limit(again.nextId++, '2', "200.00", 1));
            List<CapturingExecutionHandler.Observed> next = again.execs.awaitAtLeast(1, 10_000);
            assertEquals(last.seq() + 1, next.get(next.size() - 1).seq());
        }
    }

    /** R1: the oldest usable snapshot floors the inbound start, then the window does once it is gone. */
    @Test
    void aPurgeNeverPassesTheOldestUsableSnapshot() throws IOException {
        ExchangeConfig config = config(root, 0, Map.of(ExchangeConfig.SNAPSHOT_INTERVAL, "1000"));
        try (Run run = new Run(config, 1_000L, 1)) {
            SnapshotStore store = new SnapshotStore(config.snapshotDir());
            Path dir = config.snapshotDir();
            run.open(FIRST_DAY);
            run.trade(600);   // past seq 1000: a periodic snapshot
            await(() -> !valid(store).isEmpty(), "a periodic snapshot");
            List<RestoredSnapshot> found = valid(store);
            long earlySeq = found.get(found.size() - 1).inboundSeq();
            Path earlyFile = dir.resolve(SnapshotStore.fileName(earlySeq));
            byte[] early = Files.readAllBytes(earlyFile);
            run.trade(1200);  // more periodic snapshots, then the close: the early one is pruned
            run.close(FIRST_DAY);
            await(() -> !Files.exists(earlyFile), "the early snapshot pruned");
            Files.write(earlyFile, early);   // an older recovery point back on disk
            long earlyPosition = minPosition(store);
            RestoredSnapshot close = closeSnapshot(store, FIRST_DAY);
            assertTrue(base(close.inboundPosition()) > base(earlyPosition), "the window alone would purge more");

            run.runRetention();
            assertEquals(base(earlyPosition), run.status().inbound().startPosition(), "the oldest snapshot floors it");
            assertEquals(base(close.outboundPosition()), run.status().outbound().startPosition(),
                    "no snapshot floor on the outbound stream");

            Files.delete(earlyFile);
            long next = minPosition(store);
            assertTrue(base(next) > base(earlyPosition));
            run.runRetention();
            assertEquals(base(next), run.status().inbound().startPosition(), "the next oldest snapshot floors it");
        }
    }

    /** The Reporter's committed checkpoint floors both streams; any state but RUNNING holds retention (R7). */
    @Test
    void theReporterCheckpointFloorsAndANotRunningReporterHoldsRetention() {
        ExchangeConfig config = config(root, 0, Map.of(ExchangeConfig.DB_URL, "jdbc:postgresql://127.0.0.1:1/none"));
        try (Run run = new Run(config, 1_000L, 1)) {
            FakeReporter reporter = new FakeReporter();
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);

            run.runRetention();
            assertEquals(JournalStatus.SKIPPED, run.status().outcome(), "DB_URL set, no Reporter attached");
            assertTrue(run.status().skipReason().startsWith("REPORTER_NOT_RUNNING"), run.status().skipReason());

            run.pipeline().attachReporter(reporter);
            run.runRetention();
            assertTrue(run.status().skipReason().contains("no committed checkpoint"), run.status().skipReason());
            assertEquals(0, run.status().inbound().startPosition());

            for (String state : List.of("BACKING_OFF", "STARTING", "FAILED", "STOPPED")) {
                reporter.state = state;
                reporter.inbound = 3L * SEG + 10;
                reporter.outbound = 3L * SEG + 10;
                run.runRetention();
                assertEquals(JournalStatus.SKIPPED, run.status().outcome(), state);
                assertEquals("REPORTER_NOT_RUNNING: " + state, run.status().skipReason());
                assertEquals(0, run.status().inbound().startPosition(), state + " holds retention");
                assertEquals(0, run.status().outbound().startPosition(), state + " holds retention");
            }

            reporter.state = "RUNNING";
            reporter.inbound = SEG + 10;
            reporter.outbound = 2L * SEG + 10;
            run.runRetention();
            assertNotEquals(JournalStatus.SKIPPED, run.status().outcome(), String.valueOf(run.status().skipReason()));
            assertEquals(SEG, run.status().inbound().startPosition(), "never past the inbound checkpoint");
            assertEquals(2L * SEG, run.status().outbound().startPosition(), "never past the outbound checkpoint");
        }
    }

    /** R2: whatever the floors allow, the segment holding the last recorded byte stays. */
    @Test
    void theActiveSegmentSurvives() {
        try (Run run = new Run(config(root, 0), 1_000L, 1)) {
            run.day(FIRST_DAY, 600);   // the close is the last command: the window floor is the recording's end
            run.runRetention();
            for (JournalStatus.Recording r : List.of(run.status().inbound(), run.status().outbound())) {
                long lastSegment = base(r.recordedPosition() - 1);
                assertTrue(r.startPosition() > 0, "something was purged");
                assertTrue(r.startPosition() <= lastSegment, "the last segment is kept");
                awaitNoSegmentBelow(run.venue.config().archiveDir(), r.recordingId(), r.startPosition());
                assertTrue(Journal.segmentFiles(run.venue.config().archiveDir(), r.recordingId())
                        .containsKey(lastSegment), "its file is on disk");
                assertTrue(r.bytesOnDisk() > 0);
            }
        }
    }

    /** R4: a purged inbound journal with no snapshot at or after its start refuses, appending nothing. */
    @Test
    void aPurgedJournalWithoutAUsableSnapshotRefusesStartup() {
        try (Run run = new Run(config(root, 0), 1_000L, 1)) {
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);
            run.runRetention();
            assertTrue(run.status().inbound().startPosition() > 0);
        }
        RecoveryScenario.deleteTree(JournaledVenue.config(root).snapshotDir());
        RecordingDescriptor inBefore = describe(config(root, 0), Journal.INBOUND_STREAM_ID);
        RecordingDescriptor outBefore = describe(config(root, 0), Journal.OUTBOUND_STREAM_ID);

        RecoveryException refused = assertThrows(RecoveryException.class,
                () -> JournaledVenue.create(config(root, 0), JournaledPipeline.Seams.NONE));
        assertTrue(refused.getMessage().contains("journal retention removed everything before it"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("full replay from seq 1, which is no longer possible"));

        RecordingDescriptor inAfter = describe(config(root, 0), Journal.INBOUND_STREAM_ID);
        RecordingDescriptor outAfter = describe(config(root, 0), Journal.OUTBOUND_STREAM_ID);
        assertEquals(inBefore.stopPosition(), inAfter.stopPosition(), "nothing appended to the inbound journal");
        assertEquals(outBefore.stopPosition(), outAfter.stopPosition(), "nothing appended to the outbound journal");
    }

    /** R5: a resend from seq 1 after a purge gets the earliest event the history still holds, onward. */
    @Test
    void aResendBelowTheStartResendsFromTheEarliestAvailableSeq() {
        try (Run run = new Run(config(root, 0), 1_000L, 1)) {
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);
            run.trade(10);
            run.runRetention();
            awaitNoSegmentBelow(run.venue.config().archiveDir(), run.outboundId(),
                    run.status().outbound().startPosition());

            ExecutionHistory history = run.pipeline().outboundHistory();
            long through = history.publishedSeq();
            List<Long> seqs = new ArrayList<>();
            ExecutionHistory.Result result = history.replay(1, through, event -> {
                seqs.add(event.seq);
                return true;
            });
            assertTrue(result.firstSeq() > 1, "the oldest events are gone");
            assertEquals(result.firstSeq(), seqs.get(0));
            assertEquals(through, result.lastSeq());
            for (int i = 1; i < seqs.size(); i++) assertEquals(seqs.get(i - 1) + 1, seqs.get(i), "contiguous");
            assertEquals(through - result.firstSeq() + 1, result.count());
        }
    }

    /** A process killed between the catalog update and the deletes: the start moved, the files are still there. */
    @Test
    void aCrashMidPurgeLeavesARecordingThatRecoversAndIsCleanedUp() {
        List<String> before;
        try (Run run = new Run(config(root, 0, OFF), 1_000L, 1)) {
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);
            run.trade(10);
            before = EngineStates.describe(run.venue.engine());
        }
        ExchangeConfig config = config(root, 0);
        long detachedTo;
        long inboundId;
        try (Journal journal = Journal.launch(config)) {
            RecordingDescriptor in = journal.findRecording(Journal.INBOUND_STREAM_ID);
            inboundId = in.recordingId();
            RestoredSnapshot close = closeSnapshot(new SnapshotStore(config.snapshotDir()), FIRST_DAY);
            detachedTo = base(close.inboundPosition());
            assertTrue(detachedTo > 0);
            journal.archive().detachSegments(in.recordingId(), detachedTo);   // the catalog only, as before a crash
        }
        assertTrue(Journal.segmentFiles(config.archiveDir(), inboundId).firstKey() < detachedTo,
                "the detached files are still on disk");

        try (Run again = new Run(config, 10_000_000L, 1_000_000)) {
            assertEquals(before, EngineStates.describe(again.venue.engine()), "recovered from the retained snapshot");
            assertEquals(detachedTo, again.status().inbound().startPosition());
            again.runRetention();
            awaitNoSegmentBelow(config.archiveDir(), inboundId, detachedTo);
        }
    }

    /** Retention running beside live matching changes nothing the venue produces (the D11 gate is untouched). */
    @Test
    void retentionBesideLiveMatchingChangesNothingTheVenueProduces() {
        List<String> withRetention;
        List<CapturingExecutionHandler.Observed> execsWith;
        try (Run run = new Run(config(root, 0), 1_000L, 1)) {
            for (int d = 0; d < 4; d++) {
                run.open(FIRST_DAY + d);
                for (int k = 0; k < 4; k++) {
                    run.pipeline().retention().requestRun();   // runs while commands flow
                    run.trade(80);
                }
                run.close(FIRST_DAY + d);
            }
            run.runRetention();
            assertTrue(run.status().inbound().startPosition() > 0, "retention purged while trading");
            withRetention = EngineStates.describe(run.venue.engine());
            execsWith = run.execs.snapshot();
            assertEquals(run.pipeline().journaledSeq(), run.pipeline().appliedSeq());
        }
        List<String> without;
        List<CapturingExecutionHandler.Observed> execsWithout;
        Map<String, String> off = Map.of(ExchangeConfig.JOURNAL_RETENTION_ENABLED, "false");
        try (Run run = new Run(config(other, 0, off), 1_000L, 1)) {
            for (int d = 0; d < 4; d++) {
                run.open(FIRST_DAY + d);
                for (int k = 0; k < 4; k++) run.trade(80);
                run.close(FIRST_DAY + d);
            }
            run.runRetention();
            assertEquals(JournalStatus.DISABLED, run.status().outcome());
            assertEquals(0, run.status().inbound().startPosition(), "disabled: nothing purged");
            without = EngineStates.describe(run.venue.engine());
            execsWithout = run.execs.snapshot();
        }
        assertEquals(without, withRetention);
        assertEquals(execsWithout, execsWith, "every event identical, seq by seq");
        for (int i = 0; i < execsWith.size(); i++) assertEquals(i + 1, execsWith.get(i).seq(), "seqs contiguous");
    }

    /** (g) A replay reading below the candidate makes the Archive refuse the purge; once it ends, the purge runs. */
    @Test
    void aResendInProgressBelowTheNewStartHoldsTheOutboundPurge() throws Exception {
        try (Run run = new Run(config(root, 0), 1_000L, 1)) {
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ExecutionHistory history = run.pipeline().outboundHistory();
            long through = history.publishedSeq();
            Thread resend = new Thread(() -> {
                try {
                    history.replay(1, through, event -> {
                        holding.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return true;
                    });
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-resend");
            resend.start();
            assertTrue(holding.await(10, TimeUnit.SECONDS), "the resend is reading the first segment");

            run.runRetention();
            assertEquals(JournalStatus.SKIPPED, run.status().outcome());
            assertTrue(run.status().skipReason().startsWith("ARCHIVE_REFUSED_REPLAY"), run.status().skipReason());
            assertEquals(0, run.status().outbound().startPosition(), "the outbound start held");

            release.countDown();
            resend.join(20_000);
            assertFalse(resend.isAlive());
            assertNull(failure.get());
            // The Archive ends the replay session asynchronously after the subscription closes.
            await(() -> {
                run.runRetention();
                return run.status().outbound().startPosition() > 0;
            }, "the purge once the resend ended");
        }
    }

    /**
     * Amendment 2: the Archive's check uses a replay's current segment, not where it started, so a
     * long-lived follower (as the Reporter's) that started at the old start and has read past the
     * candidate does not block the purge.
     */
    @Test
    void aFollowerThatHasReadPastTheCandidateDoesNotBlockThePurge() {
        try (Run run = new Run(config(root, 0), 1_000L, 1)) {
            run.open(FIRST_DAY);
            run.trade(600);
            AeronArchive archive = run.venue.journal().archive();
            List<Subscription> followers = new ArrayList<>();
            List<Image> images = new ArrayList<>();
            try {
                int streamId = 1190;
                // Both start at position 0, before anything is purged, as a Reporter resuming an old checkpoint.
                for (long recordingId : List.of(run.inboundId(), run.outboundId())) {
                    Subscription sub = archive.replay(recordingId, 0L, AeronArchive.REPLAY_ALL_AND_FOLLOW,
                            Journal.IPC_CHANNEL, streamId++);
                    followers.add(sub);
                    await(() -> sub.imageCount() > 0, "the follower's image");
                    images.add(sub.imageAtIndex(0));
                }
                run.close(FIRST_DAY);
                run.open(FIRST_DAY + 1);
                for (int i = 0; i < 2; i++) {
                    Image image = images.get(i);
                    long end = archive.getMaxRecordedPosition(i == 0 ? run.inboundId() : run.outboundId());
                    await(() -> {
                        image.poll((buffer, offset, length, header) -> { }, 256);
                        return image.position() >= end;
                    }, "the follower read past the candidate to the live end");
                }
                run.runRetention();
                assertNotEquals(JournalStatus.SKIPPED, run.status().outcome(), String.valueOf(run.status().skipReason()));
                assertTrue(run.status().inbound().startPosition() > 0, "purged below a follower that started at 0");
                assertTrue(run.status().outbound().startPosition() > 0, "purged below a follower that started at 0");
            } finally {
                followers.forEach(Subscription::close);
            }
        }
    }

    /** Amendment 4: outbound purged, inbound whole, no snapshot: the inbound legacy scan runs and recovery succeeds. */
    @Test
    void aPurgedOutboundRecordingWithAWholeInboundJournalRecoversByFullReplay() {
        List<String> before;
        long commands;
        try (Run run = new Run(config(root, 0, OFF), 1_000L, 1)) {
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);
            run.trade(10);
            before = EngineStates.describe(run.venue.engine());
            commands = run.pipeline().appliedSeq();
        }
        ExchangeConfig config = config(root, 0);
        RecoveryScenario.deleteTree(config.snapshotDir());
        try (Journal journal = Journal.launch(config)) {
            RecordingDescriptor out = journal.findRecording(Journal.OUTBOUND_STREAM_ID);
            journal.archive().purgeSegments(out.recordingId(), base(out.stopPosition() - 1));
        }
        try (Run again = new Run(config, 10_000_000L, 1_000_000)) {
            assertEquals(-1, again.pipeline().recoveredSnapshotSeq());
            assertEquals(commands, again.pipeline().replayedCommands(), "the whole inbound journal");
            assertEquals(before, EngineStates.describe(again.venue.engine()));
        }
    }

    /** A version 1 snapshot (from before P16-7) still recovers the venue. */
    @Test
    void aVersion1SnapshotStillRecovers() throws IOException {
        List<String> before;
        long seq;
        try (Run run = new Run(config(root, 0), 1_000L, 1)) {
            run.open(FIRST_DAY);
            run.trade(30);
            before = EngineStates.describe(run.venue.engine());
            seq = run.pipeline().appliedSeq();
        }
        Path file = JournaledVenue.config(root).snapshotDir().resolve(SnapshotStore.fileName(seq));
        Files.write(file, SnapshotFiles.asVersion1(Files.readAllBytes(file)));
        try (Run again = new Run(config(root, 0), 10_000_000L, 1_000_000)) {
            assertEquals(seq, again.pipeline().recoveredSnapshotSeq());
            assertEquals(0, again.pipeline().replayedCommands());
            assertEquals(before, EngineStates.describe(again.venue.engine()));
        }
    }

    /** JOURNAL_RETENTION_ENABLED=false: nothing is purged and /health says DISABLED. */
    @Test
    void disabledRetentionPurgesNothing() {
        Map<String, String> off = Map.of(ExchangeConfig.JOURNAL_RETENTION_ENABLED, "false");
        try (Run run = new Run(config(root, 0, off), 1_000L, 1)) {
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);
            run.runRetention();
            assertEquals(JournalStatus.DISABLED, run.status().outcome());
            assertFalse(run.status().enabled());
            assertEquals(0, run.status().inbound().startPosition());
            assertEquals(0, run.status().outbound().startPosition());
        }
    }
}
