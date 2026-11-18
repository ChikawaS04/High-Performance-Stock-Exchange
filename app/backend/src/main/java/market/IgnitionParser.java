package market;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import model.Prices;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure parsing of an Alpaca stock snapshot into the ignition price (P16-3, D9). No network, no state.
 *
 * Reads two fields of {@code dailyBar}: {@code o}, the day's official open in dollars, and {@code t},
 * the bar's start as an RFC 3339 instant. The open is converted exactly from the number's decimal text,
 * never through floating point: rounded half-up to the cent, as the P11 terminal rounded at this edge,
 * then scaled to long units of $0.0001, so the result is always on the one-cent tick. The bar's date is
 * {@code t} taken in New York, the zone of Alpaca's US-equity daily bars, as YYYYMMDD.
 *
 * Anything else (malformed JSON, a missing field, a non-number or exponent open, an open at or below
 * zero or with more than {@value #MAX_INTEGER_DIGITS} integer digits, an unparseable {@code t}) returns
 * null. The caller treats null as a failed fetch.
 */
final class IgnitionParser {

    /** The zone of Alpaca's daily bar dates and of the cache's notion of "today". */
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /** Integer digits of the dollar open, at most: up to $999,999,999.99, far inside a long of units. */
    static final int MAX_INTEGER_DIGITS = 9;

    private static final JsonFactory JSON = new JsonFactory();

    /** The open in units of $0.0001 and the bar's New York date. */
    record Bar(long openPx, int date) { }

    private IgnitionParser() { }

    /** The daily bar of a snapshot body, or null when the body has no usable one. */
    static Bar parse(byte[] body) {
        String openText = null;
        String time = null;
        try (JsonParser p = JSON.createParser(body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return null;
            JsonToken token;
            while ((token = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                token = p.nextToken();
                if ("dailyBar".equals(name) && token == JsonToken.START_OBJECT) {
                    JsonToken inner;
                    while ((inner = p.nextToken()) == JsonToken.FIELD_NAME) {
                        String field = p.currentName();
                        JsonToken value = p.nextToken();
                        if ("o".equals(field)) {
                            openText = (value == JsonToken.VALUE_NUMBER_INT || value == JsonToken.VALUE_NUMBER_FLOAT)
                                    ? p.getText() : null;
                        } else if ("t".equals(field)) {
                            time = value == JsonToken.VALUE_STRING ? p.getText() : null;
                        }
                        p.skipChildren();
                    }
                    if (inner != JsonToken.END_OBJECT) return null;
                } else {
                    p.skipChildren();
                }
            }
            if (token != JsonToken.END_OBJECT) return null;
        } catch (IOException e) {
            return null;   // malformed JSON
        }
        if (openText == null || time == null) return null;
        long openPx = dollarsToUnits(openText);
        int date = newYorkDate(time);
        if (openPx <= 0 || date < 0) return null;
        return new Bar(openPx, date);
    }

    /**
     * A plain decimal dollar amount ({@code 149.8}, {@code 150}, {@code 149.875}) to units of $0.0001,
     * rounded half-up to the cent. Returns -1 for anything else, including exponent notation, a sign,
     * too many integer digits and a value that rounds to zero.
     */
    static long dollarsToUnits(String text) {
        int dot = text.indexOf('.');
        String whole = dot < 0 ? text : text.substring(0, dot);
        String fraction = dot < 0 ? "" : text.substring(dot + 1);
        if (whole.isEmpty() || whole.length() > MAX_INTEGER_DIGITS || (dot >= 0 && fraction.isEmpty())) return -1;
        if (!digits(whole) || !digits(fraction)) return -1;
        long cents = Long.parseLong(whole) * 100L;
        if (fraction.length() >= 1) cents += (fraction.charAt(0) - '0') * 10L;
        if (fraction.length() >= 2) cents += fraction.charAt(1) - '0';
        if (fraction.length() >= 3 && fraction.charAt(2) >= '5') cents += 1L;   // half-up at the cent
        if (cents <= 0) return -1;
        return cents * (Prices.SCALE / 100L);
    }

    /** An RFC 3339 instant's New York date as YYYYMMDD, or -1. */
    static int newYorkDate(String instant) {
        try {
            ZonedDateTime ny = Instant.parse(instant).atZone(NEW_YORK);
            return yyyymmdd(ny);
        } catch (DateTimeException e) {
            return -1;
        }
    }

    static int yyyymmdd(ZonedDateTime t) {
        return t.getYear() * 10_000 + t.getMonthValue() * 100 + t.getDayOfMonth();
    }

    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
