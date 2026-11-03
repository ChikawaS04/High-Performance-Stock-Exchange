package engine;

import model.Prices;

/**
 * The rules a session command must satisfy (P15-8, SRS §3.3, §3.12). The engine applies them to
 * every SESSION_OPEN and SESSION_CLOSE it processes, live or replayed; the session controller
 * applies the same rules before it issues a command, so a configuration the engine would refuse
 * never reaches the journal.
 *
 * Reference data. The engine honours exactly one set of reference values: the tick and price
 * scale wired into {@link Prices} (and through them into the FIX parser and {@code Order}), and a
 * lot size of 1 (the venue has no lot check). A SESSION_OPEN asking for anything else is refused
 * rather than recorded and ignored, so every open in the journal describes how the orders after
 * it were actually matched. The symbol is 1 to 8 characters of {@code [A-Z0-9.]}, NUL-padded.
 *
 * Business dates are YYYYMMDD integers, checked by arithmetic: no {@code java.time}, no
 * allocation, so the check is cheap and deterministic on the engine thread.
 *
 * Every check returns a reason code, {@link #OK} when it passes; {@link #describe} turns a code
 * into text off the engine thread.
 */
public final class SessionRules {

    public static final int OK                   = 0;
    public static final int ALREADY_OPEN         = 1;
    public static final int NOT_OPEN             = 2;
    public static final int INVALID_DATE         = 3;
    public static final int DATE_NOT_AFTER_CLOSE = 4;
    public static final int WRONG_DATE           = 5;
    public static final int BAD_SYMBOL           = 6;
    public static final int BAD_TICK_SIZE        = 7;
    public static final int BAD_LOT_SIZE         = 8;
    public static final int BAD_PRICE_SCALE      = 9;

    /** The only lot size the engine honours: it performs no lot check. */
    public static final long LOT_SIZE = 1L;

    /** Earliest and latest year a business date may name. */
    private static final int MIN_YEAR = 1970;
    private static final int MAX_YEAR = 9999;

    private SessionRules() {
    }

    /**
     * Whether a SESSION_OPEN may be applied.
     *
     * @param open           whether a session is open now
     * @param lastClosedDate the last closed business date, or -1 if none was ever closed
     * @param symbol         the 8-byte NUL-padded symbol
     */
    public static int checkOpen(boolean open, int lastClosedDate, int businessDate, byte[] symbol,
                                long tickSize, long lotSize, long priceScale) {
        if (open) return ALREADY_OPEN;
        int reference = checkReference(symbol, tickSize, lotSize, priceScale);
        if (reference != OK) return reference;
        if (!isValidDate(businessDate)) return INVALID_DATE;
        if (businessDate <= lastClosedDate) return DATE_NOT_AFTER_CLOSE;
        return OK;
    }

    /** Whether a SESSION_CLOSE for {@code businessDate} may be applied to a session open on {@code openDate}. */
    public static int checkClose(boolean open, int openDate, int businessDate) {
        if (!open) return NOT_OPEN;
        if (businessDate != openDate) return WRONG_DATE;
        return OK;
    }

    /** The reference-data part of {@link #checkOpen}. */
    public static int checkReference(byte[] symbol, long tickSize, long lotSize, long priceScale) {
        if (!isValidSymbol(symbol)) return BAD_SYMBOL;
        if (tickSize != Prices.TICK) return BAD_TICK_SIZE;
        if (lotSize != LOT_SIZE) return BAD_LOT_SIZE;
        if (priceScale != Prices.SCALE) return BAD_PRICE_SCALE;
        return OK;
    }

    /** 1 to 8 characters of {@code [A-Z0-9.]} from the start, then only NUL. */
    public static boolean isValidSymbol(byte[] symbol) {
        if (symbol == null || symbol.length != 8) return false;
        int length = 0;
        while (length < symbol.length && symbol[length] != 0) {
            byte b = symbol[length];
            boolean allowed = (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '.';
            if (!allowed) return false;
            length++;
        }
        for (int i = length; i < symbol.length; i++) {
            if (symbol[i] != 0) return false;
        }
        return length > 0;
    }

    /** A real calendar date written as YYYYMMDD, years 1970 to 9999. */
    public static boolean isValidDate(int yyyymmdd) {
        int year = yyyymmdd / 10_000;
        int month = (yyyymmdd / 100) % 100;
        int day = yyyymmdd % 100;
        if (year < MIN_YEAR || year > MAX_YEAR) return false;
        if (month < 1 || month > 12) return false;
        return day >= 1 && day <= daysInMonth(year, month);
    }

    private static int daysInMonth(int year, int month) {
        switch (month) {
            case 2:
                boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
                return leap ? 29 : 28;
            case 4: case 6: case 9: case 11:
                return 30;
            default:
                return 31;
        }
    }

    /** Text for a reason code. Allocates; never call on the engine thread. */
    public static String describe(int code) {
        switch (code) {
            case OK:                   return "accepted";
            case ALREADY_OPEN:         return "a session is already open";
            case NOT_OPEN:             return "no session is open";
            case INVALID_DATE:         return "the business date is not a valid YYYYMMDD date";
            case DATE_NOT_AFTER_CLOSE: return "the business date is not after the last closed business date";
            case WRONG_DATE:           return "the business date is not the open session's date";
            case BAD_SYMBOL:           return "the symbol must be 1 to 8 characters of A-Z, 0-9 and '.'";
            case BAD_TICK_SIZE:        return "the tick size must be " + Prices.TICK + " (the engine's tick)";
            case BAD_LOT_SIZE:         return "the lot size must be " + LOT_SIZE + " (the engine has no lot check)";
            case BAD_PRICE_SCALE:      return "the price scale must be " + Prices.SCALE + " (the engine's scale)";
            default:                   return "unknown reason " + code;
        }
    }
}
