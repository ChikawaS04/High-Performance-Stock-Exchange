package integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import config.ExchangeConfig;
import event.CapturingExecutionHandler;
import event.CapturingSnapshotHandler;
import gateway.OrderGateway;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import journal.Journal;
import net.WebSocketFrameHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import publisher.WebSocketPublisher;
import recovery.EngineStates;
import recovery.RecoveryException;
import recovery.SnapshotSink;
import recovery.SnapshotStore;
import util.BookFrameCache;
import util.EpochNanoClock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static integration.RecoveryScenario.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Recovery through the real {@link journal.JournaledPipeline} (P15-5, SRS §3.10): every test
 * runs the venue, stops it, and starts a new venue on the same data directory, exactly as Main
 * restarts. Commands enter as FIX bytes through the gateway.
 *
 * A hard stop is modelled here by removing the snapshots a crash would not have written; the
 * journal itself is closed cleanly. {@link HardKillRecoveryTest} kills a real process instead.
 */
class RecoveryTest {

    /** Far above any nanoTime stamp of a first run, so the D1 clamp never rewrites it. */
    private static final long NEXT_COMMAND_CLOCK = 4_000_000_000_000_000_000L;

    @TempDir
    Path root;

    @TempDir
    Path other;

    private record FirstRun(List<String> state, long lastOutboundSeq, int commands) { }

    /** Runs the scenario on {@code root} and stops gracefully. */
    private static FirstRun runScenario(ExchangeConfig config) {
        List<byte[]> frames = RecoveryScenario.frames();
        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        JournaledVenue venue = JournaledVenue.create(config, null, null);
        venue.outbound().handleEventsWith(execs);
        OrderGateway gateway = venue.gateway(RecoveryScenario.countingClock(1_000L));
        venue.startAndOpen(gateway);   // P15-8: the session open is inbound seq 1
        for (byte[] frame : frames) gateway.onFrame(frame);
        int commands = RecoveryScenario.commands();
        await(() -> venue.pipeline().appliedSeq() == commands, "scenario applied");
        venue.close();
        List<CapturingExecutionHandler.Observed> seen = execs.snapshot();
        return new FirstRun(EngineStates.describe(venue.engine()), seen.get(seen.size() - 1).seq(), commands);
    }

    private static Path snapshotDir(Path root) {
        return JournaledVenue.config(root).snapshotDir();
    }

    @Test
    void aGracefulStopThenRestartReplaysNothing() {
        FirstRun first = runScenario(JournaledVenue.config(root));
        assertTrue(new SnapshotStore(snapshotDir(root)).exists(first.commands()), "graceful stop wrote a snapshot");

        JournaledVenue again = JournaledVenue.create(root);
        assertEquals(first.commands(), again.pipeline().recoveredSnapshotSeq());
        again.start();
        assertEquals(0, again.pipeline().replayedCommands());
        assertEquals(first.commands(), again.pipeline().appliedSeq());
        again.close();
        assertEquals(first.state(), EngineStates.describe(again.engine()));
    }

    @Test
    void aHardStopWithNoSnapshotReplaysTheWholeJournal() {
        FirstRun first = runScenario(JournaledVenue.config(root));
        RecoveryScenario.deleteTree(snapshotDir(root));

        JournaledVenue again = JournaledVenue.create(root);
        assertEquals(-1, again.pipeline().recoveredSnapshotSeq());
        again.start();
        assertEquals(first.commands(), again.pipeline().replayedCommands());
        again.close();
        assertEquals(first.state(), EngineStates.describe(again.engine()));
    }

