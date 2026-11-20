package integration;

import event.CapturingExecutionHandler;
import event.ExecutionEventType;
import gateway.OrderGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import recovery.EngineStates;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The disk guard's hard stop through the journaled venue (P16-8): while it holds, a NEW is refused before
 * sequencing and never journaled, a cancel is accepted and matched as usual, and the session commands go
 * through. A recovery from the journal alone, across the stop period, gives exactly the live state:
 * nothing the journal does not hold ever decided anything the engine did.
 */
class DiskHardStopTest {

    @TempDir
    Path root;

    @Test
    void underTheStopNewIsRefusedCancelsWorkAndAReplayIsIdentical() {
        AtomicBoolean stop = new AtomicBoolean();
        List<String> liveState;
        long journaled;
        long lastOutbound;
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            CapturingExecutionHandler execs = new CapturingExecutionHandler();
            venue.outbound().handleEventsWith(execs);
            OrderGateway gateway = new OrderGateway(venue.pipeline().inboundRing(),
                    RecoveryScenario.countingClock(1_000L), stop::get);
            venue.startAndOpen(gateway);                                         // seq 1: SESSION_OPEN

            assertEquals(OrderGateway.FrameOutcome.PUBLISHED, gateway.onFrame(FixMessages.limit(1, '2', "150.00", 10)));
            assertEquals(OrderGateway.FrameOutcome.PUBLISHED, gateway.onFrame(FixMessages.limit(2, '2', "151.00", 10)));

            stop.set(true);
            assertEquals(OrderGateway.FrameOutcome.REFUSED, gateway.onFrame(FixMessages.limit(3, '1', "150.00", 10)),
                    "a crossing NEW is refused under the stop");
            assertEquals(OrderGateway.FrameOutcome.PUBLISHED, gateway.onFrame(FixMessages.cancel(4, 2)),
                    "a cancel is accepted under the stop");
            assertEquals(OrderGateway.FrameOutcome.REFUSED, gateway.onFrame(FixMessages.limit(5, '1', "151.00", 1)));
            stop.set(false);
            assertEquals(OrderGateway.FrameOutcome.PUBLISHED, gateway.onFrame(FixMessages.limit(6, '1', "150.00", 4)));

            RecoveryScenario.await(() -> venue.pipeline().appliedSeq() == 5, "open, two NEWs, the cancel, one NEW applied");
            journaled = venue.pipeline().journaledSeq();
            assertEquals(5, journaled, "the two refused NEWs were never sequenced");
            assertEquals(2L, gateway.refusedNewOrders());

            // SESSION_OPENED, two ACCEPTED, the CANCELLED, order 6's fill.
            List<CapturingExecutionHandler.Observed> seen = execs.awaitAtLeast(5, 5_000);
            assertEquals(5, seen.size(), seen.toString());
            assertTrue(seen.stream().noneMatch(o -> o.orderId() == 3 || o.orderId() == 5), "no event for a refused NEW");
            assertTrue(seen.stream().anyMatch(o -> o.eventType() == ExecutionEventType.ORDER_CANCELLED && o.orderId() == 2));
            assertTrue(seen.stream().anyMatch(o -> o.eventType() == ExecutionEventType.ORDER_FILLED && o.orderId() == 6),
                    "matching unchanged after the stop: order 6 fills against order 1");
            lastOutbound = venue.pipeline().publishedSeq();
            liveState = EngineStates.describe(venue.engine());
        }

        RecoveryScenario.deleteTree(JournaledVenue.config(root).snapshotDir());
        try (JournaledVenue again = JournaledVenue.create(root)) {
            assertEquals(-1, again.pipeline().recoveredSnapshotSeq());
            again.start();
            assertEquals(journaled, again.pipeline().replayedCommands(), "the whole journal, stop period included");
            assertEquals(journaled, again.pipeline().appliedSeq());
            assertEquals(lastOutbound, again.pipeline().publishedSeq(), "the same outbound seqs, none added");
            assertEquals(liveState, EngineStates.describe(again.engine()), "replay = live, with the stop in between");
        }
    }
}
