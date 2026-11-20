package integration;

import clearing.Clearing;
import clearing.ClearingTiming;
import clearing.DatabaseRetention;
import clearing.DatabaseRetentionTiming;
import db.RequiresTestDatabase;
import db.TestDatabase;
import gateway.OrderGateway;
import model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reporter.Reporter;
import session.SessionController;
import util.DatabaseRetentionStatus;

import java.nio.file.Path;
import java.util.List;

import static integration.ReporterHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Database retention under a real venue, Reporter and clearing (P16-8): a GTC order accepted on the first
 * day rests while that day is purged; the Reporter is restarted and resumes from its checkpoint; the order
 * then fills, its execution references the kept row, and clearing nets the day correctly.
 */
@RequiresTestDatabase
class DatabaseRetentionVenueTest {

    private static final int DAY = JournaledVenue.BUSINESS_DATE;
    private static final ClearingTiming WAKE_ONLY = new ClearingTiming(60_000L, 10L, 100L, 1_000L, 5_000L);

    @TempDir
    Path root;

    @BeforeEach
    void emptySchema() {
        TestDatabase.cleanAndMigrate();
    }

    private static Clearing clearing(JournaledVenue venue, Reporter reporter) {
        Clearing clearing = new Clearing(TestDatabase.database()::connect, reporter, venue.pipeline()::session,
                System::nanoTime, WAKE_ONLY);
        reporter.onRunPending(clearing::wake);
        return clearing;
    }

    @Test
    void aGtcOrderOutlivesThePurgeOfItsDayAndFillsCorrectlyAfterAReporterRestart() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            SessionController sessions = venue.sessionController(gateway);
            Reporter reporter = reporter(venue);
            Clearing clearing = clearing(venue, reporter);
            clearing.start();
            reporter.start();
            long gtcSeq;
            try {
                gateway.onFrame(limit(1L, Side.SELL, 1_000_500L, 10L));           // the GTC order, resting above the day's trades
                gateway.onFrame(limit(2L, Side.SELL, 1_000_000L, 5L));
                gateway.onFrame(limit(3L, Side.BUY, 1_000_000L, 5L));            // a fill on DAY
                sessions.close(DAY);
                sessions.open(DAY + 1);
                gateway.onFrame(limit(4L, Side.SELL, 1_000_200L, 2L));
                gateway.onFrame(limit(5L, Side.BUY, 1_000_200L, 2L));            // a fill on DAY + 1
                sessions.close(DAY + 1);
                sessions.open(DAY + 2);
                await("DAY + 1 cleared (state " + clearing.state() + ", failure " + clearing.failure() + ")",
                        () -> clearing.lastClearedDate() == DAY + 1);
                gtcSeq = Long.parseLong(TestDatabase.queryOne("SELECT inbound_seq FROM orders WHERE order_id = 1"));

                // One COMPLETE date kept: DAY goes.
                DatabaseRetention retention = new DatabaseRetention(TestDatabase.database()::connect, () -> reporter,
                        () -> clearing, true, 1, 1_000, true, System::nanoTime, DatabaseRetentionTiming.ON_REQUEST);
                retention.start();
                try {
                    retention.requestRun();
                    await("a retention pass", () -> retention.completedRuns() > 0);
                    assertEquals(DatabaseRetentionStatus.PURGED, retention.status().outcome(),
                            String.valueOf(retention.status().skipReason()));
                } finally {
                    assertTrue(retention.stop());
                }
                assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM executions WHERE business_date = " + DAY));
                assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM order_audit WHERE business_date = " + DAY));
                assertEquals(List.of("1 OPEN 0"), rows("SELECT order_id, status, cum_quantity FROM orders"
                        + " WHERE business_date = " + DAY), "only the resting GTC order of DAY is left");
                assertNotEquals("0", TestDatabase.queryOne("SELECT count(*) FROM executions WHERE business_date = "
                        + (DAY + 1)), "the kept day is untouched");
            } finally {
                assertTrue(clearing.stop());
                assertTrue(reporter.stop());
            }

            // A fresh Reporter resumes from its checkpoint over the purged database.
            Reporter again = reporter(venue);
            Clearing clearingAgain = clearing(venue, again);
            clearingAgain.start();
            again.start();
            try {
                awaitState(again, "RUNNING");
                gateway.onFrame(limit(6L, Side.BUY, 1_000_500L, 10L));          // fills the GTC order
                sessions.close(DAY + 2);
                await("DAY + 2 cleared (state " + clearingAgain.state() + ", failure " + clearingAgain.failure()
                        + ", reporter " + again.state() + ")", () -> clearingAgain.lastClearedDate() == DAY + 2);
                assertEquals("RUNNING", again.state());
                assertEquals(List.of("1 6 " + gtcSeq + " 1000500 10"), rows("SELECT e.passive_order_id,"
                        + " e.aggressor_order_id, e.passive_order_inbound_seq, e.price, e.filled_quantity FROM executions e"
                        + " WHERE e.trade_id IS NOT NULL AND e.business_date = " + (DAY + 2)));
                assertEquals(List.of("FILLED 10"), rows("SELECT status, cum_quantity FROM orders WHERE inbound_seq = "
                        + gtcSeq));
                assertEquals(List.of("COMPLETE 1 10"), rows("SELECT status, fill_count, volume FROM clearing_run"
                        + " WHERE business_date = " + (DAY + 2)));
                assertEquals(List.of((DAY + 2) + " 1 ASML 10 10 0 0"), rows("SELECT * FROM clearing_obligation"
                        + " WHERE business_date = " + (DAY + 2)));
            } finally {
                assertTrue(clearingAgain.stop());
                assertTrue(again.stop());
            }
        }
    }
}
