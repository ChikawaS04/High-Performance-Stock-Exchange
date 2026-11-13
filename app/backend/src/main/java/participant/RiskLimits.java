package participant;

import model.Prices;
import model.Side;

/**
 * Per-bot risk limits: a price band around the open and a maximum absolute position.
 *
 * <p>The band is held in whole ticks ({@code open * bandPercent / 100}, divided down to ticks), so
 * both edges are the open plus or minus a whole number of ticks and lie on the tick themselves;
 * nothing here rounds a price. A bot never sends an order priced outside the band. A bot at its
 * position limit only sends orders that bring the position back toward zero.
 */
public record RiskLimits(long openUnits, int bandPercent, long maxPosition) {

    public static final int DEFAULT_BAND_PERCENT = 20;

    public RiskLimits {
        if (!Prices.isOnTick(openUnits)) {
            throw new IllegalArgumentException("open must be a positive price on the tick: " + openUnits);
        }
        if (bandPercent <= 0 || bandPercent >= 100) {
            throw new IllegalArgumentException("band percent must be in (0, 100): " + bandPercent);
        }
        if (maxPosition <= 0) {
            throw new IllegalArgumentException("max position must be positive: " + maxPosition);
        }
    }

    public long bandTicks() {
        return openUnits * bandPercent / 100 / Prices.TICK;
    }

    public long lowest() {
        return openUnits - bandTicks() * Prices.TICK;
    }

    public long highest() {
        return openUnits + bandTicks() * Prices.TICK;
    }

    /** True when {@code price} is on the tick and inside the band, edges included. */
    public boolean inBand(long price) {
        return Prices.isOnTick(price) && price >= lowest() && price <= highest();
    }

    /**
     * True when buying or selling {@code qty} keeps the position within the limit, or moves it
     * toward zero. Counts {@code alsoWorking} (quantity already working on the same side) as if
     * it would fill, so resting orders cannot together overshoot the limit.
     */
    public boolean allows(Side side, long qty, long position, long alsoWorking) {
        long signed = (side == Side.BUY) ? qty + alsoWorking : -(qty + alsoWorking);
        long after = position + signed;
        if (Math.abs(after) <= maxPosition) {
            return true;
        }
        return Math.abs(after) < Math.abs(position);
    }
}
