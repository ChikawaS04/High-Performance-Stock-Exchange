package integration;

import codec.OrderEventCodec;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import event.CapturingExecutionHandler;
import event.CapturingSnapshotHandler;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import gateway.OrderGateway;
import journal.Journal;
import journal.JournalReader;
import journal.RecordingDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Replay determinism over the real journal (SRS §5.8, a preview of P15-5 without snapshots):
 * a live run through the journaled pipeline, then the inbound recording replayed through
 * {@link JournalReader} into a fresh engine and handler. The replayed execution stream and book
 * stream must equal the live ones, every field, in order.
 */
class JournalReplayTest {

    @TempDir
    Path root;

    /** Rests, full and partial fills, iceberg reload, midpoint peg, IOC, FOK, cancels, rejects. */
    private static List<byte[]> scenario() {
        return List.of(
                FixMessages.limit(100, '2', "150.00", 100),          // SELL rests
                FixMessages.limit(101, '1', "150.00", 100),          // BUY fills fully
                FixMessages.limit(200, '2', "150.00", 50),           // SELL 50 rests
                FixMessages.limit(201, '1', "150.00", 80),           // BUY 80: partial, 30 rests
                FixMessages.iceberg(300, '2', "151.00", 1_000, 100), // iceberg SELL rests
                FixMessages.limit(301, '1', "151.00", 150),          // BUY 150 lifts the iceberg's 100 tip, reloads, then 50
                FixMessages.limit(400, '1', "149.00", 10, '3'),      // IOC, nothing to cross: expires
                FixMessages.limit(401, '1', "151.00", 5_000, '4'),   // FOK, not enough: expires
                FixMessages.limit(500, '1', "100.00", 10),           // bid
                FixMessages.peg(501, '2', 40, '1'),                  // peg SELL rests in its pool
                FixMessages.peg(502, '1', 25, '1'),                  // peg BUY crosses at the mid
                FixMessages.limit(500, '1', "99.00", 5),             // duplicate of resting 500: rejected
                FixMessages.cancel(600, 500),                        // cancel resting 500
                FixMessages.cancel(601, 999),                        // unknown: rejected
                FixMessages.limit(700, '2', "150.50", 7));           // rests
    }

    @Test
    void replayingTheInboundRecordingReproducesTheLiveExecutionAndBookStreams() {
        List<byte[]> frames = scenario();
        final long commands = JournaledVenue.OPEN_SEQ + frames.size();
        CapturingExecutionHandler liveExec = new CapturingExecutionHandler();
        CapturingSnapshotHandler liveBook = new CapturingSnapshotHandler();

        try (JournaledVenue venue = JournaledVenue.create(root)) {
            venue.outbound().handleEventsWith(liveExec);
            venue.snapshots().handleEventsWith(liveBook);
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);   // P15-8: the session open is journaled first and replayed too
            for (byte[] frame : frames) gateway.onFrame(frame);
            await(() -> venue.pipeline().appliedSeq() == commands, "every command applied live");
        }   // graceful close: every event drained to the capture handlers

        Set<ExecutionEventType> types = EnumSet.noneOf(ExecutionEventType.class);
        liveExec.snapshot().forEach(o -> types.add(o.eventType()));
        assertTrue(types.containsAll(EnumSet.of(ExecutionEventType.ORDER_ACCEPTED, ExecutionEventType.ORDER_FILLED,
                ExecutionEventType.ORDER_PARTIALLY_FILLED, ExecutionEventType.ORDER_EXPIRED,
                ExecutionEventType.ORDER_CANCELLED, ExecutionEventType.ORDER_REJECTED)),
                "the scenario exercises every live event type, saw " + types);

