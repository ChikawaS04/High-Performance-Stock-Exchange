package engine;

import event.OrderEvent;
import event.OrderEventType;
import model.Prices;

import java.nio.charset.StandardCharsets;

/**
 * Test helpers for the session lifecycle (P15-8). Handler-level tests that are about matching, not
 * sessions, start with a session already open through {@link #open(MatchingEngineHandler)}, which
 * uses the production snapshot-restore path: no SESSION_OPENED event is published, so their seqs are
 * unchanged. Session commands for tests that drive the lifecycle are built by {@link #openCommand}
 * and {@link #closeCommand}, writing every field as the gateway does.
 */
public final class Sessions {

    /** The business date the test sessions open on. */
    public static final int BUSINESS_DATE = 20261102;

    /** The symbol the test sessions carry. */
    public static final String SYMBOL = "ASML";

    private Sessions() {
    }

    /** {@link #SYMBOL} as the journal carries it: 8 ASCII bytes, NUL-padded. */
    public static byte[] symbol() {
        return symbol(SYMBOL);
    }

    public static byte[] symbol(String text) {
        byte[] bytes = new byte[OrderEvent.SYMBOL_LENGTH];
        byte[] ascii = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, bytes, 0, ascii.length);
        return bytes;
    }

    /** Puts {@code handler} in an open session on {@link #BUSINESS_DATE}, publishing nothing. */
    public static void open(MatchingEngineHandler handler) {
        handler.restoreSession(true, BUSINESS_DATE, -1, symbol());
    }

    /** A valid SESSION_OPEN for {@code businessDate} with the engine's reference data. */
    public static OrderEvent openCommand(int businessDate, long timestamp) {
        return openCommand(businessDate, symbol(), Prices.TICK, SessionRules.LOT_SIZE, Prices.SCALE, timestamp);
    }

    /** A SESSION_OPEN with every field given. */
    public static OrderEvent openCommand(int businessDate, byte[] symbol, long tickSize, long lotSize,
                                         long priceScale, long timestamp) {
        OrderEvent e = sessionCommand(OrderEventType.SESSION_OPEN, timestamp);
        e.businessDate = businessDate;
        System.arraycopy(symbol, 0, e.symbol, 0, OrderEvent.SYMBOL_LENGTH);
        e.tickSize = tickSize;
        e.lotSize = lotSize;
        e.priceScale = priceScale;
        return e;
    }

    /** A SESSION_CLOSE for {@code businessDate}. */
    public static OrderEvent closeCommand(int businessDate, long timestamp) {
        OrderEvent e = sessionCommand(OrderEventType.SESSION_CLOSE, timestamp);
        e.businessDate = businessDate;
        return e;
    }

    private static OrderEvent sessionCommand(OrderEventType type, long timestamp) {
        OrderEvent e = new OrderEvent();
        e.eventType = type;
        e.orderId = -1L;
        e.price = -1L;
        e.quantity = -1L;
        e.maxFloor = 0L;
        e.originalOrderId = -1L;
        e.timestamp = timestamp;
        e.clearSessionFields();
        return e;
    }
}
