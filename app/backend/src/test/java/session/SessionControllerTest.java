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
import model.Prices;
import org.junit.jupiter.api.Test;
import util.VenueStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The session controller's startup decisions (P15-8, decision 5) against the real engine rules: the
 * gateway publishes into a raw ring, and the test's "gateway thread" executor applies each published
 * command to a real {@link MatchingEngineHandler}, whose session word is the status the controller
 * reads. Today is fixed by a clock; the business day follows New York.
 */
class SessionControllerTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** 03:00 UTC on 3 November is still 2 November in New York. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneOffset.UTC);
    private static final int TODAY = 20261102;
    private static final SessionReference ASML = new SessionReference("ASML", Prices.TICK, 1L, Prices.SCALE);

    private final RingBuffer<OrderEvent> ring = RingBuffer.createSingleProducer(new OrderEventFactory(), 64);
    private final OrderGateway gateway = new OrderGateway(ring, () -> 1L);
    private final MatchingEngine engine = new MatchingEngine();
    private final MatchingEngineHandler handler = new MatchingEngineHandler(engine, new OutboundPipeline().getRingBuffer());
    private final List<OrderEventType> issued = new ArrayList<>();
    private long applied = -1;
    private boolean applying = true;

    /** Runs the publish, then applies every newly published command, as the journal and engine would. */
    private final Executor gatewayThread = task -> {
        task.run();
        while (applying && applied < ring.getCursor()) {
            OrderEvent command = ring.get(++applied);
            issued.add(command.eventType);
            handler.onCommand(command);
        }
    };

    private final VenueStatus status = new VenueStatus() {
        @Override public boolean running() { return true; }
        @Override public long appliedSeq() { return applied + 1; }
        @Override public long journaledSeq() { return ring.getCursor() + 1; }
        @Override public long publishedSeq() { return handler.getLastSeq(); }
        @Override public long recordedSeq() { return handler.getLastSeq(); }
        @Override public long session() { return handler.sessionWord(); }
    };

    private SessionController controller(boolean autoOpen, SessionReference reference, long timeoutMs) {
        return new SessionController(reference, NEW_YORK, CLOCK, autoOpen, gateway, gatewayThread, status, timeoutMs);
    }

    private SessionController controller(boolean autoOpen) {
        return controller(autoOpen, ASML, 1_000L);
    }

    @Test
    void todayIsTheCalendarDateInTheSessionsTimeZone() {
        assertEquals(TODAY, controller(true).today());
        assertEquals(20261103, new SessionController(ASML, ZoneOffset.UTC, CLOCK, true, gateway, gatewayThread,
                status, 1_000L).today());
    }

    @Test
    void aNeverOpenedVenueWithAutoOpenOpensToday() {
        controller(true).startup();
        assertEquals(List.of(OrderEventType.SESSION_OPEN), issued);
        assertEquals(VenueStatus.sessionWord(true, TODAY, -1), handler.sessionWord());
        byte[] symbol = new byte[8];
        handler.getSessionSymbol(symbol);
        assertArrayEquals(Sessions.symbol("ASML"), symbol);
    }

    @Test
    void withoutAutoOpenNothingIsIssued() {
        controller(false).startup();
        assertTrue(issued.isEmpty());
        assertFalse(handler.isSessionOpen());
    }

    @Test
    void aSessionRecoveredOpenTodayResumesWithNothingIssued() {
        handler.restoreSession(true, TODAY, -1, Sessions.symbol());
        controller(true).startup();
        assertTrue(issued.isEmpty());
        assertTrue(handler.isSessionOpen());
    }

    @Test
    void aStaleOpenSessionIsClosedThenTodayIsOpened() {
        handler.restoreSession(true, 20261030, -1, Sessions.symbol());
        controller(true).startup();
        assertEquals(List.of(OrderEventType.SESSION_CLOSE, OrderEventType.SESSION_OPEN), issued);
        assertEquals(TODAY, handler.getBusinessDate());
        assertEquals(20261030, handler.getLastClosedBusinessDate());
    }

    @Test
    void aStaleOpenSessionIsClosedEvenWithoutAutoOpen() {
        handler.restoreSession(true, 20261030, -1, Sessions.symbol());
        controller(false).startup();
        assertEquals(List.of(OrderEventType.SESSION_CLOSE), issued);
        assertFalse(handler.isSessionOpen(), "never trades on a stale business date");
    }

    @Test
    void anOpenOrClosedDateAfterTodayRefusesStartupAndIssuesNothing() {
        handler.restoreSession(true, 20261103, -1, Sessions.symbol());
        SessionStartupException open = assertThrows(SessionStartupException.class, () -> controller(true).startup());
        assertTrue(open.getMessage().contains("SESSION_TIME_ZONE"), open.getMessage());

        handler.restoreSession(false, -1, 20261103, Sessions.symbol());
        assertThrows(SessionStartupException.class, () -> controller(true).startup());
        assertTrue(issued.isEmpty());
    }

    @Test
    void todayAlreadyClosedIsNotReopened() {
        handler.restoreSession(false, -1, TODAY, Sessions.symbol());
        controller(true).startup();
        assertTrue(issued.isEmpty(), "the engine would refuse to reopen a closed day");
        assertFalse(handler.isSessionOpen());
    }

    @Test
    void referenceDataTheEngineWouldRefuseStopsStartupBeforeAnythingIsJournaled() {
        SessionReference badTick = new SessionReference("ASML", 1L, 1L, Prices.SCALE);
        SessionStartupException e = assertThrows(SessionStartupException.class,
                () -> controller(true, badTick, 1_000L).startup());
        assertTrue(e.getMessage().contains("SESSION_TICK_SIZE"), e.getMessage());
        assertEquals(-1, ring.getCursor(), "nothing reached the gateway");
    }

    @Test
    void aCommandTheEngineDoesNotApplyInTimeFails() {
        applying = false;   // published, never applied
        SessionStartupException e = assertThrows(SessionStartupException.class,
                () -> controller(true, ASML, 100L).startup());
        assertTrue(e.getMessage().contains("did not apply SESSION_OPEN " + TODAY), e.getMessage());
    }

    @Test
    void closeAndOpenCheckTheRulesBeforeIssuing() {
        SessionController controller = controller(true);
        assertThrows(SessionStartupException.class, () -> controller.close(TODAY), "nothing open");
        controller.open(TODAY);
        assertThrows(SessionStartupException.class, () -> controller.open(TODAY), "already open");
        assertThrows(SessionStartupException.class, () -> controller.close(20261101), "another day");
        controller.close(TODAY);
        assertEquals(List.of(OrderEventType.SESSION_OPEN, OrderEventType.SESSION_CLOSE), issued);
    }
}
