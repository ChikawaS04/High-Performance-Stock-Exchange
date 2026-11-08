package http;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict parsing of one route's query parameters. Every failure is a 400 with the fixed body: an
 * unknown or repeated parameter, a missing required one, or a value out of its range. Nothing is
 * trimmed, defaulted from a bad value, or rounded.
 *
 * <ul>
 *   <li>A business date is exactly 8 digits naming a real calendar date from 19700101 to 99991231.</li>
 *   <li>A seq, id or count is 1 to 19 digits with no sign, within the stated bounds.</li>
 *   <li>A symbol matches the instrument table's own check, {@code ^[A-Z0-9.]{1,8}$}.</li>
 * </ul>
 */
final class ApiParams {

    static final int MIN_DATE = 19700101;
    static final int MAX_DATE = 99991231;

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;
    /** The longest range a volume summary covers, both ends included. */
    static final int MAX_RANGE_DAYS = 366;
    static final Set<Integer> INTERVALS_MINUTES = Set.of(1, 5, 15, 30, 60);

    private static final Pattern DATE = Pattern.compile("[0-9]{8}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,19}");
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9.]{1,8}");

    private final Map<String, List<String>> values;

    /** @throws ApiException 400 if a parameter is not in {@code allowed} or appears more than once */
    ApiParams(Map<String, List<String>> values, Set<String> allowed) {
        for (Map.Entry<String, List<String>> e : values.entrySet()) {
            if (!allowed.contains(e.getKey()) || e.getValue().size() != 1) {
                throw ApiException.badRequest();
            }
        }
        this.values = values;
    }

    private String raw(String name) {
        List<String> v = values.get(name);
        return v == null ? null : v.get(0);
    }

    /** A required business date. */
    int date(String name) {
        String s = raw(name);
        if (s == null) throw ApiException.badRequest();
        return parseDate(s);
    }

    static int parseDate(String s) {
        if (!DATE.matcher(s).matches()) throw ApiException.badRequest();
        int date = Integer.parseInt(s);
        if (date < MIN_DATE || date > MAX_DATE) throw ApiException.badRequest();
        try {
            LocalDate.of(date / 10_000, date / 100 % 100, date % 100);
        } catch (DateTimeException e) {
            throw ApiException.badRequest();
        }
        return date;
    }

    /** A long in {@code [min, max]}, or {@code absent} when the parameter is not given. */
    long longValue(String name, long absent, long min, long max) {
        String s = raw(name);
        if (s == null) return absent;
        if (!DIGITS.matcher(s).matches()) throw ApiException.badRequest();
        long v;
        try {
            v = Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest();   // 19 digits above Long.MAX_VALUE
        }
        if (v < min || v > max) throw ApiException.badRequest();
        return v;
    }

    /** A required long in {@code [min, max]}. */
    long requiredLong(String name, long min, long max) {
        if (raw(name) == null) throw ApiException.badRequest();
        return longValue(name, 0L, min, max);
    }

    /** {@code afterSeq}: 0 (from the start) when absent. */
    long afterSeq() {
        return longValue("afterSeq", 0L, 0L, Long.MAX_VALUE);
    }

    /** {@code limit}: {@value #DEFAULT_LIMIT} when absent, at most {@value #MAX_LIMIT}. */
    int limit() {
        return (int) longValue("limit", DEFAULT_LIMIT, 1L, MAX_LIMIT);
    }

    /** A symbol, or null when it is optional and absent. */
    String symbol(String name, boolean required) {
        String s = raw(name);
        if (s == null) {
            if (required) throw ApiException.badRequest();
            return null;
        }
        if (!SYMBOL.matcher(s).matches()) throw ApiException.badRequest();
        return s;
    }

    /** {@code interval} in minutes: 1 when absent, otherwise one of {@link #INTERVALS_MINUTES}. */
    int intervalMinutes() {
        int minutes = (int) longValue("interval", 1L, 1L, 60L);
        if (!INTERVALS_MINUTES.contains(minutes)) throw ApiException.badRequest();
        return minutes;
    }

    /** @throws ApiException 400 if {@code from} is after {@code to} or the range holds more than {@value #MAX_RANGE_DAYS} dates */
    static void checkRange(int from, int to) {
        if (from > to) throw ApiException.badRequest();
        long days = ChronoUnit.DAYS.between(toLocalDate(from), toLocalDate(to)) + 1;
        if (days > MAX_RANGE_DAYS) throw ApiException.badRequest();
    }

    private static LocalDate toLocalDate(int date) {
        return LocalDate.of(date / 10_000, date / 100 % 100, date % 100);
    }
}
