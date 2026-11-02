package engine;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import event.BookSnapshotEvent;
import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import model.Order;
import model.OrdType;
import model.Side;
import model.TimeInForce;

/**
 * Adapts MatchingEngine onto the Disruptor: consumes OrderEvent from the inbound
 * ring buffer and produces ExecutionEvent onto the outbound ring buffer. Sole
 * outbound producer (SRS §3.3), so the outbound ring is ProducerType.SINGLE.
 *
 * onCommand builds the Order from the OrderEvent. THIS is where domain validation
 * fires (the Order constructor throws on bad fields), since the parser
 * deliberately never builds an Order. Match executions are reported back via the
 * ExecutionListener callbacks and published from there.
 *
 * P4-2: also the sole producer of the SNAPSHOT ring. After each inbound event is
 * processed, it reads a bounded depth snapshot off the engine (safe — this is the
 * engine thread) and publishes it. Two rings, one producer, one thread: both stay
 * SINGLE.
 *
 * P15-2 determinism (SRS §3.3, §5.8): the handler reads no clock. Every EXEC and BOOK
 * carries the timestamp of the inbound command that caused it, and the outbound seq is
 * handler state, so replaying the same commands regenerates identical events.
 *
 * P15-4: the live venue drives {@link #onCommand} from the journal-gated {@link EngineAgent}.
 * {@link #onEvent} remains for the unit tests and the unjournaled latency benchmark; Main no
 * longer wires this handler onto a ring.
 *
 * P15-5 recovery (SRS §3.10, D13). Between {@link #beginReplay} and {@link #endReplay} the
 * handler processes commands exactly as live, and every execution event still takes the next
 * seq, so replay regenerates identical seqs; but an event whose seq is at or below the publish
 * floor claims no ring slot, and no BOOK is published per command. {@link #endReplay} then
 * publishes one BOOK of the recovered book. From P15-6 the floor is the last seq in the outbound
 * recording, so only events the recorder never wrote are published.
 *
 * P15-6 divergence check: replay may also be given the recorded event carrying the floor seq.
 * When replay regenerates that seq, every field is compared with the recording and a difference
 * throws, so a build that no longer reproduces the journal's executions refuses to start instead
 * of numbering new events on top of a different history. Live matching never reaches the check.
 */
public final class MatchingEngineHandler implements EventHandler<OrderEvent>, CommandHandler, ExecutionListener {

    /** Demo simplification: the FIX subset carries no participant; the gateway is stateless. */
    private static final long GATEWAY_PARTICIPANT_ID = 1L;
    private static final long NA = -1L;

    private final MatchingEngine engine;
    private final RingBuffer<ExecutionEvent> outbound;
    /** Nullable: null means "no depth feed wired" (pre-Phase-4 callers, engine-only tests). */
    private final RingBuffer<BookSnapshotEvent> snapshots;

    /** Timestamp of the command being processed; stamps every EXEC and the BOOK it causes. */
    private long commandTimestamp;

    /** Outbound seq of the last published execution event (D12). 0 until the first publish. */
    private long lastSeq;

    /** Events with seq at or below this are not published (D13). 0 outside recovery: suppresses nothing. */
    private long publishFloor;

    /** True between beginReplay and endReplay: no per-command BOOK. */
    private boolean replaying;

    /** The recorded event carrying seq {@link #publishFloor}, checked when replay regenerates it; null for none. */
    private ExecutionEvent expectedAtFloor;

    public MatchingEngineHandler(MatchingEngine engine, RingBuffer<ExecutionEvent> outbound) {
        this(engine, outbound, null);
    }

    /** Phase 4 form: both outbound rings. Used by Main (P4-7). */
    public MatchingEngineHandler(MatchingEngine engine,
                                 RingBuffer<ExecutionEvent> outbound,
                                 RingBuffer<BookSnapshotEvent> snapshots) {
        this.engine = engine;
        this.outbound = outbound;
        this.snapshots = snapshots;
    }

