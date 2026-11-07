package engine;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import event.BookSnapshotEvent;
import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import model.Order;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import util.VenueStatus;

import java.util.concurrent.atomic.AtomicLong;

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
 *
 * P15-8 session state (SRS §3.3, §3.12). The handler, as the command dispatcher, owns whether a
 * session is open, its business date, the last closed business date and the session's symbol.
 * A NEW is matched only while a session is open; while closed it is rejected with ORDER_REJECTED.
 * A CANCEL is accepted in either state. SESSION_OPEN and SESSION_CLOSE are checked by
 * {@link SessionRules}; an accepted one changes the state and publishes SESSION_OPENED or
 * SESSION_CLOSED carrying the business date, a refused one changes nothing and publishes no
 * execution event. Every session command, accepted or not, still publishes one BOOK, like every
 * other command. The outcome depends only on the command and this state, so replay reproduces it.
 * Other threads read the state through {@link #sessionWord()}, which this thread publishes in one
 * atomic store so it can never tear.
 */
public final class MatchingEngineHandler implements EventHandler<OrderEvent>, CommandHandler, ExecutionListener {

    /** Demo simplification: the FIX subset carries no participant; the gateway is stateless. */
    public static final long GATEWAY_PARTICIPANT_ID = 1L;
    private static final long NA = -1L;

    private final MatchingEngine engine;
    private final RingBuffer<ExecutionEvent> outbound;
    /** Nullable: null means "no depth feed wired" (pre-Phase-4 callers, engine-only tests). */
    private final RingBuffer<BookSnapshotEvent> snapshots;

    /** Timestamp of the command being processed; stamps every EXEC and the BOOK it causes. */
    private long commandTimestamp;

    /** Inbound seq of the command being processed; -1 when driven with no journal behind it (P15-10). */
    private long commandSeq = NA;

    /** Outbound seq of the last published execution event (D12). 0 until the first publish. */
    private long lastSeq;

    /** Events with seq at or below this are not published (D13). 0 outside recovery: suppresses nothing. */
    private long publishFloor;

    /** True between beginReplay and endReplay: no per-command BOOK. */
    private boolean replaying;

    /** The recorded event carrying seq {@link #publishFloor}, checked when replay regenerates it; null for none. */
    private ExecutionEvent expectedAtFloor;

    /** Whether a session is open (P15-8). Closed at construction. */
    private boolean sessionOpen;

    /** The open session's business date (YYYYMMDD); -1 while closed. */
    private int businessDate = -1;

    /** The last closed business date; -1 if no session was ever closed. */
    private int lastClosedBusinessDate = -1;

    /** The symbol of the last session opened, NUL-padded; all NUL if none ever opened. */
    private final byte[] sessionSymbol = new byte[OrderEvent.SYMBOL_LENGTH];

    /** The session state for other threads, in {@link VenueStatus#sessionWord} form; written only here. */
    private final AtomicLong publishedSession = new AtomicLong(0L);

    /** Run after an accepted SESSION_CLOSE, on this thread (the snapshot request); null for none. */
    private Runnable sessionCloseHook;

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

    /** Whether a session is open. Engine thread. */
    public boolean isSessionOpen() {
        return sessionOpen;
    }

    /** The open session's business date, or -1 while closed. Engine thread. */
    public int getBusinessDate() {
        return businessDate;
    }

    /** The last closed business date, or -1 if none. Engine thread. */
    public int getLastClosedBusinessDate() {
        return lastClosedBusinessDate;
    }

    /** Copies the last opened session's 8-byte symbol into {@code dst}. Engine thread. */
    public void getSessionSymbol(byte[] dst) {
        System.arraycopy(sessionSymbol, 0, dst, 0, OrderEvent.SYMBOL_LENGTH);
    }

    /** The session state as a {@link VenueStatus#sessionWord}. Any thread. */
    public long sessionWord() {
        return publishedSession.get();
    }

    /**
     * Restores the session state from a snapshot (P15-8 recovery only), like {@link #restoreLastSeq}.
     * Publishes no event.
     *
     * @param businessDate the open day when {@code open}; ignored and stored as -1 when closed
     */
    public void restoreSession(boolean open, int businessDate, int lastClosedBusinessDate, byte[] symbol) {
        if (open && !SessionRules.isValidDate(businessDate)) {
            throw new IllegalArgumentException("open session with invalid business date " + businessDate);
        }
        if (lastClosedBusinessDate != -1 && !SessionRules.isValidDate(lastClosedBusinessDate)) {
            throw new IllegalArgumentException("invalid last closed business date " + lastClosedBusinessDate);
        }
        if (symbol.length != OrderEvent.SYMBOL_LENGTH) {
            throw new IllegalArgumentException("symbol must be " + OrderEvent.SYMBOL_LENGTH + " bytes");
        }
        this.sessionOpen = open;
        this.businessDate = open ? businessDate : -1;
        this.lastClosedBusinessDate = lastClosedBusinessDate;
        System.arraycopy(symbol, 0, sessionSymbol, 0, OrderEvent.SYMBOL_LENGTH);
        publishSession();
    }

    /** Run on this thread after every accepted SESSION_CLOSE: the snapshot at session close (SRS §3.10). */
    public void setSessionCloseHook(Runnable hook) {
        this.sessionCloseHook = hook;
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
        onCommand(event, NA);
    }

    /** The journaled form: every event this command causes carries {@code seq} (P15-10). */
    @Override
    public void onCommand(OrderEvent event, long seq) {
        commandSeq = seq;
        commandTimestamp = event.timestamp;
        process(event);
        if (!replaying) {
            publishSnapshot();
        }
    }

    private void process(OrderEvent event) {
        // Branch on every command type first: nothing falls through to NEW by default (P15-8).
        switch (event.eventType) {
            case NEW_ORDER:
                processNew(event);
                return;
            case CANCEL_ORDER:
                // Accepted in either session state, so participants can always withdraw GTC orders.
                boolean cancelled = engine.cancelOrder(event.originalOrderId);
                publish(cancelled ? ExecutionEventType.ORDER_CANCELLED : ExecutionEventType.ORDER_REJECTED,
                        event.originalOrderId, NA, NA, NA, NA, NA, NA, null, NA, NA, (int) NA);
                return;
            case SESSION_OPEN:
                openSession(event);
                return;
            case SESSION_CLOSE:
                closeSession(event);
                return;
            default:
                // Only a corrupt journal or a new type without a branch gets here: fail-stop, never guess.
                throw new IllegalStateException("Unhandled command type: " + event.eventType);
        }
    }

    /** SESSION_OPEN: opens if {@link SessionRules#checkOpen} accepts; a refusal changes nothing and publishes nothing. */
    private void openSession(OrderEvent event) {
        int verdict = SessionRules.checkOpen(sessionOpen, lastClosedBusinessDate, event.businessDate, event.symbol,
                event.tickSize, event.lotSize, event.priceScale);
        if (verdict != SessionRules.OK) {
            return;
        }
        sessionOpen = true;
        businessDate = event.businessDate;
        System.arraycopy(event.symbol, 0, sessionSymbol, 0, OrderEvent.SYMBOL_LENGTH);
        publishSession();
        publish(ExecutionEventType.SESSION_OPENED, NA, NA, NA, NA, NA, NA, NA, null, NA, NA, businessDate);
    }

    /** SESSION_CLOSE: closes the open day if {@link SessionRules#checkClose} accepts; a refusal changes nothing. */
    private void closeSession(OrderEvent event) {
        int verdict = SessionRules.checkClose(sessionOpen, businessDate, event.businessDate);
        if (verdict != SessionRules.OK) {
            return;
        }
        int closed = businessDate;
        sessionOpen = false;
        businessDate = -1;
        lastClosedBusinessDate = closed;
        publishSession();
        publish(ExecutionEventType.SESSION_CLOSED, NA, NA, NA, NA, NA, NA, NA, null, NA, NA, closed);
        if (sessionCloseHook != null) {
            sessionCloseHook.run();
        }
    }

    private void publishSession() {
        publishedSession.lazySet(VenueStatus.sessionWord(sessionOpen, businessDate, lastClosedBusinessDate));
    }

    private void processNew(OrderEvent event) {
        // SRS §3.3: a new order is matched only while a session is open.
        if (!sessionOpen) {
            publish(ExecutionEventType.ORDER_REJECTED, event.orderId, NA, NA, NA, NA, NA, NA, null, NA, NA, (int) NA);
            return;
        }

        // D3 (SRS §3.3): a NEW reusing the id of an order still resting is rejected before any
        // Order is built, so the resting order stays fillable and cancellable.
        if (engine.hasOpenOrder(event.orderId)) {
            publish(ExecutionEventType.ORDER_REJECTED, event.orderId, NA, NA, NA, NA, NA, NA, null, NA, NA, (int) NA);
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
            publish(ExecutionEventType.ORDER_REJECTED, event.orderId, NA, NA, NA, NA, NA, NA, null, NA, NA, (int) NA);
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
                aggressorSide, aggressorParticipantId, passiveParticipantId, (int) NA);
    }

    @Override
    public void onAccepted(long orderId, long price, long remainingQuantity) {
        publish(ExecutionEventType.ORDER_ACCEPTED, orderId, NA, price, NA, remainingQuantity, NA, NA, null, NA, NA, (int) NA);
    }

    @Override
    public void onExpired(long orderId, long expiredQuantity) {
        // ORDER_EXPIRED carries the order id and the quantity that expired unexecuted in
        // remainingQuantity; every other field is the NA sentinel (Phase 14 D5, SRS §3.4).
        publish(ExecutionEventType.ORDER_EXPIRED, orderId, NA, NA, NA, expiredQuantity, NA, NA, null, NA, NA, (int) NA);
    }

    // --- single outbound publish point: writes EVERY field (slot-reuse discipline) ---

    private void publish(ExecutionEventType type, long orderId, long tradeId, long price,
                         long filledQuantity, long remainingQuantity,
                         long aggressorOrderId, long passiveOrderId,
                         Side aggressorSide, long aggressorParticipantId, long passiveParticipantId,
                         int businessDate) {
        final long seq = ++lastSeq;   // D12: gap-free from 1, assigned only here, published or not
        if (seq <= publishFloor) {
            if (seq == publishFloor && expectedAtFloor != null) {
                checkAgainstRecording(seq, type, orderId, tradeId, price, filledQuantity, remainingQuantity,
                        aggressorOrderId, passiveOrderId, aggressorSide, aggressorParticipantId, passiveParticipantId,
                        businessDate);
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
            e.businessDate           = businessDate;   // SESSION_OPENED / SESSION_CLOSED only; -1 otherwise
            e.timestamp              = commandTimestamp;
            e.commandSeq             = commandSeq;
        } finally {
            outbound.publish(slot);
        }
    }

    /** Recovery only: the regenerated event at the floor must be the one the recording holds. */
    private void checkAgainstRecording(long seq, ExecutionEventType type, long orderId, long tradeId, long price,
                                       long filledQuantity, long remainingQuantity,
                                       long aggressorOrderId, long passiveOrderId,
                                       Side aggressorSide, long aggressorParticipantId, long passiveParticipantId,
                                       int businessDate) {
        ExecutionEvent r = expectedAtFloor;
        boolean same = r.eventType == type && r.orderId == orderId && r.tradeId == tradeId && r.price == price
                && r.filledQuantity == filledQuantity && r.remainingQuantity == remainingQuantity
                && r.aggressorOrderId == aggressorOrderId && r.passiveOrderId == passiveOrderId
                && r.aggressorSide == aggressorSide && r.aggressorParticipantId == aggressorParticipantId
                && r.passiveParticipantId == passiveParticipantId && r.businessDate == businessDate
                && r.timestamp == commandTimestamp
                // A recording from before schema version 1 cannot say; -1 means nothing to compare.
                && (r.commandSeq == NA || r.commandSeq == commandSeq);
        if (!same) {
            throw new IllegalStateException("Replay regenerated a different event at outbound seq " + seq
                    + " than the outbound recording holds. Recorded: " + describe(r.eventType, r.orderId, r.tradeId,
                    r.price, r.filledQuantity, r.remainingQuantity, r.aggressorOrderId, r.passiveOrderId,
                    r.aggressorSide, r.aggressorParticipantId, r.passiveParticipantId, r.businessDate, r.timestamp)
                    + " commandSeq=" + r.commandSeq
                    + "; regenerated: " + describe(type, orderId, tradeId, price, filledQuantity, remainingQuantity,
                    aggressorOrderId, passiveOrderId, aggressorSide, aggressorParticipantId, passiveParticipantId,
                    businessDate, commandTimestamp) + " commandSeq=" + commandSeq);
        }
    }

    private static String describe(ExecutionEventType type, long orderId, long tradeId, long price,
                                   long filledQuantity, long remainingQuantity, long aggressorOrderId,
                                   long passiveOrderId, Side aggressorSide, long aggressorParticipantId,
                                   long passiveParticipantId, int businessDate, long timestamp) {
        return type + " orderId=" + orderId + " tradeId=" + tradeId + " price=" + price
                + " filled=" + filledQuantity + " remaining=" + remainingQuantity
                + " aggressor=" + aggressorOrderId + " passive=" + passiveOrderId + " side=" + aggressorSide
                + " participants=" + aggressorParticipantId + "/" + passiveParticipantId
                + " businessDate=" + businessDate + " timestamp=" + timestamp;
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