    @Test
    void aSnapshotPlusPartialReplayEqualsAFullReplayAndTheNextCommandsMatch() throws IOException {
        FirstRun first = runScenario(JournaledVenue.config(root, 6));   // periodic snapshots from seq 6
        Path dir = snapshotDir(root);
        Files.delete(dir.resolve(SnapshotStore.fileName(first.commands())));   // as if it crashed after the last
        // Normally 18; a periodic snapshot defers to a later seq if the writer was still busy.
        long periodic = new SnapshotStore(dir).loadNewestValid().inboundSeq();
        assertTrue(periodic >= 6 && periodic < first.commands(), "a periodic snapshot remains: " + periodic);

        RecoveryScenario.copyTree(root.resolve("data"), other.resolve("data"));
        RecoveryScenario.deleteTree(snapshotDir(other));                         // the twin replays everything

        List<byte[]> next = List.of(
                FixMessages.limit(200, '2', "148.50", 120),       // sweeps bids, crosses the bid peg pool first
                FixMessages.limit(201, '1', "152.00", 2_000),     // sweeps every ask level, rests the remainder
                FixMessages.cancel(202, 116),                     // the recovered peg is cancellable
                FixMessages.limit(203, '1', "1.00", 1));

        Recovered fromSnapshot = recoverAndRun(JournaledVenue.config(root, 6), next);
        Recovered fromScratch = recoverAndRun(JournaledVenue.config(other, 6), next);

        assertEquals(periodic, fromSnapshot.snapshotSeq());
        assertEquals(first.commands() - periodic, fromSnapshot.replayed());
        assertEquals(-1, fromScratch.snapshotSeq());
        assertEquals(first.commands(), fromScratch.replayed());

        assertEquals(first.state(), fromSnapshot.stateBefore(), "snapshot + replay = state before the stop");
        assertEquals(first.state(), fromScratch.stateBefore(), "full replay = state before the stop");
        assertEquals(fromScratch.execs(), fromSnapshot.execs(), "identical EXECs, every field, after recovery");
        assertEquals(fromScratch.books(), fromSnapshot.books(), "identical BOOKs after recovery");
        assertEquals(fromScratch.stateAfter(), fromSnapshot.stateAfter());
        assertTrue(fromSnapshot.execs().size() > next.size(), "the next commands traded against the recovered book");
    }

    private record Recovered(long snapshotSeq, long replayed, List<String> stateBefore,
                             List<CapturingExecutionHandler.Observed> execs, List<String> books,
                             List<String> stateAfter) { }

    private static Recovered recoverAndRun(ExchangeConfig config, List<byte[]> next) {
        JournaledVenue venue = JournaledVenue.create(config, null, null);
        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        CapturingSnapshotHandler books = new CapturingSnapshotHandler();
        venue.outbound().handleEventsWith(execs);
        venue.snapshots().handleEventsWith(books);
        OrderGateway gateway = venue.gateway(RecoveryScenario.countingClock(NEXT_COMMAND_CLOCK));
        venue.start();
        // Recovery ran on the engine thread and start() saw it finish; no command has been sent, so
        // the agent is idle and the book is safe to read.
        List<String> before = EngineStates.describe(venue.engine());
        long target = venue.pipeline().appliedSeq() + next.size();
        for (byte[] frame : next) gateway.onFrame(frame);
        await(() -> venue.pipeline().appliedSeq() == target, "next commands applied");
        venue.close();
        List<String> bookStrings = new ArrayList<>();
        books.snapshot().forEach(b -> bookStrings.add(RecoveryScenario.book(b)));
        return new Recovered(venue.pipeline().recoveredSnapshotSeq(), venue.pipeline().replayedCommands(), before,
                execs.snapshot(), bookStrings, EngineStates.describe(venue.engine()));
    }

    @Test
    void seqsContinueAfterRecoveryWithNoGapAndNoRepeat() {
        FirstRun first = runScenario(JournaledVenue.config(root));
        RecoveryScenario.deleteTree(snapshotDir(root));   // recover by replay, the harder path for seqs

        JournaledVenue again = JournaledVenue.create(root);
        CapturingExecutionHandler execs = new CapturingExecutionHandler();
        again.outbound().handleEventsWith(execs);
        OrderGateway gateway = again.gateway(RecoveryScenario.countingClock(NEXT_COMMAND_CLOCK));
        again.start();
        gateway.onFrame(FixMessages.limit(300, '1', "1.00", 1));
        gateway.onFrame(FixMessages.limit(301, '1', "1.00", 1));
        await(() -> again.pipeline().appliedSeq() == first.commands() + 2, "two more applied");
        assertEquals(first.commands() + 2, again.pipeline().journaledSeq(), "inbound seq continued");
        again.close();

        assertEquals(List.of(first.lastOutboundSeq() + 1, first.lastOutboundSeq() + 2),
                execs.snapshot().stream().map(CapturingExecutionHandler.Observed::seq).toList(),
                "outbound seq continued, and no replayed event was republished");
    }

