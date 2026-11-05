package session;

import com.lmax.disruptor.RingBuffer;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import engine.Sessions;
import event.OrderEvent;
import event.OrderEventFactory;
import event.OrderEventType;
import event.OutboundPipeline;
import gateway.OrderGateway;
import org.junit.jupiter.api.Test;
import util.VenueStatus;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The running venue's calendar-driven close and open (P15-9), tick by tick with a clock the test
 * moves, against the real engine rules: the gateway publishes into a raw ring, and the test's
 * "gateway thread" executor applies each published command to a real handler, as in
 * {@link SessionControllerTest}.
 */
class SessionRollTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final int DAY_ONE = 20261102;
    private static final int DAY_TWO = 20261103;
    /** Noon on {@link #DAY_ONE} in New York. */
    private static final String NOON = "2026-11-02T17:00:00Z";
    /** One second past midnight into {@link #DAY_TWO} in New York. */
    private static final String PAST_MIDNIGHT = "2026-11-03T05:00:01Z";

    private final RingBuffer<OrderEvent> ring = RingBuffer.createSingleProducer(new OrderEventFactory(), 64);
    private final OrderGateway gateway = new OrderGateway(ring, () -> 1L);
    private final MatchingEngineHandler handler =
            new MatchingEngineHandler(new MatchingEngine(), new OutboundPipeline().getRingBuffer());
    private final List<OrderEventType> issued = new ArrayList<>();
    private final MutableClock clock = new MutableClock(NOON);
    private final ScriptedSource source = new ScriptedSource();
    private long applied = -1;
    private volatile boolean running = true;

    private final Executor gatewayThread = task -> {
        task.run();
        while (applied < ring.getCursor()) {
            OrderEvent command = ring.get(++applied);
            issued.add(command.eventType);
            handler.onCommand(command);
        }
    };

    private final VenueStatus status = new VenueStatus() {
        @Override public boolean running() { return running; }
        @Override public long appliedSeq() { return applied + 1; }
        @Override public long journaledSeq() { return ring.getCursor() + 1; }
        @Override public long publishedSeq() { return handler.getLastSeq(); }
        @Override public long recordedSeq() { return handler.getLastSeq(); }
        @Override public long session() { return handler.sessionWord(); }
    };

    private SessionController controller(boolean autoOpen) {
        return new SessionController(source, NEW_YORK, clock, autoOpen, gateway, gatewayThread, status, 1_000L);
    }

    private void openDayOne() {
        handler.restoreSession(true, DAY_ONE, -1, Sessions.symbol());
    }

    private void assertOpen(int date) {
        assertEquals(VenueStatus.sessionWord(true, date, -1), handler.sessionWord());
    }

    private void assertClosed(int lastClosed) {
        assertEquals(VenueStatus.sessionWord(false, -1, lastClosed), handler.sessionWord());
    }

    @Test
    void midnightClosesTheDayAndOpensTheNext() {
        openDayOne();
        SessionController controller = controller(true);
        controller.roll();
        assertTrue(issued.isEmpty(), "still the same day");
        assertEquals(0, source.loads(), "an open day never reads the source");

        clock.set(PAST_MIDNIGHT);
        controller.roll();
        assertEquals(List.of(OrderEventType.SESSION_CLOSE, OrderEventType.SESSION_OPEN), issued);
        assertOpen(DAY_TWO);
        assertEquals(DAY_ONE, handler.getLastClosedBusinessDate());
    }

    @Test
    void repeatedTicksIssueNothingFurther() {
        openDayOne();
        clock.set(PAST_MIDNIGHT);
        SessionController controller = controller(true);
        controller.roll();
        issued.clear();
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(1));
            controller.roll();
        }
        assertTrue(issued.isEmpty());
        assertEquals(1, source.loads());
    }

    @Test
    void theNextDayIsReadFromTheSourceAtTheRoll() {
        openDayOne();
        clock.set(PAST_MIDNIGHT);
        controller(true).roll();
        byte[] symbol = new byte[8];
        handler.getSessionSymbol(symbol);
        assertArrayEquals(Sessions.symbol("ASML"), symbol);
        assertEquals(1, source.loads());
    }

    @Test
    void aNonTradingNextDayClosesOnlyAndACalendarChangeIsPickedUp() {
        openDayOne();
        source.closedDates.add(DAY_TWO);
        clock.set(PAST_MIDNIGHT);
        SessionController controller = controller(true);
        controller.roll();
        assertEquals(List.of(OrderEventType.SESSION_CLOSE), issued);
        assertClosed(DAY_ONE);

        source.closedDates.clear();
        clock.advance(Duration.ofMillis(SessionController.SOURCE_RETRY_MS));
        controller.roll();
        assertOpen(DAY_TWO);
    }

    @Test
    void anUnavailableSourceClosesTheDayStaysClosedBacksOffThenOpens() {
        openDayOne();
        source.mode = ScriptedSource.Mode.UNAVAILABLE;
        clock.set(PAST_MIDNIGHT);
        SessionController controller = controller(true);

        controller.roll();
        assertEquals(List.of(OrderEventType.SESSION_CLOSE), issued, "the database never holds up a close");
        assertClosed(DAY_ONE);
        assertEquals(1, source.loads());

        clock.advance(Duration.ofMillis(SessionController.SOURCE_RETRY_MS - 1));
        controller.roll();
        assertEquals(1, source.loads(), "backs off");

        clock.advance(Duration.ofMillis(1));
        controller.roll();
        assertEquals(2, source.loads());
        assertClosed(DAY_ONE);

        source.mode = ScriptedSource.Mode.NORMAL;
        clock.advance(Duration.ofMillis(SessionController.SOURCE_RETRY_MS));
        controller.roll();
        assertOpen(DAY_TWO);
        assertEquals(List.of(OrderEventType.SESSION_CLOSE, OrderEventType.SESSION_OPEN), issued);
    }

    @Test
    void anUnusableSourceNeverStopsARunningVenueAndIsRetried() {
        openDayOne();
        source.mode = ScriptedSource.Mode.UNUSABLE;
        clock.set(PAST_MIDNIGHT);
        SessionController controller = controller(true);
        assertDoesNotThrow(controller::roll);
        assertClosed(DAY_ONE);

        source.mode = ScriptedSource.Mode.NORMAL;
        clock.advance(Duration.ofMillis(SessionController.SOURCE_RETRY_MS));
        controller.roll();
        assertOpen(DAY_TWO);
    }

    @Test
    void referenceDataTheRulesRefuseNeverReachesTheGatewayWhileRunning() {
        openDayOne();
        source.reference = new SessionReference("ASML", 1L, 1L, 10_000L);
        clock.set(PAST_MIDNIGHT);
        assertDoesNotThrow(controller(true)::roll);
        assertEquals(List.of(OrderEventType.SESSION_CLOSE), issued);
        assertClosed(DAY_ONE);
    }

    @Test
    void aClockMovedBackwardsTakesNoActionWhileRunning() {
        handler.restoreSession(true, DAY_TWO, -1, Sessions.symbol());
        SessionController controller = controller(true);
        assertDoesNotThrow(controller::roll);
        assertDoesNotThrow(controller::roll);
        assertTrue(issued.isEmpty());
        assertOpen(DAY_TWO);

        handler.restoreSession(false, -1, DAY_TWO, Sessions.symbol());
        assertDoesNotThrow(controller::roll);
        assertTrue(issued.isEmpty());
        assertEquals(0, source.loads());
    }

    @Test
    void withoutAutoOpenTheDayClosesAndNoneOpens() {
        openDayOne();
        clock.set(PAST_MIDNIGHT);
        SessionController controller = controller(false);
        controller.roll();
        controller.roll();
        assertEquals(List.of(OrderEventType.SESSION_CLOSE), issued);
        assertEquals(0, source.loads());
    }

    @Test
    void afterAFailStopRollReadsNothingAndPublishesNothing() {
        openDayOne();
        clock.set(PAST_MIDNIGHT);
        running = false;
        SessionController controller = controller(true);
        controller.roll();
        assertTrue(issued.isEmpty());
        assertEquals(-1, ring.getCursor(), "nothing reached the gateway");
        assertEquals(0, source.loads());
        assertOpen(DAY_ONE);

        handler.restoreSession(false, -1, DAY_ONE, Sessions.symbol());
        controller.roll();
        assertEquals(-1, ring.getCursor());
        assertEquals(0, source.loads());
    }

    @Test
    void aFailStopDuringTheReadPublishesNothing() {
        handler.restoreSession(false, -1, DAY_ONE, Sessions.symbol());
        clock.set(PAST_MIDNIGHT);
        source.duringLoad = () -> running = false;
        assertDoesNotThrow(controller(true)::roll);
        assertEquals(1, source.loads());
        assertEquals(-1, ring.getCursor(), "the check on the gateway's thread stopped it");
    }

    @Test
    void aFailStopAfterTheCloseReadsNothing() {
        openDayOne();
        clock.set(PAST_MIDNIGHT);
        Executor failingAfterClose = task -> {
            gatewayThread.execute(task);
            running = false;
        };
        new SessionController(source, NEW_YORK, clock, true, gateway, failingAfterClose, status, 1_000L).roll();
        assertEquals(List.of(OrderEventType.SESSION_CLOSE), issued);
        assertEquals(0, source.loads());
    }

    @Test
    void aRestartBetweenTheCloseAndTheOpenConvergesWithNoSecondClose() {
        handler.restoreSession(false, -1, DAY_ONE, Sessions.symbol());  // recovered: closed, the open never issued
        clock.set(PAST_MIDNIGHT);
        controller(true).startup();
        assertEquals(List.of(OrderEventType.SESSION_OPEN), issued);
        assertOpen(DAY_TWO);

        issued.clear();
        controller(true).roll();    // and the new run's first tick has nothing to do
        assertTrue(issued.isEmpty());
    }

    @Test
    void aFailureOfTheGatewaysThreadIsLoggedAndALaterTickRetries() {
        openDayOne();
        clock.set(PAST_MIDNIGHT);
        boolean[] rejecting = {true};
        Executor flaky = task -> {
            if (rejecting[0]) throw new IllegalStateException("event loop busy");
            gatewayThread.execute(task);
        };
        SessionController controller = new SessionController(source, NEW_YORK, clock, true, gateway, flaky, status, 1_000L);
        assertThrows(IllegalStateException.class, controller::roll, "the scheduler's own catch keeps it alive");
        assertOpen(DAY_ONE);

        rejecting[0] = false;
        controller.roll();
        assertOpen(DAY_TWO);
    }
}
