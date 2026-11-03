package integration;

import com.lmax.disruptor.EventHandler;
import event.BookSnapshotEvent;
import event.CapturingExecutionHandler;
import event.CapturingSnapshotHandler;
import event.ExecutionEvent;
import gateway.OrderGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbound and snapshot consumers fail soft (P15-4): an exception in one subscriber is logged,
 * that subscriber carries on with the next event, the other subscribers are unaffected, and the
 * engine keeps processing. A dead consumer would otherwise fill its ring and stall the engine.
 */
class ConsumerFailureTest {

    @TempDir
    Path root;

    /** Throws on the first event it sees, counts every event. */
    private static final class ThrowsOnce<T> implements EventHandler<T> {
        final AtomicInteger seen = new AtomicInteger();

        @Override
        public void onEvent(T event, long sequence, boolean endOfBatch) {
            if (seen.incrementAndGet() == 1) throw new IllegalStateException("subscriber fails once (test)");
        }
    }

    @Test
    void aThrowingSubscriberDoesNotStopLaterEventsOrTheEngine() {
        int orders = 2_000;   // about two rings' worth: a dead consumer would have stalled the engine
        int total = (int) JournaledVenue.OPEN_SEQ + orders;
        CapturingExecutionHandler executions = new CapturingExecutionHandler();
        CapturingSnapshotHandler books = new CapturingSnapshotHandler();
        ThrowsOnce<ExecutionEvent> failingExec = new ThrowsOnce<>();
        ThrowsOnce<BookSnapshotEvent> failingBook = new ThrowsOnce<>();

        try (JournaledVenue venue = JournaledVenue.create(root)) {
            venue.outbound().handleEventsWith(failingExec, executions);
            venue.snapshots().handleEventsWith(failingBook, books);
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);   // P15-8: one more command, event and BOOK before the orders

            for (int i = 1; i <= orders; i++) gateway.onFrame(FixMessages.limit(i, '1', "150.00", 10));

            executions.awaitAtLeast(total, 10_000);
            books.awaitAtLeast(total, 10_000);
            assertEquals(total, venue.pipeline().appliedSeq(), "the engine processed every command");
        }   // close(): drains cleanly and asserts no fatal

        assertEquals(total, executions.snapshot().size(), "the healthy EXEC subscriber saw every event");
        assertEquals(total, books.snapshot().size(), "the healthy BOOK subscriber saw every snapshot");
        assertEquals(total, failingExec.seen.get(), "the failing EXEC subscriber continued after its exception");
        assertEquals(total, failingBook.seen.get(), "the failing BOOK subscriber continued after its exception");
        assertTrue(executions.snapshot().get(total - 1).seq() == total, "seq continued gap-free");
        assertEquals(orders, executions.snapshot().stream()
                .filter(o -> o.eventType() == event.ExecutionEventType.ORDER_ACCEPTED).count(), "every order rested");
    }
}