    /** Outbound seq of the last published execution event; 0 before the first. */
    public long getLastSeq() {
        return lastSeq;
    }

    /**
     * Restores the outbound seq from a snapshot (P15-5 recovery only). The next execution
     * event carries {@code lastSeq + 1}.
     */
    public void restoreLastSeq(long lastSeq) {
        if (lastSeq < 0) {
            throw new IllegalArgumentException("lastSeq must be >= 0, got " + lastSeq);
        }
        this.lastSeq = lastSeq;
    }

    /** Timestamp of the last command processed (or restored); stamps the post-recovery BOOK. */
    public long getCommandTimestamp() {
        return commandTimestamp;
    }

    /** Restores the last command's timestamp from a snapshot (P15-5 recovery only). */
    public void restoreCommandTimestamp(long timestamp) {
        this.commandTimestamp = timestamp;
    }

    /**
     * Enters replay (P15-5 recovery): events with seq at or below {@code floor} are not
     * published, and no BOOK is published per command, until {@link #endReplay}.
     */
    public void beginReplay(long floor) {
        beginReplay(floor, null);
    }

    /**
     * As {@link #beginReplay(long)}, and when replay regenerates seq {@code floor} it must equal
     * {@code expectedAtFloor} (the last event in the outbound recording) in every field, or
     * processing throws {@link IllegalStateException}. Null skips the check.
     */
    public void beginReplay(long floor, ExecutionEvent expectedAtFloor) {
        if (floor < 0) {
            throw new IllegalArgumentException("publish floor must be >= 0, got " + floor);
        }
        if (expectedAtFloor != null && expectedAtFloor.seq != floor) {
            throw new IllegalArgumentException("expected event has seq " + expectedAtFloor.seq + ", floor is " + floor);
        }
        this.publishFloor = floor;
        this.expectedAtFloor = expectedAtFloor;
        this.replaying = true;
    }

