package integration;

import config.ExchangeConfig;
import db.Database;
import db.DatabaseReferenceSource;
import db.Migrator;
import db.TestDatabase;
import engine.CommandHandler;
import event.OrderEventType;
import gateway.OrderGateway;
import journal.JournaledPipeline;
import market.MarketDataService;
import net.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import publisher.TradeLogger;
import publisher.WebSocketPublisher;
import session.MutableClock;
import session.ReferenceSource;
import session.ScriptedSource;
import session.SessionController;
import session.SessionStartupException;
import session.VenueStartup;
import util.EpochNanoClock;
import util.VenueStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VenueStartup} on a real server and pipeline, in the cases P15-9 added: a venue that failed
 * during startup never binds; a database that cannot be reached starts the venue with no fallback to
 * configuration; and the running venue's roll goes through the gateway's thread.
 */
class VenueStartupTest {

    private static final int DAY = JournaledVenue.BUSINESS_DATE;

    @TempDir
    Path root;

    /** One venue on a real server, wired as Main wires it. */
    private final class Running implements AutoCloseable {
        final JournaledVenue venue;
        final OrderGateway gateway;
        final WebSocketServer server;

        Running(JournaledVenue venue, int port) {
            this.venue = venue;
            this.gateway = venue.gateway();
            this.server = new WebSocketServer(port, gateway, new EpochNanoClock(), null, venue.pipeline());
            WebSocketPublisher publisher = new WebSocketPublisher(server.getChannelGroup(), server.getBookFrameCache());
            venue.outbound().handleEventsWith(publisher.executionHandler(), new TradeLogger());
            venue.snapshots().handleEventsWith(publisher.snapshotHandler(), new MarketDataService());
        }

        SessionController start(Clock clock, ReferenceSource source) throws InterruptedException {
            return VenueStartup.start(venue.config(), venue.pipeline(), server, gateway, clock, source);
        }

        String health() throws Exception {
            return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.boundPort() + "/health")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        }

        @Override
        public void close() throws Exception {
            server.stop();
            venue.close();
        }
    }

    /** A source that cannot reach its database: the production classes, with short delays. */
    private static DatabaseReferenceSource unreachable() {
        Database database = new Database(TestDatabase.UNREACHABLE_URL, "exchange", "unused", TestDatabase.FAST);
        DatabaseReferenceSource source = new DatabaseReferenceSource(database, new Migrator(database, true), "ASML");
        assertFalse(source.prepare());
        return source;
    }

    private static List<OrderEventType> sessionCommands(List<OrderEventType> seen) {
        synchronized (seen) {
            return new ArrayList<>(seen);
        }
    }

    private static CommandHandler tapInto(List<OrderEventType> seen) {
        return command -> {
            if (command.eventType == OrderEventType.SESSION_OPEN || command.eventType == OrderEventType.SESSION_CLOSE) {
                seen.add(command.eventType);
            }
        };
    }

    @Test
    void aVenueThatFailsOnItsOpenNeverBinds() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        ExchangeConfig config = JournaledVenue.config(root);
        JournaledVenue venue = JournaledVenue.create(config, new JournaledPipeline.Seams(null, null, seq -> {
            throw new IllegalStateException("recorder failure on seq " + seq);
        }));
        Running running = new Running(venue, port);
        SessionStartupException e = assertThrows(SessionStartupException.class,
                () -> running.start(JournaledVenue.CLOCK, new ScriptedSource()));
        assertTrue(e.getMessage().contains("fail-stop"), e.getMessage());
        assertFalse(venue.pipeline().running());
        try (Socket client = new Socket()) {
            assertThrows(IOException.class, () -> client.connect(new InetSocketAddress("localhost", port), 3_000),
                    "the port was never bound");
        }
        venue.closeAfterFatal();
    }

    @Test
    void anUnreachableDatabaseStartsTheVenueClosedWithNoSessionJournaled() throws Exception {
        List<OrderEventType> commands = Collections.synchronizedList(new ArrayList<>());
        try (Running running = new Running(JournaledVenue.create(root, tapInto(commands)), 0)) {
            running.start(JournaledVenue.CLOCK, unreachable());
            assertEquals(0L, running.venue.pipeline().session(), "never opened");
            assertEquals(0L, running.venue.pipeline().journaledSeq(), "nothing was journaled");
            assertTrue(sessionCommands(commands).isEmpty());
            assertTrue(running.venue.pipeline().running());
            assertTrue(running.health().contains("\"session\":\"CLOSED\""), running.health());
        }
    }

    @Test
    void anUnreachableDatabaseNeverStopsARecoveredOpenDay() throws Exception {
        try (Running first = new Running(JournaledVenue.create(root), 0)) {
            first.start(JournaledVenue.CLOCK, new ScriptedSource());
            assertEquals(VenueStatus.sessionWord(true, DAY, -1), first.venue.pipeline().session());
        }
        List<OrderEventType> commands = Collections.synchronizedList(new ArrayList<>());
        try (Running second = new Running(JournaledVenue.create(root, tapInto(commands)), 0)) {
            second.start(JournaledVenue.CLOCK, unreachable());
            assertEquals(VenueStatus.sessionWord(true, DAY, -1), second.venue.pipeline().session(), "trading resumes");
            assertTrue(sessionCommands(commands).isEmpty(), "no second open");
            assertTrue(second.health().contains("\"session\":\"OPEN\""), second.health());
        }
    }

    @Test
    void theRollPublishesOnTheWorkerAndReadsTheSourceOffTheEventLoop() throws Exception {
        MutableClock clock = new MutableClock("2026-11-02T17:00:00Z");
        ScriptedSource source = new ScriptedSource();
        try (Running running = new Running(JournaledVenue.create(root), 0)) {
            SessionController sessions = running.start(clock, source);
            Thread producer = running.gateway.producerThread();
            assertTrue(running.server.gatewayExecutor().inEventLoop(producer));

            clock.set("2026-11-03T05:00:01Z");
            Thread scheduler = new Thread(sessions::roll, "session-scheduler-test");
            scheduler.start();
            scheduler.join(15_000L);
            assertFalse(scheduler.isAlive());

            assertEquals(VenueStatus.sessionWord(true, DAY + 1, -1), running.venue.pipeline().session());
            assertSame(producer, running.gateway.producerThread(), "the inbound ring keeps one producer");
            assertEquals(List.of(Thread.currentThread().getName(), "session-scheduler-test"), source.loadThreads,
                    "the startup thread, then the scheduler's; never an event loop or the engine thread");
        }
    }
}
