package integration;

import clearing.Clearing;
import clearing.ClearingTiming;
import db.RequiresTestDatabase;
import db.TestDatabase;
import event.ExecutionEvent;
import event.ExecutionEventType;
import gateway.OrderGateway;
import model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reporter.Reporter;

import java.nio.file.Path;
import java.util.List;

import static integration.ReporterHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A trading day through the journaled venue, the Reporter and clearing (P15-11): the house trades with
 * itself, the controller closes the day, the Reporter records the close, and clearing completes it.
 */
@RequiresTestDatabase
class ClearingVenueTest {

    private static final int DAY = JournaledVenue.BUSINESS_DATE;
    private static final int NEXT = DAY + 1;
    /** The poll is a minute: only the Reporter's wake can clear the day within the await. */
    private static final ClearingTiming WAKE_ONLY = new ClearingTiming(60_000L, 10L, 100L, 1_000L, 5_000L);

    @TempDir
    Path root;

    @BeforeEach
    void emptySchema() {
        TestDatabase.cleanAndMigrate();
    }

    private static Clearing clearing(JournaledVenue venue, Reporter reporter) {
        return new Clearing(TestDatabase.database()::connect, reporter, venue.pipeline()::session,
                System::nanoTime, WAKE_ONLY);
    }

    /** Trades and closes; returns the SESSION_CLOSED event's outbound seq. */
    private static long tradeAndClose(JournaledVenue venue, OrderGateway gateway) {
        gateway.onFrame(limit(10L, Side.SELL, 1_000_000L, 5L));
        gateway.onFrame(limit(11L, Side.BUY, 1_000_000L, 3L));       // fills 3
        gateway.onFrame(limit(12L, Side.BUY, 1_000_100L, 4L));       // fills 2, rests 2
        venue.sessionController(gateway).close(DAY);
        List<ExecutionEvent> events = recordedEvents(venue.journal(), 6);
        ExecutionEvent closed = events.get(events.size() - 1);
        assertEquals(ExecutionEventType.SESSION_CLOSED, closed.eventType);
        return closed.seq;
    }

    /** P15-11 finding: heldBy said "reporter STARTING" until the next poll; the Reporter now wakes clearing. */
    @Test
    void clearingStopsShowingTheReporterAsHoldingOnceItRuns() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            venue.startAndOpen(venue.gateway());
            Reporter reporter = reporter(venue);
            Clearing clearing = clearing(venue, reporter);
            reporter.onRunPending(clearing::wake);
            clearing.start();
            try {
                await("clearing's first cycle", () -> "reporter STARTING".equals(clearing.heldBy()));
                reporter.start();
                // The poll is a minute, so only the Reporter's wake can clear heldBy within the await.
                await("heldBy cleared (heldBy " + clearing.heldBy() + ", reporter " + reporter.state() + ")",
                        () -> clearing.heldBy() == null && "RUNNING".equals(reporter.state()));
            } finally {
                assertTrue(clearing.stop());
                assertTrue(reporter.stop());
            }
        }
    }

    @Test
    void aClosedDayIsClearedOnceTheReporterHasIt() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            Reporter reporter = reporter(venue);
            Clearing clearing = clearing(venue, reporter);
            reporter.onRunPending(clearing::wake);
            venue.pipeline().attachClearing(clearing);
            clearing.start();
            reporter.start();
            try {
                long closeSeq = tradeAndClose(venue, gateway);
                await("clearing of " + DAY + " (state " + clearing.state() + ", failure " + clearing.failure() + ")",
                        () -> clearing.lastClearedDate() == DAY);

                assertTrue(reporter.outboundSeq() >= closeSeq);
                assertEquals(List.of("COMPLETE " + closeSeq + " " + NEXT + " 2 5"), rows("SELECT status, close_outbound_seq,"
                        + " next_business_date, fill_count, volume FROM clearing_run"));
                // Participant 1 on both sides: zero net, zero cash, and the house rolls forward flat.
                assertEquals(List.of(DAY + " 1 ASML 5 5 0 0"), rows("SELECT * FROM clearing_obligation"));
                assertEquals(List.of(NEXT + " ASML 0"), rows("SELECT * FROM house_position"));
                assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM account_balance"));
                assertSame(clearing, venue.pipeline().clearing());
                assertEquals("IDLE", clearing.state());
            } finally {
                assertTrue(clearing.stop());
                assertTrue(reporter.stop());
            }
        }
    }

    @Test
    void aSecondClearingOfTheSameDayChangesNothing() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            Reporter reporter = reporter(venue);
            Clearing first = clearing(venue, reporter);
            reporter.onRunPending(first::wake);
            first.start();
            reporter.start();
            try {
                tradeAndClose(venue, gateway);
                await("the first clearing", () -> first.lastClearedDate() == DAY);
                assertTrue(first.stop());
                List<String> before = dump();

                Clearing second = clearing(venue, reporter);
                second.start();
                await("the second clearing to settle", () -> "IDLE".equals(second.state()));
                assertEquals(DAY, second.lastClearedDate());
                assertTrue(second.stop());
                assertEquals(before, dump(), "no row changed, not even its version");
            } finally {
                assertTrue(reporter.stop());
            }
        }
    }

    private static List<String> dump() {
        return rows("SELECT xmin::text || ' ' || r::text FROM clearing_run r UNION ALL"
                + " SELECT xmin::text || ' ' || o::text FROM clearing_obligation o UNION ALL"
                + " SELECT xmin::text || ' ' || h::text FROM house_position h ORDER BY 1");
    }
}
