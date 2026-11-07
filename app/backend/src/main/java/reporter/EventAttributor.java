package reporter;

import engine.MatchingEngineHandler;
import event.ExecutionEvent;
import event.OrderEvent;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns journaled commands and execution events into rows (SRS 3.11). Pure logic: no JDBC, no Aeron,
 * no clock of its own.
 *
 * Every event carries the inbound seq of the command that caused it. That is not always the order
 * the event is about, so attribution also needs the map of resting orders:
 * <ul>
 *   <li>ACCEPTED, EXPIRED, and REJECTED of a NEW: the NEW's own row. A duplicate-id reject therefore
 *       never touches the resting order under that id.</li>
 *   <li>REJECTED of a CANCEL: no order. The engine rejects a cancel only when the id is not resting.</li>
 *   <li>CANCELLED: the resting order.</li>
 *   <li>A fill naming the NEW as aggressor: the NEW's own row. D3 means no resting order shares a live
 *       NEW's id. Any other aggressor is a resting peg crossing another in {@code crossPegPools}, under
 *       a lit NEW: resolved through the map.</li>
 *   <li>The passive side of a fill: always the map. Passive orders get no event of their own, so their
 *       cumulative quantity is tracked here and they leave the map at zero remaining. A reused id is
 *       therefore safe.</li>
 * </ul>
 *
 * The caller feeds commands up to an event's {@code commandSeq} before that event, and must not end a
 * batch between the two: a resume re-reads neither. Only a NEW can have more events after a batch
 * boundary, and its row is what a resume reloads as {@link Resume#current()}.
 *
 * Every rule the engine guarantees is checked, and a breach throws
 * {@link ReporterInconsistencyException}: nothing is ever skipped or guessed.
 */
final class EventAttributor {

    /** One order the attributor may still see events for. Its row is the source of truth on a resume. */
    static final class OpenOrder {
        final long inboundSeq;
        final long orderId;
        final long quantity;
        long cumQuantity;

        OpenOrder(long inboundSeq, long orderId, long quantity, long cumQuantity) {
            this.inboundSeq = inboundSeq;
            this.orderId = orderId;
            this.quantity = quantity;
            this.cumQuantity = cumQuantity;
        }

        long remaining() {
            return quantity - cumQuantity;
        }
    }

    /**
     * Where a resume continues from, as the checkpoint's transaction left it.
     *
     * @param current the NEW at {@code inboundSeq}, or null if that command was not a NEW
     */
    record Resume(long inboundSeq, long outboundSeq, Integer sessionDate, String sessionSymbol,
                  Collection<OpenOrder> resting, OpenOrder current) {

        static Resume empty() {
            return new Resume(0L, 0L, null, null, java.util.List.of(), null);
        }
    }

    private enum Pending { NONE, NEW, CANCEL, SESSION_OPEN, SESSION_CLOSE }

    private final Map<Long, OpenOrder> resting = new HashMap<>();
    private long inboundSeq;
    private long outboundSeq;
    private Integer sessionDate;
    private String sessionSymbol;

    /** What the last command consumed can still explain. */
    private Pending pending;
    /** The NEW being processed, kept after it rests: its peg-cross fills arrive under the same command. */
    private OpenOrder current;
    /** Whether a NEW or CANCEL has had an event yet: one with none is a bug, not a refusal. */
    private boolean eventSeen;
    private long cancelTarget;
    private int pendingDate;
    private String pendingSymbol;

    EventAttributor(Resume resume) {
        this.inboundSeq = resume.inboundSeq();
        this.outboundSeq = resume.outboundSeq();
        this.sessionDate = resume.sessionDate();
        this.sessionSymbol = resume.sessionSymbol();
        for (OpenOrder order : resume.resting()) {
            resting.put(order.orderId, order);
        }
        OpenOrder resumed = resume.current();
        if (resumed != null) {
            OpenOrder same = resting.get(resumed.orderId);
            this.current = (same != null && same.inboundSeq == resumed.inboundSeq) ? same : resumed;
            this.pending = Pending.NEW;
        } else {
            this.pending = Pending.NONE;
        }
        this.eventSeen = true;
    }

    long inboundSeq() {
        return inboundSeq;
    }

    long outboundSeq() {
        return outboundSeq;
    }

    Integer sessionDate() {
        return sessionDate;
    }

    String sessionSymbol() {
        return sessionSymbol;
    }

    /** Resting orders tracked; bounded by the book. */
    int restingCount() {
        return resting.size();
    }

    /**
     * Checks an event can come next, before its command is read: a version 0 event, a gap, or a command
     * seq going backwards.
     */
    void checkNext(ExecutionEvent e) {
        if (e.commandSeq == -1L) {
            throw new ReporterInconsistencyException("Outbound seq " + e.seq + " carries no command seq: it was recorded"
                    + " by a build from before P15-10, so it cannot be attributed to an order. The venue is unaffected."
                    + " To report this journal's future, move the data directory aside to start a fresh history, or run"
                    + " without DB_URL to keep it.");
        }
        if (e.seq != outboundSeq + 1) {
            throw new ReporterInconsistencyException("Outbound seq " + e.seq + " after checkpointed seq " + outboundSeq
                    + ": this database does not match this journal");
        }
        if (e.commandSeq < inboundSeq) {
            throw new ReporterInconsistencyException("Outbound seq " + e.seq + " names command " + e.commandSeq
                    + " after command " + inboundSeq + " was consumed");
        }
    }

    /** Whether {@code e}'s command has been consumed, so {@link #onEvent} may run. */
    boolean ready(ExecutionEvent e) {
        return e.commandSeq <= inboundSeq;
    }

    /** Consumes the next inbound command. The carrier is reused: nothing is kept past the call. */
    void onCommand(long seq, OrderEvent c, ReporterBatch batch) {
        if (seq != inboundSeq + 1) {
            throw new ReporterInconsistencyException("Inbound seq " + seq + " after checkpointed seq " + inboundSeq
                    + ": this database does not match this journal");
        }
        if ((pending == Pending.NEW || pending == Pending.CANCEL) && !eventSeen) {
            throw new ReporterInconsistencyException("Command " + inboundSeq + " (" + pending + ") produced no event");
        }
        inboundSeq = seq;
        eventSeen = false;
        current = null;
        pendingSymbol = null;
        switch (c.eventType) {
            case NEW_ORDER -> {
                pending = Pending.NEW;
                current = new OpenOrder(seq, c.orderId, c.quantity, 0L);
                batch.orders.add(new ReporterBatch.OrderRow(seq, c.orderId, MatchingEngineHandler.GATEWAY_PARTICIPANT_ID,
                        sessionDate, sessionSymbol, c.side.name(), c.ordType.name(), c.tif.name(),
                        nullable(c.price), c.quantity, c.maxFloor == 0L ? null : c.maxFloor, c.timestamp));
                batch.audit.add(new ReporterBatch.AuditRow("IN", seq, seq, c.orderId, "NEW_ORDER", sessionDate,
                        c.timestamp, nullable(c.price), c.quantity, null));
            }
            case CANCEL_ORDER -> {
                pending = Pending.CANCEL;
                cancelTarget = c.originalOrderId;
                OpenOrder target = resting.get(c.originalOrderId);
                batch.audit.add(new ReporterBatch.AuditRow("IN", seq, target == null ? null : target.inboundSeq,
                        c.originalOrderId, "CANCEL_ORDER", sessionDate, c.timestamp, null, null, null));
            }
            case SESSION_OPEN -> {
                pending = Pending.SESSION_OPEN;
                pendingDate = c.businessDate;
                pendingSymbol = symbol(c.symbol);
            }
            case SESSION_CLOSE -> {
                pending = Pending.SESSION_CLOSE;
                pendingDate = c.businessDate;
            }
            default -> throw new ReporterInconsistencyException("Unknown command type " + c.eventType + " at seq " + seq);
        }
    }

    /** Applies the next outbound event; {@link #checkNext} passed and {@link #ready} is true. */
    void onEvent(ExecutionEvent e, ReporterBatch batch, long nowNanos) {
        checkNext(e);
        if (e.commandSeq != inboundSeq) {
            // Commands in between were read to reach this one, and each was checked when the next arrived.
            throw new ReporterInconsistencyException("Outbound seq " + e.seq + " names command " + e.commandSeq
                    + " but the last command consumed is " + inboundSeq);
        }
        switch (e.eventType) {
            case SESSION_OPENED -> sessionOpened(e);
            case SESSION_CLOSED -> sessionClosed(e, batch);
            case ORDER_ACCEPTED -> accepted(e, batch);
            case ORDER_EXPIRED -> expired(e, batch);
            case ORDER_REJECTED -> rejected(e, batch);
            case ORDER_CANCELLED -> cancelled(e, batch);
            case ORDER_FILLED, ORDER_PARTIALLY_FILLED -> filled(e, batch);
            default -> throw inconsistent(e, "an unknown event type");
        }
        eventSeen = true;
        outboundSeq = e.seq;
        batch.eventAdded(nowNanos);
    }

    private void sessionOpened(ExecutionEvent e) {
        if (pending != Pending.SESSION_OPEN || pendingDate != e.businessDate) {
            throw inconsistent(e, "SESSION_OPENED for date " + e.businessDate + " without a matching SESSION_OPEN");
        }
        sessionDate = e.businessDate;
        sessionSymbol = pendingSymbol;
        pending = Pending.NONE;
    }

    /** Closes the day and records its pending clearing run, committed with the checkpoint that passes it (P15-11). */
    private void sessionClosed(ExecutionEvent e, ReporterBatch batch) {
        if (pending != Pending.SESSION_CLOSE || pendingDate != e.businessDate
                || sessionDate == null || sessionDate != e.businessDate) {
            throw inconsistent(e, "SESSION_CLOSED for date " + e.businessDate + " while the session date is " + sessionDate);
        }
        batch.pendingRuns.add(new ReporterBatch.PendingRun(e.businessDate, e.seq, e.timestamp));
        sessionDate = null;
        sessionSymbol = null;
        pending = Pending.NONE;
    }

    private OpenOrder theNew(ExecutionEvent e) {
        if (pending != Pending.NEW || current == null || current.orderId != e.orderId) {
            throw inconsistent(e, e.eventType + " for order " + e.orderId + " not under its own NEW");
        }
        return current;
    }

    private void accepted(ExecutionEvent e, ReporterBatch batch) {
        OpenOrder order = theNew(e);
        requireRemaining(e, order, e.remainingQuantity);
        OpenOrder other = resting.get(order.orderId);
        if (other != null && other != order) {
            throw inconsistent(e, "ORDER_ACCEPTED for id " + order.orderId + " already resting as command " + other.inboundSeq);
        }
        resting.put(order.orderId, order);
        batch.orderUpdates.add(new ReporterBatch.OrderUpdate(order.inboundSeq,
                order.cumQuantity == 0L ? "OPEN" : "PARTIALLY_FILLED", order.cumQuantity, e.seq));
        report(e, order.inboundSeq, null, batch);
    }

    private void expired(ExecutionEvent e, ReporterBatch batch) {
        OpenOrder order = theNew(e);
        requireRemaining(e, order, e.remainingQuantity);
        batch.orderUpdates.add(new ReporterBatch.OrderUpdate(order.inboundSeq, "EXPIRED", order.cumQuantity, e.seq));
        report(e, order.inboundSeq, null, batch);
        pending = Pending.NONE;
    }

    private void rejected(ExecutionEvent e, ReporterBatch batch) {
        if (pending == Pending.CANCEL) {
            if (e.orderId != cancelTarget) {
                throw inconsistent(e, "ORDER_REJECTED for order " + e.orderId + " under a CANCEL of " + cancelTarget);
            }
            if (resting.containsKey(e.orderId)) {
                throw inconsistent(e, "a CANCEL rejected while order " + e.orderId + " is resting");
            }
            report(e, null, null, batch);
        } else {
            OpenOrder order = theNew(e);
            batch.orderUpdates.add(new ReporterBatch.OrderUpdate(order.inboundSeq, "REJECTED", 0L, e.seq));
            report(e, order.inboundSeq, null, batch);
        }
        pending = Pending.NONE;
    }

    private void cancelled(ExecutionEvent e, ReporterBatch batch) {
        if (pending != Pending.CANCEL || e.orderId != cancelTarget) {
            throw inconsistent(e, "ORDER_CANCELLED for order " + e.orderId + " not under its CANCEL");
        }
        OpenOrder order = resting.remove(e.orderId);
        if (order == null) {
            throw inconsistent(e, "ORDER_CANCELLED for order " + e.orderId + ", which is not resting");
        }
        batch.orderUpdates.add(new ReporterBatch.OrderUpdate(order.inboundSeq, "CANCELLED", order.cumQuantity, e.seq));
        report(e, order.inboundSeq, null, batch);
        pending = Pending.NONE;
    }

    private void filled(ExecutionEvent e, ReporterBatch batch) {
        if (pending != Pending.NEW || current == null) {
            throw inconsistent(e, "a fill not under a NEW");
        }
        if (sessionDate == null || sessionSymbol == null) {
            throw inconsistent(e, "a fill outside a session");
        }
        OpenOrder aggressor = (e.aggressorOrderId == current.orderId) ? current : restingOrder(e, e.aggressorOrderId);
        OpenOrder passive = restingOrder(e, e.passiveOrderId);
        if (aggressor == passive) {
            throw inconsistent(e, "order " + e.aggressorOrderId + " on both sides of a fill");
        }

        aggressor.cumQuantity += e.filledQuantity;
        passive.cumQuantity += e.filledQuantity;
        requireRemaining(e, aggressor, e.remainingQuantity);
        if (passive.remaining() < 0) {
            throw inconsistent(e, "passive order " + passive.orderId + " overfilled");
        }
        if (aggressor.remaining() == 0L) {
            resting.remove(aggressor.orderId, aggressor);
        }
        if (passive.remaining() == 0L) {
            resting.remove(passive.orderId);
        }

        batch.orderUpdates.add(new ReporterBatch.OrderUpdate(aggressor.inboundSeq, fillStatus(aggressor),
                aggressor.cumQuantity, e.seq));
        batch.orderUpdates.add(new ReporterBatch.OrderUpdate(passive.inboundSeq, fillStatus(passive),
                passive.cumQuantity, e.seq));
        report(e, aggressor.inboundSeq, passive.inboundSeq, batch);
        batch.candleFills.add(new ReporterBatch.CandleFill(sessionSymbol, sessionDate, Candles.bucketStart(e.timestamp),
                e.price, e.filledQuantity, e.seq));
    }

    private OpenOrder restingOrder(ExecutionEvent e, long orderId) {
        OpenOrder order = resting.get(orderId);
        if (order == null) {
            throw inconsistent(e, "a fill names order " + orderId + ", which is not resting");
        }
        return order;
    }

    private static String fillStatus(OpenOrder order) {
        return order.remaining() == 0L ? "FILLED" : "PARTIALLY_FILLED";
    }

    private void requireRemaining(ExecutionEvent e, OpenOrder order, long reported) {
        if (reported != order.remaining()) {
            throw inconsistent(e, "the event reports " + reported + " remaining on order " + order.orderId
                    + " (command " + order.inboundSeq + "), the journal gives " + order.remaining());
        }
    }

    private void report(ExecutionEvent e, Long orderInboundSeq, Long passiveInboundSeq, ReporterBatch batch) {
        String type = e.eventType.name();
        batch.executions.add(new ReporterBatch.ExecutionRow(e.seq, type, orderInboundSeq, e.orderId, nullable(e.tradeId),
                nullable(e.price), nullable(e.filledQuantity), nullable(e.remainingQuantity),
                nullable(e.aggressorOrderId), nullable(e.passiveOrderId), passiveInboundSeq,
                e.aggressorSide == null ? null : e.aggressorSide.name(),
                nullable(e.aggressorParticipantId), nullable(e.passiveParticipantId), sessionDate, e.timestamp));
        batch.audit.add(new ReporterBatch.AuditRow("OUT", e.seq, orderInboundSeq, e.orderId, type, sessionDate,
                e.timestamp, nullable(e.price), nullable(e.filledQuantity), nullable(e.remainingQuantity)));
    }

    private ReporterInconsistencyException inconsistent(ExecutionEvent e, String what) {
        return new ReporterInconsistencyException("Outbound seq " + e.seq + " (" + e.eventType + ", command "
                + e.commandSeq + "): " + what + ". The journal breaks a rule the engine guarantees, or this database"
                + " does not match it. Nothing was skipped; keep both for investigation.");
    }

    /** -1 is the journal's "does not apply"; the database's is NULL. */
    private static Long nullable(long value) {
        return value == -1L ? null : value;
    }

    /** The ASCII symbol from its NUL-padded bytes. */
    private static String symbol(byte[] bytes) {
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }
}
