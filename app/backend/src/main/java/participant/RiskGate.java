package participant;

import event.ExecutionEventType;
import model.Prices;
import model.TimeInForce;

/**
 * The last check before anything a bot decides reaches the socket (P16-5, D16). Every NEW passes
 * it; a CANCEL always passes, because withdrawing an order is never a risk.
 *
 * <p>A NEW is refused when its quantity is above {@code maxOrderQty}; when it is GTC and the bot
 * already has {@code maxOpenOrders} orders working (no cancel pending); when its price is not on
 * the tick inside the band; or when {@link RiskLimits#allows} says it would take the position past
 * the bot's limit. The strategies apply the same band and position rule themselves, so in normal
 * running the gate never fires: it is the backstop that holds whatever a strategy does.
 *
 * <p>The gate keeps its own {@link OwnOrders}, fed with exactly what was sent and with the EXEC
 * stream, so its position and open-order count are the venue's view of this bot, not the
 * strategy's intentions. A refused NEW is never sent; the client hands the strategy a local
 * ORDER_REJECTED for it ({@link #refusal}), so the strategy forgets it at once and no phantom
 * resting order is left to block a stop.
 *
 * <p>Single-thread confined, like the strategy and the client core that own it.
 */
public final class RiskGate {

    /** Why a NEW was refused; null means it passed. */
    public enum Refusal { ORDER_TOO_LARGE, TOO_MANY_OPEN_ORDERS, OUTSIDE_BAND, POSITION_LIMIT }

    private final RiskLimits risk;
    private final long maxOrderQty;
    private final int maxOpenOrders;
    private final OwnOrders book;
    private long refused;

    /**
     * @param owner         the bot's id block
     * @param risk          the bot's band and position limit (the same instance its strategy uses)
     * @param maxOrderQty   largest quantity of one NEW
     * @param maxOpenOrders most GTC orders working at once
     */
    public RiskGate(Bots.Owner owner, RiskLimits risk, long maxOrderQty, int maxOpenOrders) {
        if (maxOrderQty <= 0) {
            throw new IllegalArgumentException("max order qty must be positive: " + maxOrderQty);
        }
        if (maxOpenOrders <= 0) {
            throw new IllegalArgumentException("max open orders must be positive: " + maxOpenOrders);
        }
        this.risk = risk;
        this.maxOrderQty = maxOrderQty;
        this.maxOpenOrders = maxOpenOrders;
        this.book = new OwnOrders(owner);
    }

    /**
     * Check one outgoing action. A pass is recorded as sent (a NEW starts resting, a CANCEL marks
     * its order pending), so the next check sees it.
     *
     * @return null if {@code a} may be sent, otherwise why not
     */
    public Refusal check(Action a) {
        if (a.kind() == Action.Kind.CANCEL) {
            book.cancelSent(a.origClOrdId());
            return null;
        }
        Refusal why = reason(a);
        if (why != null) {
            refused++;
            return why;
        }
        book.sent(a);
        return null;
    }

    private Refusal reason(Action a) {
        if (a.qty() > maxOrderQty) {
            return Refusal.ORDER_TOO_LARGE;
        }
        if (a.tif() == TimeInForce.GTC && book.workingCount() >= maxOpenOrders) {
            return Refusal.TOO_MANY_OPEN_ORDERS;
        }
        if (!risk.inBand(a.priceUnits())) {
            return Refusal.OUTSIDE_BAND;
        }
        long alsoWorking = (a.tif() == TimeInForce.GTC) ? book.workingQty(a.side()) : 0L;
        if (!risk.allows(a.side(), a.qty(), book.position(), alsoWorking)) {
            return Refusal.POSITION_LIMIT;
        }
        return null;
    }

    /** Feed every EXEC on the venue, as the strategy is fed. */
    public void onExec(ExecReport e) {
        book.onExec(e);
    }

    /** The local ORDER_REJECTED the strategy is handed for a refused NEW. Never sent anywhere. */
    public static ExecReport refusal(Action a) {
        return new ExecReport(ExecutionEventType.ORDER_REJECTED, a.clOrdId(), Prices.NA, Prices.NA,
                Prices.NA, Prices.NA, Prices.NA, Prices.NA, 0L, null);
    }

    /** Net position from the venue's fills. */
    public long position() {
        return book.position();
    }

    /** GTC orders working (no cancel pending). */
    public int openOrders() {
        return book.workingCount();
    }

    /** NEWs refused since start. */
    public long refused() {
        return refused;
    }

    public long maxOrderQty() {
        return maxOrderQty;
    }

    public int maxOpenOrders() {
        return maxOpenOrders;
    }

    public RiskLimits risk() {
        return risk;
    }
}
