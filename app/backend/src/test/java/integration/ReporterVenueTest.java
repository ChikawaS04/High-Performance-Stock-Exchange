package integration;

import db.Database;
import db.TestDatabase;
import event.CapturingExecutionHandler;
import event.ExecutionEventType;
import gateway.OrderGateway;
import model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reporter.Reporter;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;

import static integration.ReporterHarness.limit;
import static org.junit.jupiter.api.Assertions.*;

/** The Reporter never touches the critical path. Needs no database: nothing here ever reaches one. */
class ReporterVenueTest {

    private static final long PRICE = 1_000_000L;

    @TempDir
    Path root;

    private static Database closedPort() {
        return new Database(TestDatabase.UNREACHABLE_URL, "nobody", "nothing", TestDatabase.FAST);
    }

    @Test
    void aVenueWithItsDatabaseAtAClosedPortKeepsTradingAndTheReporterBacksOff() {
        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            venue.outbound().handleEventsWith(execs);
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);

            Reporter reporter = ReporterHarness.reporter(venue, closedPort()::connect, null);
            venue.pipeline().attachReporter(reporter);
            reporter.start();
            ReporterHarness.awaitState(reporter, "BACKING_OFF");

            int orders = 200;
            for (int i = 0; i < orders; i++) {
                gateway.onFrame(limit(1_000L + i * 2, Side.SELL, PRICE, 1L));
                gateway.onFrame(limit(1_001L + i * 2, Side.BUY, PRICE, 1L));
            }
            int expected = 1 + orders * 2;
            assertEquals(expected, execs.awaitAtLeast(expected, 10_000).size());
            assertEquals(ExecutionEventType.ORDER_FILLED, execs.snapshot().get(expected - 1).eventType());

            assertTrue(venue.pipeline().running());
            ReporterHarness.await("the recorder to keep up", () -> venue.pipeline().recordedSeq() == expected);
            assertEquals("BACKING_OFF", reporter.state());
            assertEquals(0L, reporter.outboundSeq());
            assertSame(reporter, venue.pipeline().reporter());
            assertTrue(reporter.stop());
            assertEquals("STOPPED", reporter.state());
        }
    }

    @Test
    void itRunsOnOneThreadNamedReporter() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            venue.startAndOpen(venue.gateway());
            Thread[] seen = new Thread[1];
            Reporter reporter = ReporterHarness.reporter(venue, () -> {
                seen[0] = Thread.currentThread();
                throw new SQLException("down", "08001");
            }, null);
            reporter.start();
            ReporterHarness.await("a connection attempt", () -> seen[0] != null);
            assertEquals("reporter", seen[0].getName());
            assertTrue(seen[0].isDaemon());
            assertTrue(reporter.stop());
        }
    }

    /** Stop is bounded, and the journal closes safely under a thread stuck in JDBC: it never polls again. */
    @Test
    void stopIsBoundedWhileTheDatabaseHangsAndTheJournalStillClosesCleanly() throws Exception {
        CountDownLatch hung = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Reporter reporter;
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            venue.startAndOpen(venue.gateway());
            reporter = new Reporter(() -> {
                hung.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                throw new SQLException("down", "08001");
            }, () -> { }, venue.journal(), venue.pipeline().inboundRecording(), venue.pipeline().outboundRecording(),
                    new reporter.ReporterTiming(3, 20L, 10L, 100L, 1_000L, 5_000L, 100L), null);
            reporter.start();
            hung.await();
            assertFalse(reporter.stop(), "the thread is inside JDBC: stop reports it and moves on");
        }
        release.countDown();
        ReporterHarness.await("the abandoned thread to finish", () -> "STOPPED".equals(reporter.state()));
    }

    @Test
    void aReporterNeverStartedStopsAtOnce() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            venue.start();
            assertTrue(ReporterHarness.reporter(venue, closedPort()::connect, null).stop());
        }
    }
}
