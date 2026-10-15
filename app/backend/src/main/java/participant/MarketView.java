package participant;

import model.Prices;

/**
 * Immutable snapshot of the venue the strategies read on each tick. Built by
 * {@link ParticipantClient} from the BOOK and EXEC streams and handed to a
 * {@link Strategy} by value, so a strategy never races the socket thread.
 *
 * <p>All prices are long units of $0.0001 (SRS §4). A side that is empty, or a
 * value that has not been observed yet, carries {@link Prices#NA} (-1).
 */
public record MarketView(
        long bestBid,
        long bestAsk,
        long midUnits,
        long lastTradeUnits) {

    /** True when both sides are populated, so {@link #midUnits} is a real midpoint. */
    public boolean twoSided() {
        return bestBid > 0 && bestAsk > 0;
    }

    /** True once at least one trade has printed, so {@link #lastTradeUnits} is real. */
    public boolean hasTraded() {
        return lastTradeUnits > 0;
    }

    /** Empty venue: no book, no prints. */
    public static MarketView empty() {
        return new MarketView(Prices.NA, Prices.NA, Prices.NA, Prices.NA);
    }
}
