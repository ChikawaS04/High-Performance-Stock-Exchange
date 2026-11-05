package session;

import config.ExchangeConfig;
import event.OrderEvent;

import java.nio.charset.StandardCharsets;

/**
 * The reference data a SESSION_OPEN carries (SRS §3.12): symbol, tick size, lot size and price
 * scale. Built by a {@link ReferenceSource}, from the database or from configuration; either way
 * the values travel inside the journaled command, so the engine never reads them from anywhere
 * else and replay needs neither.
 *
 * @param symbol     1 to 8 characters of {@code [A-Z0-9.]}
 * @param tickSize   units of $0.0001
 * @param lotSize    shares per lot
 * @param priceScale units per dollar
 */
public record SessionReference(String symbol, long tickSize, long lotSize, long priceScale) {

    public SessionReference {
        if (symbol == null || !symbol.matches("[A-Z0-9.]{1,8}")) {
            throw new IllegalArgumentException("symbol must be 1 to 8 characters of A-Z, 0-9 and '.': " + symbol);
        }
    }

    /** From the {@code SESSION_*} variables. */
    public static SessionReference fromConfig(ExchangeConfig config) {
        return new SessionReference(config.sessionSymbol(), config.sessionTickSize(), config.sessionLotSize(),
                config.sessionPriceScale());
    }

    /** The symbol as the journal carries it: {@link OrderEvent#SYMBOL_LENGTH} ASCII bytes, NUL-padded. */
    public byte[] symbolBytes() {
        byte[] bytes = new byte[OrderEvent.SYMBOL_LENGTH];
        byte[] ascii = symbol.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, bytes, 0, ascii.length);
        return bytes;
    }
}
