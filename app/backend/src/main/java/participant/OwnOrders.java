package participant;

import event.ExecutionEventType;
import model.Side;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One bot's book-keeping, fed from the EXEC stream: the GTC orders it still has resting, and its
 * net position. Shared by every strategy so each reconciles fills the same way.
 *
 * <p><b>Position.</b> A fill moves the position whenever one of this bot's ids (by
 * {@link Bots#owner}) is on either side: the aggressor trades {@code aggressorSide}, the passive
 * order the opposite. A trade between two of the bot's own orders moves it both ways and nets to
 * zero, which is right.
 *
 * <p><b>Resting orders.</b> Only GTC orders are recorded (an IOC or FOK never rests). The engine
 * reports a passive order's fills only through {@code passiveOrderId} on the aggressor's fill, so
 * a passive fill decrements the remaining quantity here; an aggressor fill or an ACCEPTED states
 * it directly. A CANCELLED, REJECTED or EXPIRED report removes the order. A cancel this bot has
 * sent but the venue has not answered yet is "pending": the order still counts as resting, but
 * is never cancelled twice.
 *
 * <p>Executor-thread confined, like the strategy that owns it.
 */
public final class OwnOrders {

    /** A resting order of this bot. Mutable: remaining and the pending flag change in place. */
    public static final class Resting {
        public final long id;
        public final Side side;
        public final long price;
        long remaining;
        boolean cancelPending;

        Resting(long id, Side side, long price, long remaining) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
        }

        public long remaining() {
            return remaining;
        }

        public boolean cancelPending() {
            return cancelPending;
        }
    }

    private final Bots.Owner owner;
    private final Bots.IdSequence ids;
    private final Map<Long, Resting> resting = new LinkedHashMap<>();
    private long position;

    public OwnOrders(Bots.IdSequence ids) {
        this.owner = ids.owner();
        this.ids = ids;
    }

    /** The bot's id sequence; strategies draw new-order ids from here too. */
    public Bots.IdSequence ids() {
        return ids;
    }

    /** Net position in shares, + = long. */
    public long position() {
        return position;
    }

    /** Record an outgoing action. A GTC NEW starts resting; everything else is not tracked. */
    public Action sent(Action a) {
        if (a.kind() == Action.Kind.NEW && a.tif() == model.TimeInForce.GTC) {
            resting.put(a.clOrdId(), new Resting(a.clOrdId(), a.side(), a.priceUnits(), a.qty()));
        }
        return a;
    }

    /** A cancel for one resting order, or null if it is unknown or already being cancelled. */
    public Action cancel(long orderId) {
        Resting r = resting.get(orderId);
        if (r == null || r.cancelPending) {
            return null;
        }
        r.cancelPending = true;
        return Action.cancel(ids.next(), orderId);
    }

    /** Cancels for every resting order not already being cancelled. */
    public List<Action> cancelAll() {
        List<Action> out = new ArrayList<>();
        for (Resting r : resting.values()) {
            if (!r.cancelPending) {
                r.cancelPending = true;
                out.add(Action.cancel(ids.next(), r.id));
            }
        }
        return out;
    }

    /**
     * Cancels for every resting order, including one whose earlier cancel is still in flight.
     * For a session close or a stop, where leaving an order behind is worse than a duplicate
     * cancel: a duplicate is answered with ORDER_REJECTED, which removes the order here too.
     */
    public List<Action> cancelEverything() {
        List<Action> out = new ArrayList<>();
        for (Resting r : resting.values()) {
            r.cancelPending = true;
            out.add(Action.cancel(ids.next(), r.id));
        }
        return out;
    }

    /** Every resting order, oldest first, including those with a cancel pending. */
    public Collection<Resting> resting() {
        return resting.values();
    }

    /** Resting orders with no cancel pending. */
    public List<Resting> working() {
        List<Resting> out = new ArrayList<>();
        for (Resting r : resting.values()) {
            if (!r.cancelPending) out.add(r);
        }
        return out;
    }

    /** Total quantity still working (no cancel pending) on {@code side}. */
    public long workingQty(Side side) {
        long q = 0;
        for (Resting r : resting.values()) {
            if (!r.cancelPending && r.side == side) q += r.remaining;
        }
        return q;
    }

    public void onExec(ExecReport e) {
        ExecutionEventType t = e.execType();
        if (ParticipantClient.isFill(t)) {
            onFill(e);
            return;
        }
        Resting r = resting.get(e.orderId());
        if (r == null) {
            return;
        }
        switch (t) {
            case ORDER_ACCEPTED -> r.remaining = e.remainingQuantity();
            case ORDER_CANCELLED, ORDER_REJECTED, ORDER_EXPIRED -> resting.remove(e.orderId());
            default -> { }
        }
    }

    private void onFill(ExecReport e) {
        long qty = e.filledQuantity();
        if (qty <= 0) {
            return;
        }
        boolean aggMine = Bots.owner(e.aggressorOrderId()) == owner;
        boolean pasMine = Bots.owner(e.passiveOrderId()) == owner;

        Side aggSide = e.side();
        if (aggSide == null) {
            // A server that does not state the side: recover it from our own record if we can.
            Resting own = aggMine ? resting.get(e.aggressorOrderId()) : null;
            Resting pas = pasMine ? resting.get(e.passiveOrderId()) : null;
            if (own != null) aggSide = own.side;
            else if (pas != null) aggSide = opposite(pas.side);
        }
        if (aggSide != null) {
            if (aggMine) position += (aggSide == Side.BUY) ? qty : -qty;
            if (pasMine) position += (aggSide == Side.BUY) ? -qty : qty;
        }

        if (aggMine) {
            Resting r = resting.get(e.aggressorOrderId());
            if (r != null) {
                r.remaining = e.remainingQuantity();
                if (r.remaining <= 0) resting.remove(r.id);
            }
        }
        if (pasMine) {
            Resting r = resting.get(e.passiveOrderId());
            if (r != null) {
                r.remaining -= qty;
                if (r.remaining <= 0) resting.remove(r.id);
            }
        }
    }

    static Side opposite(Side s) {
        return s == Side.BUY ? Side.SELL : Side.BUY;
    }
}
