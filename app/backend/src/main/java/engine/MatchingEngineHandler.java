package engine;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import event.BookSnapshotEvent;
import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import model.Order;
import model.TimeInForce;

import java.util.function.LongSupplier;

/**
 * Adapts MatchingEngine onto the Disruptor: consumes OrderEvent from the inbound
 * ring buffer and produces ExecutionEvent onto the outbound ring buffer. Sole
 * outbound producer (SRS §3.3), so the outbound ring is ProducerType.SINGLE.
 *
 * onEvent builds the Order from the OrderEvent — THIS is where domain validation
 * fires (the Order constructor throws on bad fields), since the parser
 * deliberately never builds an Order. Match executions are reported back via the
 * ExecutionListener callbacks and published from there.
 *
 * P4-2: also the sole producer of the SNAPSHOT ring. After each inbound event is
 * processed, it reads a bounded depth snapshot off the engine (safe — this is the
 * engine thread) and publishes it. Two rings, one producer, one thread: both stay
 * SINGLE.
 *
 * The injectable clock mirrors the OrderGateway seam (Step 7) for deterministic
 * tests and future JMH timestamping (SRS §6.3). It stamps BOTH outbound streams.
 */
public final class MatchingEngineHandler implements EventHandler<OrderEvent>, ExecutionListener {

    /** Demo simplification: the FIX subset carries no participant; the gateway is stateless. */
    private static final long GATEWAY_PARTICIPANT_ID = 1L;
    private static final long NA = -1L;

    private final MatchingEngine engine;
    private final RingBuffer<ExecutionEvent> outbound;
    /** Nullable: null means "no depth feed wired" (pre-Phase-4 callers, engine-only tests). */
    private final RingBuffer<BookSnapshotEvent> snapshots;
    private final LongSupplier clock;

    public MatchingEngineHandler(MatchingEngine engine, RingBuffer<ExecutionEvent> outbound) {
        this(engine, outbound, null, System::nanoTime);
    }

    /** Phase 4 form: both outbound rings. Used by Main (P4-7). */
    public MatchingEngineHandler(MatchingEngine engine,
                                 RingBuffer<ExecutionEvent> outbound,
                                 RingBuffer<BookSnapshotEvent> snapshots) {
        this(engine, outbound, snapshots, System::nanoTime);
    }

    // Package-private clock seam for tests (no depth feed).
    MatchingEngineHandler(MatchingEngine engine, RingBuffer<ExecutionEvent> outbound, LongSupplier clock) {
        this(engine, outbound, null, clock);
    }

    /**
     * Clock injection point: both outbound rings plus an explicit time source.
     * Public because Main (root package) wires the production EpochNanoClock in through
     * here (P7-1); it is also the seam tests use to inject a deterministic clock.
     */
    public MatchingEngineHandler(MatchingEngine engine,
                                 RingBuffer<ExecutionEvent> outbound,
                                 RingBuffer<BookSnapshotEvent> snapshots,
                                 LongSupplier clock) {
        this.engine = engine;
        this.outbound = outbound;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /**
     * Processes one inbound event, then publishes exactly one depth snapshot —
     * on every path, including rejects. "One snapshot per inbound event" is a
     * simpler invariant than "one per book-mutating event"; a redundant snapshot
     * after a reject is harmless and costs nothing at demo volume.
     */
    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        process(event);
        publishSnapshot();
    }

    private void process(OrderEvent event) {
        if (event.eventType == OrderEventType.CANCEL_ORDER) {
            boolean cancelled = engine.cancelOrder(event.originalOrderId);
            publish(cancelled ? ExecutionEventType.ORDER_CANCELLED : ExecutionEventType.ORDER_REJECTED,
                    event.originalOrderId, NA, NA, NA, NA, NA, NA);
            return;
        }

        // NEW_ORDER: domain validation fires here, in the Order constructor.
        // A null tif reaching the engine means the slot carried no tag 59, which the wire
        // maps to GTC; coalesce here so the canonical constructor's non-null guard stays a
        // guard for direct callers rather than a reject path for the live pipeline.
        TimeInForce tif = (event.tif == null) ? TimeInForce.GTC : event.tif;
        Order order;
        try {
            order = new Order(
                    event.orderId,
                    event.timestamp,
                    event.side,
                    (int) event.quantity,
                    event.price,
                    GATEWAY_PARTICIPANT_ID,
                    tif
            );
        } catch (IllegalArgumentException rejected) {
            publish(ExecutionEventType.ORDER_REJECTED, event.orderId, NA, NA, NA, NA, NA, NA);
            return;
        }

        engine.addOrder(order);   // drives matching; fires onFill / onAccepted below
    }

    // --- ExecutionListener: fired synchronously by the engine during addOrder ---

    @Override
    public void onFill(long aggressorOrderId, long passiveOrderId, long tradeId,
                       long price, long filledQuantity, long aggressorRemainingQuantity) {
        ExecutionEventType type = (aggressorRemainingQuantity == 0)
                ? ExecutionEventType.ORDER_FILLED
                : ExecutionEventType.ORDER_PARTIALLY_FILLED;
        publish(type, aggressorOrderId, tradeId, price, filledQuantity,
                aggressorRemainingQuantity, aggressorOrderId, passiveOrderId);
    }

    @Override
    public void onAccepted(long orderId, long price, long remainingQuantity) {
        publish(ExecutionEventType.ORDER_ACCEPTED, orderId, NA, price, NA, remainingQuantity, NA, NA);
    }

    @Override
    public void onExpired(long orderId, long expiredQuantity) {
        // ORDER_EXPIRED carries the order id and the quantity that expired unexecuted in
        // remainingQuantity; every other field is the NA sentinel (Phase 14 D5, SRS §3.4).
        publish(ExecutionEventType.ORDER_EXPIRED, orderId, NA, NA, NA, expiredQuantity, NA, NA);
    }

    // --- single outbound publish point: writes EVERY field (slot-reuse discipline) ---

    private void publish(ExecutionEventType type, long orderId, long tradeId, long price,
                         long filledQuantity, long remainingQuantity,
                         long aggressorOrderId, long passiveOrderId) {
        long seq = outbound.next();
        try {
            ExecutionEvent e = outbound.get(seq);
            e.eventType         = type;
            e.orderId           = orderId;
            e.tradeId           = tradeId;
            e.price             = price;
            e.filledQuantity    = filledQuantity;
            e.remainingQuantity = remainingQuantity;
            e.aggressorOrderId  = aggressorOrderId;
            e.passiveOrderId    = passiveOrderId;
            e.timestamp         = clock.getAsLong();
        } finally {
            outbound.publish(seq);
        }
    }

    /**
     * Reads the book into a claimed snapshot slot and publishes it. Runs on the
     * engine thread — the only thread that may read bids/asks unsynchronized.
     *
     * snapshotInto fills every scalar and the valid array prefix; the level counts
     * are authoritative, so array tails beyond them are intentionally left stale
     * (BookSnapshotEvent's reuse contract) and consumers must not read past the
     * counts. The timestamp is then overwritten from the injected clock so both
     * outbound streams share one time source.
     */
    private void publishSnapshot() {
        if (snapshots == null) return;   // no depth feed wired

        long seq = snapshots.next();
        try {
            BookSnapshotEvent s = snapshots.get(seq);
            engine.snapshotInto(s, BookSnapshotEvent.MAX_DEPTH_LEVELS);
            s.timestamp = clock.getAsLong();
        } finally {
            snapshots.publish(seq);
        }
    }
}
