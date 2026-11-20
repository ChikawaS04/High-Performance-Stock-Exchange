package clearing;

import java.util.ArrayList;
import java.util.List;

/**
 * Which business dates database retention may purge (P16-8, D14). Pure.
 *
 * <p>The window is counted in business dates that have a COMPLETE clearing run, never in calendar days:
 * the newest {@code days} COMPLETE dates are kept, and a COMPLETE date is eligible once at least
 * {@code days} COMPLETE dates are newer than it. After k COMPLETE runs d1 &lt; ... &lt; dk the eligible
 * dates are d1 .. d(k - days) when k &gt; days, none otherwise. Dates with no session, PENDING dates and
 * the open day never count. With {@code days} = 2, the first date goes at the third COMPLETE run, as the
 * journal's first day goes at its third close (P16-7).
 */
public final class RetentionWindow {

    private RetentionWindow() {
    }

    /**
     * @param completeDatesAscending every business date with a COMPLETE run, ascending, without repeats
     * @param days                   {@code DB_RETENTION_DAYS}, 0 or more
     * @return the eligible dates, ascending
     */
    public static List<Integer> eligible(List<Integer> completeDatesAscending, int days) {
        if (days < 0) throw new IllegalArgumentException("days must be 0 or more: " + days);
        int previous = Integer.MIN_VALUE;
        for (int date : completeDatesAscending) {
            if (date <= previous) {
                throw new IllegalArgumentException("COMPLETE dates must be ascending without repeats: " + completeDatesAscending);
            }
            previous = date;
        }
        int count = completeDatesAscending.size() - days;
        return count <= 0 ? List.of() : new ArrayList<>(completeDatesAscending.subList(0, count));
    }
}
