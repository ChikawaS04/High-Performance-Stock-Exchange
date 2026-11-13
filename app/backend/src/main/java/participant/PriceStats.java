package participant;

import model.Prices;

/**
 * Trade-driven price signals for one bot: a fast and a slow exponential moving average of trade
 * prices, the last trade, and a volatility estimate (an EMA of the absolute tick move between
 * consecutive trades). Every fill on the venue feeds it, from any participant, so each bot reads
 * the same market.
 *
 * <p>The averages are seeded at the open, so before the first print every signal sits on the open
 * and a crossover cannot fire. Half-lives are counted in trades, not time: a quiet market moves the
 * signals slowly and a busy one quickly, which suits a venue whose pace depends on its bots.
 *
 * <p>The EMAs are doubles because they are signals, never prices: no strategy sends an order at
 * an average. Order prices always come from an on-tick anchor plus a whole number of ticks.
 */
public final class PriceStats {

    private final double fastAlpha;
    private final double slowAlpha;
    private final double volAlpha;

    private double fast;
    private double slow;
    private double volTicks;
    private long lastTrade = Prices.NA;
    private long trades;

    /** Half-lives in trades; the volatility average uses {@code volHalfLife}. */
    public PriceStats(long openUnits, double fastHalfLife, double slowHalfLife, double volHalfLife) {
        if (openUnits <= 0) {
            throw new IllegalArgumentException("open must be positive: " + openUnits);
        }
        this.fastAlpha = alpha(fastHalfLife);
        this.slowAlpha = alpha(slowHalfLife);
        this.volAlpha = alpha(volHalfLife);
        this.fast = openUnits;
        this.slow = openUnits;
    }

    /** Weight of a new sample for an EMA whose weight halves every {@code halfLife} samples. */
    static double alpha(double halfLife) {
        if (!(halfLife > 0)) {
            throw new IllegalArgumentException("half-life must be positive: " + halfLife);
        }
        return 1.0 - Math.pow(0.5, 1.0 / halfLife);
    }

    public void onTrade(long priceUnits) {
        if (priceUnits <= 0) {
            return;
        }
        if (lastTrade > 0) {
            double move = Math.abs(priceUnits - lastTrade) / (double) Prices.TICK;
            volTicks += volAlpha * (move - volTicks);
        }
        fast += fastAlpha * (priceUnits - fast);
        slow += slowAlpha * (priceUnits - slow);
        lastTrade = priceUnits;
        trades++;
    }

    /** Feed it from an EXEC: only fills carry a trade price. */
    public void onExec(ExecReport e) {
        if (ParticipantClient.isFill(e.execType())) {
            onTrade(e.priceUnits());
        }
    }

    public double fast() {
        return fast;
    }

    public double slow() {
        return slow;
    }

    /** Typical tick move between consecutive trades; 0 before two prints. */
    public double volTicks() {
        return volTicks;
    }

    public long lastTrade() {
        return lastTrade;
    }

    public long trades() {
        return trades;
    }
}
