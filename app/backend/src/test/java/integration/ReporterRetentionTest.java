package integration;

import config.ExchangeConfig;
import db.RequiresTestDatabase;
import db.TestDatabase;
import io.aeron.archive.client.AeronArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import recovery.SnapshotStore;
import reporter.Reporter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Journal retention and the Reporter on the test database (P16-7): the committed checkpoint floors
 * both recordings; a RUNNING Reporter survives a purge and a venue restart and catches up from its
 * checkpoint; a database whose checkpoint the journal no longer holds (none at all, or one restored
 * from an old backup) stops the Reporter with the remedy, never starting mid-history (f).
 */
@RequiresTestDatabase
class ReporterRetentionTest {

    private static final int SEG = 64 * 1024;
    private static final int FIRST_DAY = 20261102;

    @TempDir
    Path root;

    @BeforeEach
    void cleanDatabase() {
        TestDatabase.cleanAndMigrate();
    }

    private static ExchangeConfig config(Path root, boolean withDatabase) {
        Map<String, String> env = withDatabase ? TestDatabase.venueEnvironment() : new HashMap<>();
        env.put(ExchangeConfig.JOURNAL_RETENTION_DAYS, "0");
        return JournaledVenue.config(root, env);
    }

    private static Reporter attach(JournalRetentionTest.Run run) {
        Reporter reporter = ReporterHarness.reporter(run.venue);
        run.pipeline().attachReporter(reporter);
        reporter.start();
        return reporter;
    }

    private static void caughtUp(JournalRetentionTest.Run run, Reporter reporter) {
        ReporterHarness.await("the recorder to settle", () -> run.pipeline().recordedSeq() == run.pipeline().publishedSeq());
        ReporterHarness.awaitCheckpoint(reporter, run.pipeline().recordedSeq());
    }

    private static long base(long position) {
        return AeronArchive.segmentFileBasePosition(0, position, SEG, SEG);
    }

    @Test
    void theCommittedCheckpointFloorsBothRecordings() {
        try (JournalRetentionTest.Run run = new JournalRetentionTest.Run(config(root, true), 1_000L, 1)) {
            Reporter reporter = attach(run);
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);
            caughtUp(run, reporter);
            long in = reporter.checkpointInboundPosition();
            long out = reporter.checkpointOutboundPosition();
            assertTrue(in > SEG && out > SEG, "committed positions are published: " + in + ", " + out);
            assertTrue(in <= run.status().inbound().recordedPosition());

            run.runRetention();
            assertTrue(run.status().inbound().startPosition() > 0, String.valueOf(run.status().skipReason()));
            assertTrue(run.status().inbound().startPosition() <= base(in), "never past the inbound checkpoint");
            assertTrue(run.status().outbound().startPosition() <= base(out), "never past the outbound checkpoint");
            assertTrue(reporter.stop());
        }
    }

    /** Amendment 6: RUNNING Reporter, purge, venue restart: it resumes from its checkpoint and reaches lag 0. */
    @Test
    void aRunningReporterSurvivesAPurgeAndAVenueRestart() {
        long startAfterPurge;
        try (JournalRetentionTest.Run run = new JournalRetentionTest.Run(config(root, true), 1_000L, 1)) {
            Reporter reporter = attach(run);
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);
            caughtUp(run, reporter);
            run.runRetention();
            startAfterPurge = run.status().inbound().startPosition();
            assertTrue(startAfterPurge > 0, String.valueOf(run.status().skipReason()));
            run.trade(20);
            caughtUp(run, reporter);
            assertTrue(reporter.stop());
        }
        try (JournalRetentionTest.Run again = new JournalRetentionTest.Run(config(root, true), 10_000_000L, 1_000_000)) {
            assertEquals(startAfterPurge, again.status().inbound().startPosition());
            Reporter reporter = attach(again);
            again.trade(20);
            caughtUp(again, reporter);
            assertEquals("RUNNING", reporter.state(), String.valueOf(reporter.failure()));
            assertEquals(again.pipeline().recordedSeq(), reporter.outboundSeq(), "lag 0");
            assertTrue(reporter.stop());
        }
    }

    /** (f) A fresh database against a purged journal: the Reporter stops with the remedy, the venue trades on. */
    @Test
    void aDatabaseWithNoCheckpointAgainstAPurgedJournalStopsTheReporter() {
        try (JournalRetentionTest.Run run = new JournalRetentionTest.Run(config(root, false), 1_000L, 1)) {
            run.day(FIRST_DAY, 600);
            run.open(FIRST_DAY + 1);
            run.runRetention();
            assertTrue(run.status().inbound().startPosition() > 0);
        }
        try (JournalRetentionTest.Run again = new JournalRetentionTest.Run(config(root, true), 10_000_000L, 1_000_000)) {
            Reporter reporter = attach(again);
            ReporterHarness.awaitState(reporter, "FAILED");
            assertTrue(reporter.failure().contains("has no checkpoint"), reporter.failure());
            assertTrue(reporter.failure().contains("cannot be rebuilt from seq 1"));
            assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM orders"), "nothing written mid-history");
            again.trade(5);
            assertTrue(again.pipeline().running(), "the venue keeps trading");
        }
    }

    /** (f) A checkpoint below the journal's start (a database restored from an old backup) stops the Reporter. */
    @Test
    void aCheckpointBelowTheStartStopsTheReporter() {
        try (JournalRetentionTest.Run run = new JournalRetentionTest.Run(config(root, true), 1_000L, 1)) {
            Reporter reporter = attach(run);
            run.open(FIRST_DAY);
            run.trade(50);
            caughtUp(run, reporter);
            assertTrue(reporter.stop());   // the database now holds an early checkpoint, like an old backup
        }
        // Periodic snapshots, so the newest three no longer include the first run's shutdown snapshot,
        // which would otherwise floor the purge at the journal's beginning.
        Map<String, String> env = new HashMap<>(Map.of(ExchangeConfig.SNAPSHOT_INTERVAL, "500",
                ExchangeConfig.JOURNAL_RETENTION_DAYS, "0"));
        try (JournalRetentionTest.Run run = new JournalRetentionTest.Run(JournaledVenue.config(root, env),
                10_000_000L, 1_000_000)) {
            run.trade(1_200);
            // Not run.close(): a periodic snapshot in flight at the close defers the close snapshot to the
            // next command (P15-5), here the next day's open; it keeps its close date (P16-7).
            run.venue.sessionController(run.gateway).close(FIRST_DAY);
            run.open(FIRST_DAY + 1);
            run.trade(5);
            SnapshotStore store = new SnapshotStore(run.venue.config().snapshotDir());
            ReporterHarness.await("the close snapshot", () -> JournalRetentionTest.closeSnapshot(store, FIRST_DAY) != null);
            run.runRetention();
            assertTrue(run.status().inbound().startPosition() > 0);
        }
        try (JournalRetentionTest.Run again = new JournalRetentionTest.Run(config(root, true), 20_000_000L, 2_000_000)) {
            Reporter reporter = attach(again);
            ReporterHarness.awaitState(reporter, "FAILED");
            assertTrue(reporter.failure().contains("restored from a backup older than the journal window"),
                    reporter.failure());
        }
    }
}
