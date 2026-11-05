package session;

/**
 * What a {@link ReferenceSource} says about one business date.
 *
 * @param reference  the reference data a SESSION_OPEN for this date would carry
 * @param tradingDay whether the trading calendar lets a session open on this date
 */
public record SessionDay(SessionReference reference, boolean tradingDay) {
}
