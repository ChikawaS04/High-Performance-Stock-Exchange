package clearing;

import java.time.LocalDate;
import java.util.Set;

/**
 * The next business date after a closed one (P15-11). The trading calendar records exceptions only: a
 * date with no row is a trading day (V1), so the next business date is the first later date that the
 * calendar does not mark as a non-trading day. Off the hot path, so {@link LocalDate} is fine here.
 */
public final class BusinessCalendar {

    /** More consecutive non-trading days than this is a broken calendar, not a holiday. */
    public static final int MAX_NON_TRADING_RUN = 3_660;

    /** The schema's last business date (V1 to V4 check every date against it). */
    public static final int LAST_DATE = 99_991_231;

    private BusinessCalendar() {
    }

    /**
     * @param businessDate   a YYYYMMDD date
     * @param nonTradingDays the dates after {@code businessDate} the calendar marks as non-trading
     * @return the first date after {@code businessDate} not in {@code nonTradingDays}, as YYYYMMDD
     * @throws ClearingInvariantException past {@link #LAST_DATE} or {@link #MAX_NON_TRADING_RUN} skipped days
     */
    public static int next(int businessDate, Set<Integer> nonTradingDays) {
        LocalDate date = toLocalDate(businessDate);
        for (int skipped = 0; skipped <= MAX_NON_TRADING_RUN; skipped++) {
            date = date.plusDays(1);
            int candidate = toInt(date);
            if (candidate > LAST_DATE) {
                throw new ClearingInvariantException("No business date follows " + businessDate
                        + " before the schema's last date " + LAST_DATE);
            }
            if (!nonTradingDays.contains(candidate)) {
                return candidate;
            }
        }
        throw new ClearingInvariantException("The trading calendar marks more than " + MAX_NON_TRADING_RUN
                + " consecutive days after " + businessDate + " as non-trading days");
    }

    static LocalDate toLocalDate(int yyyymmdd) {
        try {
            return LocalDate.of(yyyymmdd / 10_000, (yyyymmdd / 100) % 100, yyyymmdd % 100);
        } catch (java.time.DateTimeException e) {
            throw new ClearingInvariantException("Business date " + yyyymmdd + " is not a valid YYYYMMDD date", e);
        }
    }

    static int toInt(LocalDate date) {
        return date.getYear() * 10_000 + date.getMonthValue() * 100 + date.getDayOfMonth();
    }
}