    @Test
    void aSnapshotBeyondTheJournalRefusesStartup() throws IOException {
        int full = runScenario(JournaledVenue.config(root)).commands();   // snapshot at the last seq

        // A shorter history in another directory: the open and 3 orders.
        JournaledVenue shorter = JournaledVenue.create(other);
        OrderGateway gateway = shorter.gateway(RecoveryScenario.countingClock(1_000L));
        shorter.startAndOpen(gateway);
        for (byte[] frame : RecoveryScenario.frames().subList(0, 3)) gateway.onFrame(frame);
        long shortEnd = JournaledVenue.OPEN_SEQ + 3;
        await(() -> shorter.pipeline().appliedSeq() == shortEnd, "short history applied");
        shorter.close();
        Files.copy(snapshotDir(root).resolve(SnapshotStore.fileName(full)),
                snapshotDir(other).resolve(SnapshotStore.fileName(full)));

        RecoveryException refused = assertThrows(RecoveryException.class, () -> JournaledVenue.create(other));
        assertTrue(refused.getMessage().contains("journal ends at inbound seq " + shortEnd), refused.getMessage());
        assertTrue(refused.getMessage().contains("records seq " + full), refused.getMessage());
        assertTrue(refused.getMessage().contains(snapshotDir(other).toString()), refused.getMessage());

        try (Journal journal = Journal.launch(JournaledVenue.config(other))) {
            assertNotNull(journal.findRecording(Journal.INBOUND_STREAM_ID), "the refusal closed the journal cleanly");
        }
    }

    @Test
    void aDeletedJournalRefusesStartup() {
        runScenario(JournaledVenue.config(root));
        RecoveryScenario.deleteTree(JournaledVenue.config(root).archiveDir());

        RecoveryException refused = assertThrows(RecoveryException.class, () -> JournaledVenue.create(root));
        assertTrue(refused.getMessage().contains("journal was deleted"), refused.getMessage());
        try (Journal journal = Journal.launch(JournaledVenue.config(root))) {
            assertNull(journal.findRecording(Journal.INBOUND_STREAM_ID), "the refusal recorded nothing");
        }
    }

    @Test
    void snapshotOnConnectShowsTheRecoveredBook() {
        runScenario(JournaledVenue.config(root));

        JournaledVenue again = JournaledVenue.create(root);
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        BookFrameCache cache = new BookFrameCache();
        WebSocketPublisher publisher = new WebSocketPublisher(group, cache);
        CapturingSnapshotHandler books = new CapturingSnapshotHandler();
        again.snapshots().handleEventsWith(publisher.snapshotHandler(), books);
        again.start();   // returns once the depth subscribers hold the recovered book

        String cached = cache.get();
        assertNotNull(cached, "the recovered book is cached before any new command");
        assertEquals(1, books.snapshot().size());
        CapturingSnapshotHandler.Observed book = books.snapshot().get(0);
        assertEquals(1_495_000L, book.bestBid(), "iceberg 111 leads the bids");
        assertEquals(1_510_000L, book.bestAsk(), "the reloaded iceberg 107 leads the asks");
        assertTrue(cached.contains("\"type\":\"BOOK\""), cached);

        EmbeddedChannel client = new EmbeddedChannel(new WebSocketFrameHandler(group,
                again.gateway(), new ObjectMapper(), new EpochNanoClock(), cache));
        client.pipeline().fireUserEventTriggered(
                new WebSocketServerProtocolHandler.HandshakeComplete("/ws", new DefaultHttpHeaders(), null));
        TextWebSocketFrame sent = assertInstanceOf(TextWebSocketFrame.class, client.readOutbound());
        assertEquals(cached, sent.text(), "a connecting client receives the recovered book");
        sent.release();
        client.finishAndReleaseAll();
        again.close();
    }

    @Test
    void aStalledSnapshotWriteNeverBlocksMatching() {
        Object gate = new Object();
        boolean[] release = {false};
        List<Long> started = Collections.synchronizedList(new ArrayList<>());
        SnapshotSink stalled = (body, length, seq) -> {
            started.add(seq);
            synchronized (gate) {
                while (!release[0]) {
                    try { gate.wait(); } catch (InterruptedException e) { return; }
                }
            }
        };
        JournaledVenue venue = JournaledVenue.create(JournaledVenue.config(root, 10), null, stalled);
        OrderGateway gateway = venue.gateway(RecoveryScenario.countingClock(1_000L));
        venue.startAndOpen(gateway);   // the open is seq 1; the first periodic snapshot is still seq 10
        int count = 200;
        for (int i = 1; i <= count; i++) {
            gateway.onFrame(FixMessages.limit(1_000 + i, '1', "100.00", 1));
        }
        // The first periodic write (seq 10) is held forever, yet every command is matched: the
        // engine thread only encodes, it never waits for the writer.
        long last = JournaledVenue.OPEN_SEQ + count;
        await(() -> venue.pipeline().appliedSeq() == last, "all commands applied while the write is stalled");
        assertEquals(List.of(10L), started, "later intervals were deferred, not queued behind the stalled write");
        assertFalse(venue.pipeline().snapshotWriter().isIdle(), "the write is still held");

        synchronized (gate) { release[0] = true; gate.notifyAll(); }
        venue.close();
        assertTrue(new SnapshotStore(snapshotDir(root)).exists(last), "graceful stop still wrote its snapshot");
    }
}
