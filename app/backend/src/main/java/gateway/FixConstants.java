package gateway;

public final class FixConstants {

    private FixConstants() {
        //no instances
    }

    // --- Wire-level delimiters ---
    public static final byte SOH = 0x01;  // field terminator
    public static final byte EQUALS = '=';  // tag/value separator

    // --- Envelope tags (header + trailer) ---
    public static final int BEGIN_STRING = 8;
    public static final int BODY_LENGTH = 9;
    public static final int MESSAGE_TYPE = 35;
    public static final int CHECKSUM = 10;

    // --- Message body tags (in scope) ---
    public static final int CL_ORD_ID = 11;
    public static final int SIDE = 54;
    public static final int PRICE = 44;
    public static final int ORDER_QTY = 38;
    public static final int SYMBOL = 55;
    public static final int ORIG_CL_ORD_ID = 41;  // cancels only
    public static final int TIME_IN_FORCE = 59;   // new orders; optional, GTC default (Phase 14)
    public static final int MAX_FLOOR = 111;      // iceberg display size (MaxFloor); optional (Phase 14)

    // --- Message-type values (the byte after 35=) ---
    public static final byte MSG_TYPE_NEW_ORDER = 'D';  // NewOrderSingle
    public static final byte MSG_TYPE_CANCEL    = 'F';  // OrderCancelRequest

    // --- Configured instrument ---
    // Single-symbol book. Stored as byte[] so tag 55 can be compared against a
    // buf[start..end) range without allocating a String on the parse path.
    // NOTE: a public static final byte[] is not truly immutable — the contents
    // can be mutated. Acceptable for this demo; the parse path only reads it.
    public static final byte[] SYMBOL_BYTES = {'A', 'S', 'M', 'L'};

    // Human-readable form for SLF4J reject logging at the gateway boundary.
    // Boundary-only, off the hot path.
    public static final String SYMBOL_DISPLAY = "ASML";

    // Max fields per message. A 35=D with session tags is ~15;
    // 32 is comfortable headroom and bounds the scanner's arrays.
    public static final int MAX_FIELDS = 32;

    // Max BodyLength (tag 9) we'll accept before treating the length prefix as a
    // lie. Order messages are ~150 bytes; this is generous. Bounds the frame-end
    // computation and turns an absurd/garbage 9= into a defined reject.
    public static final int MAX_FRAME = 4096;

    /**
     * Parses the ASCII digits in {@code buf[start, end)} into a non-negative long.
     * Returns -1L on any reject: empty range, non-digit byte, or overflow past
     * Long.MAX_VALUE. All valid results are >= 0, so -1 is an unambiguous sentinel.
     */
    static long parseLong(byte[] buf, int start, int end) {
        if (start >= end) { return -1L; }
        long val = 0L;
        for (int i = start; i < end; i++) {
            int digit = buf[i] - '0';
            if (digit < 0 || digit > 9) { return -1L; }
            if (val > (Long.MAX_VALUE - digit) / 10) { return -1L; }
            val = val * 10 + digit;
        }
        return val;
    }
}
