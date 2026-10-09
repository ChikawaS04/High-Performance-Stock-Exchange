package engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Engine-seam test double: records every ExecutionListener callback, in order, so a
 * pure-engine test can assert the exact execution sequence without an outbound ring buffer
 * or a consumer thread. Mirrors the outbound-side CapturingExecutionHandler, one level
 * earlier in the pipeline.
 *
 * <p>Synchronous and single-threaded: the engine fires these callbacks inline during
 * addOrder on the caller's thread, so a plain ArrayList and direct reads are safe. Each
 * callback is snapshotted into an immutable Observed, which is convenient here (no slot
 * reuse at this seam) and keeps assertions uniform with the outbound capturer.
 *
 * <p>Package-private, engine package, so it can be wired via setExecutionListener. It is the
 * first non-NO_OP, non-handler implementation of ExecutionListener, but it is test-only;
 * the main-source implementations remain NO_OP and MatchingEngineHandler.
 */
final class CapturingExecutionListener implements ExecutionListener {

    enum Kind { FILL, ACCEPTED, EXPIRED }

    record Observed(
            Kind kind,
            long orderId,
            long passiveOrderId,
            long tradeId,
            long price,
            long quantity,
            long remainingQuantity
    ) { }

    private final List<Observed> events = new ArrayList<>();

    @Override
    public void onFill(long aggressorOrderId, long passiveOrderId, long tradeId,
                       long price, long filledQuantity, long aggressorRemainingQuantity) {
        events.add(new Observed(Kind.FILL, aggressorOrderId, passiveOrderId, tradeId,
                price, filledQuantity, aggressorRemainingQuantity));
    }

    @Override
    public void onAccepted(long orderId, long price, long remainingQuantity) {
        events.add(new Observed(Kind.ACCEPTED, orderId, -1L, -1L, price, -1L, remainingQuantity));
    }

    @Override
    public void onExpired(long orderId, long expiredQuantity) {
        events.add(new Observed(Kind.EXPIRED, orderId, -1L, -1L, -1L, -1L, expiredQuantity));
    }

    // --- queries kept small so tests read declaratively ---

    List<Observed> all() { return events; }

    Observed get(int i) { return events.get(i); }

    int size() { return events.size(); }

    long count(Kind kind) {
        return events.stream().filter(e -> e.kind() == kind).count();
    }
}