    /**
     * Leaves replay: the floor drops to the last seq, so no later event is suppressed. With
     * {@code publishBook}, one BOOK of the recovered book is published, stamped with the last
     * command's timestamp and seq, so snapshot-on-connect shows the recovered book before any new
     * command; a venue with no history passes false and publishes nothing, as before P15-5. Call
     * from the thread that will produce on the rings next, or before that thread starts.
     */
    public void endReplay(boolean publishBook) {
        this.publishFloor = lastSeq;
        this.expectedAtFloor = null;
        this.replaying = false;
        if (publishBook) {
            publishSnapshot();
        }
    }

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        onCommand(event);
    }

    /**
     * The single processing entry point: takes the command's timestamp, processes it, then
     * publishes exactly one depth snapshot, on every path including rejects. "One snapshot
     * per inbound event" is a simpler invariant than "one per book-mutating event"; a redundant
     * snapshot after a reject is harmless and costs nothing at demo volume.
     *
     * Public so a caller without the Disruptor (the P15-4 engine agent) drives exactly the same
     * path as onEvent.
     */
    @Override
    public void onCommand(OrderEvent event) {
        commandTimestamp = event.timestamp;
        process(event);
        if (!replaying) {
            publishSnapshot();
        }
    }

    private void process(OrderEvent event) {
        if (event.eventType == OrderEventType.CANCEL_ORDER) {
            boolean cancelled = engine.cancelOrder(event.originalOrderId);
            publish(cancelled ? ExecutionEventType.ORDER_CANCELLED : ExecutionEventType.ORDER_REJECTED,
                    event.originalOrderId, NA, NA, NA, NA, NA, NA, null, NA, NA);
            return;
        }

        // D3 (SRS §3.3): a NEW reusing the id of an order still resting is rejected before any
        // Order is built, so the resting order stays fillable and cancellable.
        if (engine.hasOpenOrder(event.orderId)) {
            publish(ExecutionEventType.ORDER_REJECTED, event.orderId, NA, NA, NA, NA, NA, NA, null, NA, NA);
            return;
        }

        // NEW_ORDER: domain validation fires here, in the Order constructor.
        // A null tif reaching the engine means the slot carried no tag 59, which the wire
        // maps to GTC; coalesce here so the canonical constructor's non-null guard stays a
        // guard for direct callers rather than a reject path for the live pipeline.
        TimeInForce tif = (event.tif == null) ? TimeInForce.GTC : event.tif;
        // A null ordType reaching the engine means the slot carried no tag 40, which the wire maps
        // to LIMIT; coalesce here exactly as tif is, so the canonical constructor's non-null guard
        // stays a guard for direct callers rather than a reject path for the live pipeline.
        OrdType ordType = (event.ordType == null) ? OrdType.LIMIT : event.ordType;
        Order order;
        try {
            order = new Order(
                    event.orderId,
                    event.timestamp,
                    event.side,
                    (int) event.quantity,
                    event.price,
                    GATEWAY_PARTICIPANT_ID,
                    tif,
                    (int) event.maxFloor,  // iceberg display size (tag 111); 0 means a plain order
                    ordType                // LIMIT or PEG_MID (tag 40)
            );
        } catch (IllegalArgumentException rejected) {
            publish(ExecutionEventType.ORDER_REJECTED, event.orderId, NA, NA, NA, NA, NA, NA, null, NA, NA);
            return;
        }

        engine.addOrder(order);   // drives matching; fires onFill / onAccepted below
    }

    // --- ExecutionListener: fired synchronously by the engine during addOrder ---

    @Override
    public void onFill(long aggressorOrderId, long passiveOrderId, long tradeId,
                       long price, long filledQuantity, long aggressorRemainingQuantity,
                       Side aggressorSide, long aggressorParticipantId, long passiveParticipantId) {
        ExecutionEventType type = (aggressorRemainingQuantity == 0)
                ? ExecutionEventType.ORDER_FILLED
                : ExecutionEventType.ORDER_PARTIALLY_FILLED;
        publish(type, aggressorOrderId, tradeId, price, filledQuantity,
                aggressorRemainingQuantity, aggressorOrderId, passiveOrderId,
                aggressorSide, aggressorParticipantId, passiveParticipantId);
    }

    @Override
    public void onAccepted(long orderId, long price, long remainingQuantity) {
        publish(ExecutionEventType.ORDER_ACCEPTED, orderId, NA, price, NA, remainingQuantity, NA, NA, null, NA, NA);
    }

    @Override
    public void onExpired(long orderId, long expiredQuantity) {
        // ORDER_EXPIRED carries the order id and the quantity that expired unexecuted in
        // remainingQuantity; every other field is the NA sentinel (Phase 14 D5, SRS §3.4).
        publish(ExecutionEventType.ORDER_EXPIRED, orderId, NA, NA, NA, expiredQuantity, NA, NA, null, NA, NA);
    }

    // --- single outbound publish point: writes EVERY field (slot-reuse discipline) ---

    private void publish(ExecutionEventType type, long orderId, long tradeId, long price,
                         long filledQuantity, long remainingQuantity,
                         long aggressorOrderId, long passiveOrderId,
                         Side aggressorSide, long aggressorParticipantId, long passiveParticipantId) {
        final long seq = ++lastSeq;   // D12: gap-free from 1, assigned only here, published or not
        if (seq <= publishFloor) {
            if (seq == publishFloor && expectedAtFloor != null) {
                checkAgainstRecording(seq, type, orderId, tradeId, price, filledQuantity, remainingQuantity,
                        aggressorOrderId, passiveOrderId, aggressorSide, aggressorParticipantId, passiveParticipantId);
            }
            return;                   // D13: replayed and already accounted for
        }
        long slot = outbound.next();
        try {
            ExecutionEvent e = outbound.get(slot);
            e.seq                    = seq;
            e.eventType              = type;
            e.orderId                = orderId;
            e.tradeId                = tradeId;
            e.price                  = price;
            e.filledQuantity         = filledQuantity;
            e.remainingQuantity      = remainingQuantity;
            e.aggressorOrderId       = aggressorOrderId;
            e.passiveOrderId         = passiveOrderId;
            e.aggressorSide          = aggressorSide;
            e.aggressorParticipantId = aggressorParticipantId;
            e.passiveParticipantId   = passiveParticipantId;
            // Written as a placeholder so no reused slot carries a stale value; P15-8 sets it
            // on SESSION_OPENED / SESSION_CLOSED.
            e.businessDate           = (int) NA;
            e.timestamp              = commandTimestamp;
        } finally {
            outbound.publish(slot);
        }
    }

    /** Recovery only: the regenerated event at the floor must be the one the recording holds. */
    private void checkAgainstRecording(long seq, ExecutionEventType type, long orderId, long tradeId, long price,
                                       long filledQuantity, long remainingQuantity,
                                       long aggressorOrderId, long passiveOrderId,
                                       Side aggressorSide, long aggressorParticipantId, long passiveParticipantId) {
        ExecutionEvent r = expectedAtFloor;
        boolean same = r.eventType == type && r.orderId == orderId && r.tradeId == tradeId && r.price == price
                && r.filledQuantity == filledQuantity && r.remainingQuantity == remainingQuantity
                && r.aggressorOrderId == aggressorOrderId && r.passiveOrderId == passiveOrderId
                && r.aggressorSide == aggressorSide && r.aggressorParticipantId == aggressorParticipantId
                && r.passiveParticipantId == passiveParticipantId && r.businessDate == (int) NA
                && r.timestamp == commandTimestamp;
        if (!same) {
            throw new IllegalStateException("Replay regenerated a different event at outbound seq " + seq
                    + " than the outbound recording holds. Recorded: " + describe(r.eventType, r.orderId, r.tradeId,
                    r.price, r.filledQuantity, r.remainingQuantity, r.aggressorOrderId, r.passiveOrderId,
                    r.aggressorSide, r.aggressorParticipantId, r.passiveParticipantId, r.timestamp)
                    + "; regenerated: " + describe(type, orderId, tradeId, price, filledQuantity, remainingQuantity,
                    aggressorOrderId, passiveOrderId, aggressorSide, aggressorParticipantId, passiveParticipantId,
                    commandTimestamp));
        }
    }

    private static String describe(ExecutionEventType type, long orderId, long tradeId, long price,
                                   long filledQuantity, long remainingQuantity, long aggressorOrderId,
                                   long passiveOrderId, Side aggressorSide, long aggressorParticipantId,
                                   long passiveParticipantId, long timestamp) {
        return type + " orderId=" + orderId + " tradeId=" + tradeId + " price=" + price
                + " filled=" + filledQuantity + " remaining=" + remainingQuantity
                + " aggressor=" + aggressorOrderId + " passive=" + passiveOrderId + " side=" + aggressorSide
                + " participants=" + aggressorParticipantId + "/" + passiveParticipantId + " timestamp=" + timestamp;
    }

    /**
     * Reads the book into a claimed snapshot slot and publishes it. Runs on the
     * engine thread — the only thread that may read bids/asks unsynchronized.
     *
     * snapshotInto fills every book field and the valid array prefix; the level counts
     * are authoritative, so array tails beyond them are intentionally left stale
     * (BookSnapshotEvent's reuse contract) and consumers must not read past the
     * counts. The handler then writes the command's timestamp and the seq of the last
     * execution event published before this snapshot.
     */
    private void publishSnapshot() {
        if (snapshots == null) return;   // no depth feed wired

        long slot = snapshots.next();
        try {
            BookSnapshotEvent s = snapshots.get(slot);
            engine.snapshotInto(s, BookSnapshotEvent.MAX_DEPTH_LEVELS);
            s.lastSeq = lastSeq;
            s.timestamp = commandTimestamp;
        } finally {
            snapshots.publish(slot);
        }
    }
}