        // Replay into a fresh engine and handler, from the same data directory.
        CapturingExecutionHandler replayExec = new CapturingExecutionHandler();
        CapturingSnapshotHandler replayBook = new CapturingSnapshotHandler();
        OutboundPipeline outbound = new OutboundPipeline();
        SnapshotPipeline snapshots = new SnapshotPipeline();
        outbound.handleEventsWith(replayExec);
        snapshots.handleEventsWith(replayBook);
        MatchingEngine engine = new MatchingEngine();
        MatchingEngineHandler handler = new MatchingEngineHandler(engine, outbound.getRingBuffer(), snapshots.getRingBuffer());
        engine.setExecutionListener(handler);
        outbound.start();
        snapshots.start();
        try (Journal journal = Journal.launch(JournaledVenue.config(root))) {
            RecordingDescriptor recording = journal.findRecording(Journal.INBOUND_STREAM_ID);
            OrderEventCodec codec = new OrderEventCodec();
            OrderEvent command = new OrderEvent();
            long replayed = new JournalReader(journal.archive()).replayAll(recording.recordingId(),
                    (buffer, offset, length, header) -> {
                        codec.decode(buffer, offset, command);
                        handler.onCommand(command);
                    });
            assertEquals(commands, replayed);
            await(() -> outbound.isDrained() && snapshots.isDrained(), "replay drained");
        } finally {
            outbound.halt();
            snapshots.halt();
        }

        List<CapturingExecutionHandler.Observed> live = liveExec.snapshot();
        List<CapturingExecutionHandler.Observed> again = replayExec.snapshot();

        // From raw FIX bytes: the resting SELL 100 and the iceberg SELL are acknowledged with their side,
        // and the replayed stream (compared field by field below) regenerates it.
        assertEquals(model.Side.SELL, accepted(live, 100).aggressorSide(), "a resting order's ACCEPTED names its side");
        assertEquals(model.Side.SELL, accepted(live, 300).aggressorSide());
        assertEquals(model.Side.BUY, accepted(live, 500).aggressorSide());
        assertEquals(model.Side.SELL, accepted(again, 100).aggressorSide());
        assertEquals(live.size(), again.size(), "execution stream length");
        for (int i = 0; i < live.size(); i++) {
            assertEquals(live.get(i), again.get(i), "execution event " + i);
        }

        List<CapturingSnapshotHandler.Observed> liveBooks = liveBook.snapshot();
        List<CapturingSnapshotHandler.Observed> againBooks = replayBook.snapshot();
        assertEquals(commands, liveBooks.size(), "one BOOK per command, the session open included");
        assertEquals(liveBooks.size(), againBooks.size(), "book stream length");
        for (int i = 0; i < liveBooks.size(); i++) {
            CapturingSnapshotHandler.Observed a = liveBooks.get(i);
            CapturingSnapshotHandler.Observed b = againBooks.get(i);
            String at = "book " + i;
            assertArrayEquals(a.bidPrices(), b.bidPrices(), at);
            assertArrayEquals(a.bidQtys(), b.bidQtys(), at);
            assertArrayEquals(a.askPrices(), b.askPrices(), at);
            assertArrayEquals(a.askQtys(), b.askQtys(), at);
            assertEquals(a.bidLevelCount(), b.bidLevelCount(), at);
            assertEquals(a.askLevelCount(), b.askLevelCount(), at);
            assertEquals(a.bestBid(), b.bestBid(), at);
            assertEquals(a.bestAsk(), b.bestAsk(), at);
            assertEquals(a.lastSeq(), b.lastSeq(), at);
            assertEquals(a.timestamp(), b.timestamp(), at);
        }
        assertEquals(engine.getNextTradeId() - 1, live.stream().filter(o -> o.tradeId() > 0).count(),
                "trade ids are the engine's own gap-free counter");
    }

    private static CapturingExecutionHandler.Observed accepted(List<CapturingExecutionHandler.Observed> stream, long orderId) {
        return stream.stream()
                .filter(o -> o.eventType() == ExecutionEventType.ORDER_ACCEPTED && o.orderId() == orderId)
                .findFirst().orElseThrow(() -> new AssertionError("no ACCEPTED for order " + orderId));
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
