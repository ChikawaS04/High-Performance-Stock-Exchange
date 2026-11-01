package event;

import com.lmax.disruptor.EventHandler;
import model.Side;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reusable outbound-side test subscriber. Snapshots each ExecutionEvent into an
 * immutable record (slots are reused, so we must copy). First-class artifact:
 * Step 8's end-to-end test asserts against this too.
 */
public final class CapturingExecutionHandler implements EventHandler<ExecutionEvent> {

    public record Observed(
            long seq,
            ExecutionEventType eventType,
            long orderId,
            long tradeId,
            long price,
            long filledQuantity,
            long remainingQuantity,
            long aggressorOrderId,
            long passiveOrderId,
            Side aggressorSide,
            long aggressorParticipantId,
            long passiveParticipantId,
            int businessDate,
            long timestamp
    ) { }

    private final List<Observed> observed = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onEvent(ExecutionEvent event, long sequence, boolean endOfBatch) {
        observed.add(new Observed(
                event.seq, event.eventType, event.orderId, event.tradeId, event.price,
                event.filledQuantity, event.remainingQuantity,
                event.aggressorOrderId, event.passiveOrderId,
                event.aggressorSide, event.aggressorParticipantId, event.passiveParticipantId,
                event.businessDate, event.timestamp
        ));
    }

    /** Busy-wait (bounded) until at least n events land, then return a snapshot. */
    public List<Observed> awaitAtLeast(int n, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (observed) {
                if (observed.size() >= n) return new ArrayList<>(observed);
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (observed) {
            return new ArrayList<>(observed);
        }
    }

    public List<Observed> snapshot() {
        synchronized (observed) {
            return new ArrayList<>(observed);
        }
    }
}