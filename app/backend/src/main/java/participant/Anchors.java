package participant;

import model.Prices;

/**
 * Where a bot measures from. Every order price a bot sends is one of these on-tick anchors plus or
 * minus a whole number of ticks, so no strategy ever rounds a price.
 */
final class Anchors {

    private Anchors() { }

    /**
     * The reference price: the last trade when it lies on the tick, otherwise {@code previous} (the
     * caller's last good reference, the open before the first print). A midpoint peg can print
     * between ticks; such a print is never used as an anchor, because an order priced from it
     * would be off the tick. The book is never the fallback: a quoting bot would otherwise anchor
     * on its own quotes and walk itself away.
     */
    static long reference(MarketView view, long previous) {
        long last = view.lastTradeUnits();
        return Prices.isOnTick(last) ? last : previous;
    }

    /** {@code n} ticks in price units. */
    static long ticks(long n) {
        return n * Prices.TICK;
    }

    /** Uniform random long in {@code [lo, hi]}. */
    static long between(java.util.SplittableRandom rnd, long lo, long hi) {
        return lo + rnd.nextLong(hi - lo + 1);
    }
}
