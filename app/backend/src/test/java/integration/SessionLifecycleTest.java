package integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import event.CapturingExecutionHandler;
import event.CapturingExecutionHandler.Observed;
import event.ExecutionEventType;
import gateway.OrderGateway;
import market.MarketDataService;
import net.ResendService;
import net.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import publisher.ExecutionFrames;
import publisher.TradeLogger;
import publisher.WebSocketPublisher;
import recovery.RestoredSnapshot;
import recovery.SnapshotStore;
import session.SessionController;
import session.VenueStartup;
import util.EpochNanoClock;
import util.VenueStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static integration.RecoveryScenario.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The session lifecycle on the journaled venue (P15-8): session commands are journaled and replayed,
 * recovery restores an open or a closed session by either path (snapshot or full replay), a snapshot
 * is taken at every close, a stale day is closed at startup, and, over a real server started in
 * Main's order, the session is opened on the Netty worker (the gateway's only producer thread),
 * SESSION frames reach clients as their own frame type, and RESEND returns them byte-identical.
 */
class SessionLifecycleTest {

    private static final int DAY = JournaledVenue.BUSINESS_DATE;
    private static final long OPEN = JournaledVenue.OPEN_SEQ;

    @TempDir
    Path root;

    private static List<ExecutionEventType> types(List<Observed> events) {
        return events.stream().map(Observed::eventType).toList();
    }

    private void deleteSnapshots() {
        RecoveryScenario.deleteTree(JournaledVenue.config(root).snapshotDir());
    }

