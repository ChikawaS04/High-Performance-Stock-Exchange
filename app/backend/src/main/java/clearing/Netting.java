package clearing;

import model.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Nets one business date's fills per participant and symbol (SRS 3.12 step 2). Pure: no JDBC.
 *
 * <p>Each fill is one trade (the engine reports a trade once, naming the aggressor). The aggressor buys
 * if {@code aggressorSide} is BUY and sells otherwise; the passive participant takes the other side. Both
 * move {@code quantity} at {@code price}, so a trade adds the same quantity and value to one buyer and
 * one seller and the day sums to zero. A participant on both sides of a trade (today every fill is the
 * house trading with itself) buys and sells the same, netting to zero quantity and zero cash.
 *
 * <p>Arithmetic is exact: an overflow is a {@link ClearingInvariantException}, never a wrap. Prices stay
 * in units of $0.0001, so a sub-penny midpoint price is exact.
 */
public final class Netting {

    private record Key(long participantId, String symbol) {
    }

    private static final class Totals {
        long bought;
        long sold;
        long boughtValue;
        long soldValue;
    }

    private static final Comparator<Key> ORDER =
            Comparator.comparingLong(Key::participantId).thenComparing(Key::symbol);

    private final Map<Key, Totals> totals = new TreeMap<>(ORDER);
    private long fills;
    private long volume;

    /** Adds one trade. {@code seq} names it in a failure. */
    public void add(long seq, String symbol, long price, long quantity, Side aggressorSide, long aggressorParticipantId,
                    long passiveParticipantId) {
        if (price <= 0L || quantity <= 0L) {
            throw new ClearingInvariantException("Fill at outbound seq " + seq + " has price " + price + " and quantity "
                    + quantity + ": both must be positive");
        }
        long value = exact(seq, () -> Math.multiplyExact(price, quantity));
        long buyer = aggressorSide == Side.BUY ? aggressorParticipantId : passiveParticipantId;
        long seller = aggressorSide == Side.BUY ? passiveParticipantId : aggressorParticipantId;
        Totals b = totals.computeIfAbsent(new Key(buyer, symbol), k -> new Totals());
        Totals s = totals.computeIfAbsent(new Key(seller, symbol), k -> new Totals());
        b.bought = exact(seq, () -> Math.addExact(b.bought, quantity));
        b.boughtValue = exact(seq, () -> Math.addExact(b.boughtValue, value));
        s.sold = exact(seq, () -> Math.addExact(s.sold, quantity));
        s.soldValue = exact(seq, () -> Math.addExact(s.soldValue, value));
        fills++;
        volume = exact(seq, () -> Math.addExact(volume, quantity));
    }

    public long fills() {
        return fills;
    }

    public long volume() {
        return volume;
    }

    /**
     * The obligations, ordered by participant then symbol, after checking that net quantity and cash each
     * sum to zero per symbol.
     */
    public List<Obligation> obligations() {
        List<Obligation> out = new ArrayList<>(totals.size());
        Map<String, long[]> sums = new TreeMap<>();
        for (Map.Entry<Key, Totals> e : totals.entrySet()) {
            Key k = e.getKey();
            Totals t = e.getValue();
            long net = exact(-1L, () -> Math.subtractExact(t.bought, t.sold));
            long cash = exact(-1L, () -> Math.subtractExact(t.boughtValue, t.soldValue));
            out.add(new Obligation(k.participantId(), k.symbol(), t.bought, t.sold, net, cash));
            long[] sum = sums.computeIfAbsent(k.symbol(), s -> new long[2]);
            sum[0] = exact(-1L, () -> Math.addExact(sum[0], net));
            sum[1] = exact(-1L, () -> Math.addExact(sum[1], cash));
        }
        for (Map.Entry<String, long[]> e : sums.entrySet()) {
            if (e.getValue()[0] != 0L || e.getValue()[1] != 0L) {
                throw new ClearingInvariantException("Obligations in " + e.getKey() + " sum to net quantity "
                        + e.getValue()[0] + " and cash " + e.getValue()[1] + ", not zero");
            }
        }
        return out;
    }

    private interface LongOp {
        long apply();
    }

    private static long exact(long seq, LongOp op) {
        try {
            return op.apply();
        } catch (ArithmeticException e) {
            throw new ClearingInvariantException((seq < 0 ? "Netting" : "Netting the fill at outbound seq " + seq)
                    + " overflows a long: amounts are never wrapped", e);
        }
    }
}