    /** A first run: open, one resting order (seq 2); optionally closed by the controller (seq 3). */
    private void firstRun(boolean close) {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            gateway.onFrame(FixMessages.limit(1, '1', "150.00", 10));
            await(() -> venue.pipeline().appliedSeq() == OPEN + 1, "order applied");
            assertTrue(venue.engine().hasOpenOrder(1));
            if (close) venue.sessionController(gateway).close(DAY);
        }
    }

    @Test
    void anOpenSessionIsRecoveredByFullReplayAndKeepsTrading() {
        firstRun(false);
        deleteSnapshots();

        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        try (JournaledVenue again = JournaledVenue.create(root)) {
            again.outbound().handleEventsWith(execs);
            OrderGateway gateway = again.gateway();
            again.startAndOpen(gateway);   // the recovered session is today's: nothing is issued
            assertEquals(OPEN + 1, again.pipeline().replayedCommands(), "the open and the order were replayed");
            assertEquals(VenueStatus.sessionWord(true, DAY, -1), again.pipeline().session());
            assertTrue(again.engine().hasOpenOrder(1));
            gateway.onFrame(FixMessages.limit(2, '1', "149.00", 5));
            await(() -> again.pipeline().appliedSeq() == OPEN + 2, "order after restart applied");
        }
        assertEquals(List.of(ExecutionEventType.ORDER_ACCEPTED), types(execs.snapshot()),
                "no second open; the order is matched in the recovered session");
    }

    @Test
    void anOpenSessionIsRecoveredFromTheShutdownSnapshot() {
        firstRun(false);
        try (JournaledVenue again = JournaledVenue.create(root)) {
            assertEquals(OPEN + 1, again.pipeline().recoveredSnapshotSeq(), "graceful shutdown snapshotted");
            again.startAndOpen(again.gateway());
            assertEquals(0, again.pipeline().replayedCommands());
            assertEquals(VenueStatus.sessionWord(true, DAY, -1), again.pipeline().session());
        }
    }

    @Test
    void aClosedSessionIsRecoveredByEitherPathAndOnlyCancelsAreAccepted() {
        firstRun(true);
        for (boolean fromSnapshot : new boolean[] {true, false}) {
            if (!fromSnapshot) deleteSnapshots();
            CapturingExecutionHandler execs = new CapturingExecutionHandler();
            try (JournaledVenue again = JournaledVenue.create(root)) {
                again.outbound().handleEventsWith(execs);
                OrderGateway gateway = again.gateway();
                again.startAndOpen(gateway);   // today was closed: never reopened
                assertEquals(VenueStatus.sessionWord(false, -1, DAY), again.pipeline().session(),
                        fromSnapshot ? "from the snapshot" : "by replay");
                long base = again.pipeline().appliedSeq();
                gateway.onFrame(FixMessages.limit(fromSnapshot ? 10 : 11, '1', "150.00", 1));
                if (fromSnapshot) gateway.onFrame(FixMessages.cancel(12, 1));   // the GTC order survives the close
                long sent = fromSnapshot ? 2 : 1;
                await(() -> again.pipeline().appliedSeq() == base + sent, "commands applied");
            }
            List<ExecutionEventType> seen = types(execs.snapshot());
            assertEquals(fromSnapshot
                    ? List.of(ExecutionEventType.ORDER_REJECTED, ExecutionEventType.ORDER_CANCELLED)
                    : List.of(ExecutionEventType.ORDER_REJECTED), seen);
        }
    }

    @Test
    void aSnapshotIsTakenAtTheSessionCloseAndHoldsTheClosedState() throws Exception {
        try (JournaledVenue venue = JournaledVenue.create(JournaledVenue.config(root, 1_000_000L), null, null)) {
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            gateway.onFrame(FixMessages.limit(1, '1', "150.00", 10));
            venue.sessionController(gateway).close(DAY);   // seq 3

            SnapshotStore store = new SnapshotStore(venue.config().snapshotDir());
            long closeSeq = OPEN + 2;
            await(() -> store.exists(closeSeq), "the snapshot at the close's seq");   // before any shutdown
            RestoredSnapshot atClose = store.loadNewestValid();
            assertEquals(closeSeq, atClose.inboundSeq());
            assertFalse(atClose.sessionOpen());
            assertEquals(DAY, atClose.lastClosedBusinessDate());
            assertEquals(1, atClose.orders().size(), "the resting GTC order is in it");
        }
    }

    @Test
    void aSessionLeftOpenOnAnEarlierDayIsClosedAtStartupThenTodayIsOpened() throws Exception {
        firstRun(false);
        Clock nextDay = Clock.fixed(Instant.parse("2026-11-03T17:00:00Z"), ZoneOffset.UTC);
        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        try (JournaledVenue again = JournaledVenue.create(root)) {
            again.outbound().handleEventsWith(execs);
            OrderGateway gateway = again.gateway();
            again.start();
            SessionController.fromConfig(again.config(), nextDay, gateway, Runnable::run, again.pipeline()).startup();
            assertEquals(VenueStatus.sessionWord(true, 20261103, DAY), again.pipeline().session());
            assertTrue(again.engine().hasOpenOrder(1), "GTC orders persist across sessions");
            List<Observed> seen = execs.awaitAtLeast(2, 5_000);
            assertEquals(List.of(ExecutionEventType.SESSION_CLOSED, ExecutionEventType.SESSION_OPENED), types(seen));
            assertEquals(DAY, seen.get(0).businessDate());
            assertEquals(20261103, seen.get(1).businessDate());
            SnapshotStore store = new SnapshotStore(again.config().snapshotDir());
            await(() -> store.exists(OPEN + 2), "the snapshot at the stale close");   // written on the writer's thread
        }
    }

    // --- over a real server, started in Main's order ---

    /** Text frames a java.net.http WebSocket client received, in arrival order. */
    private static final class Frames implements WebSocket.Listener {
        private final ObjectMapper mapper = new ObjectMapper();
        final List<String> text = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                text.add(partial.toString());
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        String await(Predicate<JsonNode> match) {
            String[] found = {null};
            RecoveryScenario.await(() -> {
                synchronized (text) {
                    for (String t : text) {
                        if (match.test(read(t))) {
                            found[0] = t;
                            return true;
                        }
                    }
                }
                return false;
            }, "a matching frame");
            return found[0];
        }

        JsonNode read(String t) {
            try {
                return mapper.readTree(t);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void overARealServerTheSessionIsOpenedOnTheWorkerAndSessionFramesReachClientsAndResendByteIdentical()
            throws Exception {
        JournaledVenue venue = JournaledVenue.create(root);
        OrderGateway gateway = venue.gateway();
        ResendService resend = new ResendService(venue.pipeline().outboundHistory(), new ExecutionFrames()::toJson);
        WebSocketServer server = new WebSocketServer(0, gateway, new EpochNanoClock(), resend, venue.pipeline());
        WebSocketPublisher publisher = new WebSocketPublisher(server.getChannelGroup(), server.getBookFrameCache());
        venue.outbound().handleEventsWith(publisher.executionHandler(), new TradeLogger());
        venue.snapshots().handleEventsWith(publisher.snapshotHandler(), new MarketDataService());
        WebSocket ws = null;
        try {
            SessionController sessions = VenueStartup.start(venue.config(), venue.pipeline(), server, gateway,
                    JournaledVenue.CLOCK);

            // D15: the open was published by the server's single worker, now the gateway's only producer.
            Thread producer = gateway.producerThread();
            assertNotNull(producer);
            assertTrue(server.gatewayExecutor().inEventLoop(producer), "opened on the worker: " + producer.getName());
            assertEquals(VenueStatus.sessionWord(true, DAY, -1), venue.pipeline().session(), "open before bind");
            assertThrows(IllegalStateException.class,
                    () -> gateway.onFrame(FixMessages.limit(99, '1', "1.00", 1)), "no second producer thread");

            Frames frames = new Frames();
            ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + server.boundPort() + "/ws"), frames)
                    .get(5, TimeUnit.SECONDS);
            await(() -> server.getChannelGroup().size() == 1, "client registered");
            ws.sendText("{\"type\":\"NEW\",\"clOrdId\":1,\"side\":\"BUY\",\"price\":1500000,\"qty\":10,"
                    + "\"symbol\":\"ASML\"}", true).get(5, TimeUnit.SECONDS);
            frames.await(n -> "EXEC".equals(n.path("type").asText())
                    && "ORDER_ACCEPTED".equals(n.path("execType").asText()));
            assertSame(producer, gateway.producerThread(), "the client's order came through the same thread");

            sessions.close(DAY);   // handed to the worker, as the calendar will at P15-9
            String liveClose = frames.await(n -> "SESSION".equals(n.path("type").asText()));
            JsonNode close = frames.read(liveClose);
            assertEquals("CLOSED", close.path("state").asText());
            assertEquals(DAY, close.path("businessDate").asInt());
            long closeSeq = close.path("seq").asLong();
            assertEquals(OPEN + 2, closeSeq);
            synchronized (frames.text) {
                assertTrue(frames.text.stream().noneMatch(t -> frames.read(t).path("type").asText().equals("EXEC")
                        && frames.read(t).path("execType").asText().startsWith("SESSION")),
                        "no session event ever travels as an EXEC frame");
            }

            frames.text.clear();
            ws.sendText("{\"type\":\"RESEND\",\"fromSeq\":" + OPEN + "}", true).get(5, TimeUnit.SECONDS);
            frames.await(n -> "RESEND_COMPLETE".equals(n.path("type").asText()));
            List<String> resent = new ArrayList<>(frames.text);
            assertTrue(resent.contains(liveClose), "the SESSION frame is resent byte-identical: " + resent);
            JsonNode first = frames.read(resent.get(0));
            assertEquals("SESSION", first.path("type").asText());
            assertEquals("OPEN", first.path("state").asText(), "the open, sent before any client, is in the history");
            assertEquals(OPEN, first.path("seq").asLong());
        } finally {
            if (ws != null) ws.abort();
            server.stop();
            venue.close();
        }
    }
}
