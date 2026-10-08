package gateway;

import event.OrderEvent;
import event.OrderEventType;
import model.Prices;
import model.Side;

/**
 * Hand-rolled FIX tag-value parser. Steps 1–5 assemble here.
 * Pure byte[]-in: no io.netty, no com.lmax imports, ever.
 */
final class FixParser {

    // Scanner output: parallel arrays, index-aligned, valid up to the
    // count returned by scan(). No per-message allocation; no reset
    // needed between messages since count bounds every read.
    final int[] tags       = new int[FixConstants.MAX_FIELDS];
    final int[] valStarts  = new int[FixConstants.MAX_FIELDS];
    final int[] valEnds    = new int[FixConstants.MAX_FIELDS];

    // ---- Step 1: numeric primitives ----

    /**
     * Parses the ASCII digits in {@code buf[start, end)} into a non-negative long.
     * Returns -1L on any reject: empty range, non-digit byte, or overflow.
     */
    static long parseLong(byte[] buf, int start, int end) {
        if (start >= end) {
            return -1L;
        }
        long val = 0L;
        for (int i = start; i < end; i++) {
            int digit = buf[i] - '0';
            if (digit < 0 || digit > 9) {
                return -1L;
            }
            if (val > (Long.MAX_VALUE - digit) / 10) {
                return -1L;
            }
            val = val * 10 + digit;
        }
        return val;
    }

    // Local to parsePrice — the only caller that knows about price scaling.
    private static final int MAX_DECIMALS = 2;
    private static final byte DOT = '.';

    /**
     * Parses a FIX price field (bytes [start, end)) to long units of $0.0001 (SRS §4).
     * "150" -> 1_500_000, "150.2" -> 1_502_000, "150.25" -> 1_502_500.
     *
     * At most two decimal places are accepted (tag 44 is on the one-cent tick), so every
     * parsed price is a multiple of 100 units.
     *
     * Returns -1L on any reject: empty, non-digit, empty integer part,
     * more than two decimals, or a non-positive result. Zero/negative
     * price policy is applied here (the > 0 check) because parseLong
     * deliberately treats "0" as a valid parse.
     *
     * @return long units of $0.0001, or -1L on reject
     */
    static long parsePrice(byte[] buf, int start, int end) {
        if (start >= end) { return -1L; }

        // Locate the first '.'. Absence means integer-only price.
        int dot = -1;
        for (int i = start; i < end; i++) {
            if (buf[i] == DOT) {
                dot = i;
                break;
            }
        }

        long intPart;
        long fracScaled;

        if (dot == -1) {
            // No fraction: whole range is the integer part.
            intPart = parseLong(buf, start, end);
            if (intPart == -1L) { return -1L; }  // non-digit somewhere
            fracScaled = 0L;
        } else {
            // Integer part is [start, dot). Empty here (".25", ".") rejects,
            // because parseLong returns -1 on an empty range.
            intPart = parseLong(buf, start, dot);
            if (intPart == -1L) { return -1L; }

            int fracStart = dot + 1;
            int fracLen = end - fracStart;

            if (fracLen == 0 || fracLen > MAX_DECIMALS) { return -1L; }  // "150." or "150.256"

            long frac = parseLong(buf, fracStart, end);
            if (frac == -1L) { return -1L; }  // non-digit in fraction, e.g. "150.2x"

            // Scale by length to units of $0.0001: one digit -> *1000, two digits -> *100.
            fracScaled = (fracLen == 1) ? frac * 1000 : frac * 100;
        }

        // intPart * Prices.SCALE has enormous headroom in long units; ASML's
        // four-figure price is nowhere near overflow. Left un-guarded to keep
        // the happy path clean.
        long units = intPart * Prices.SCALE + fracScaled;

        // Price policy: zero or negative rejects. parseLong lets "0" through
        // as a valid 0, so the field-level check lives here, not in parseLong.
        return units > 0 ? units : -1L;
    }

    /**
     * Tokenises one complete FIX message into (tag, valueStart, valueEnd)
     * triples stored in the parallel arrays. Pure structure — no checksum,
     * no dispatch, no field-type judgment. Unknown tags pass through.
     *
     * @return number of fields scanned, or -1 on any structural reject.
     */
    int scan(byte[] buf, int offset, int length) {
        final int end = offset + length;
        int pos = offset;
        int count = 0;

        while (pos < end) {
            // --- locate '=' ending the tag; SOH-before-'=' means no '=' ---
            final int tagStart = pos;
            int eq = pos;
            while (eq < end && buf[eq] != FixConstants.EQUALS) {
                if (buf[eq] == FixConstants.SOH) {
                    return -1;                 // field has no '='
                }
                eq++;
            }
            if (eq == end) {
                return -1;                     // ran off the end seeking '='
            }

            // --- locate SOH ending the value; '=' inside the value is legal ---
            final int valStart = eq + 1;
            int soh = valStart;
            while (soh < end && buf[soh] != FixConstants.SOH) {
                soh++;
            }
            if (soh == end) {
                return -1;                     // value never terminated by SOH
            }

            // --- parse the tag; empty or non-numeric falls out as -1 ---
            final long tag = parseLong(buf, tagStart, eq);
            if (tag < 0) {
                return -1;                     // empty tag or non-digit tag
            }

            // --- bound the storage before writing ---
            if (count == FixConstants.MAX_FIELDS) {
                return -1;                     // too many fields
            }

            tags[count]      = (int) tag;
            valStarts[count] = valStart;
            valEnds[count]   = soh;
            count++;

            pos = soh + 1;                     // step past SOH to next field
        }

        return count;
    }

    /**
     * Verifies the FIX checksum (tag 10) for the message in buf[offset, offset+length).
     *
     * Relies on the frame decoder's trailer invariant: the message ends with the
     * 7-byte field 10=xxx<SOH>. The checksum covers every byte from the start of the
     * message up to and including the SOH preceding 10=, summed mod 256, compared
     * against the 3-digit value of tag 10.
     *
     * @return true if the computed checksum matches tag 10; false on any reject.
     */
    static boolean validateChecksum(byte[] buf, int offset, int length) {
        // Room for at least one summed byte plus the 7-byte trailer.
        if (length < 8) { return false; }

        int end = offset + length;
        int trailerStart = end - 7;  // index of '1' in "10="

        int sum = 0;
        for (int i = offset; i < trailerStart; i++) { sum += buf[i] & 0xFF; }  // byte is signed; checksum is unsigned mod 256

        long expected = parseLong(buf, end - 4, end - 1);  // the three "xxx" digits
        return expected == (sum & 0xFF);
    }

    // ---- Step 5: parse ----

    /**
     * Parses one complete FIX message into the pre-allocated OrderEvent slot.
     *
     * On a return of {@code false} the message is rejected and {@code target}
     * is left untouched (all field validation happens before any slot write),
     * so the gateway must simply not publish. On {@code true} every field the
     * message type uses is populated; the timestamp is stamped by the gateway.
     *
     * @return true if the message was valid and the slot was populated
     */
    boolean parse(byte[] buf, int offset, int length, OrderEvent target) {
        // 1. Structural trust before anything else.
        if (!validateChecksum(buf, offset, length)) return false;

        // 2. Tokenise into the parallel arrays.
        int count = scan(buf, offset, length);
        if (count < 0) return false;

        // 3. Locate the message type (tag 35); must be exactly one byte.
        int i = findTag(FixConstants.MESSAGE_TYPE, count);
        if (i < 0) return false;
        if (valEnds[i] - valStarts[i] != 1) return false;

        // 4. Dispatch on 35=.
        switch (buf[valStarts[i]]) {
            case FixConstants.MSG_TYPE_NEW_ORDER:
                return parseNewOrder(buf, count, target);
            case FixConstants.MSG_TYPE_CANCEL:
                return parseCancel(buf, count, target);
            default:
                return false; // unknown 35= type
        }
    }

    /** NewOrderSingle (35=D): requires tags 11, 54, 44, 38, 55. */
    private boolean parseNewOrder(byte[] buf, int count, OrderEvent target) {
        int iId = findTag(FixConstants.CL_ORD_ID, count);
        int iSide = findTag(FixConstants.SIDE, count);
        int iPrice = findTag(FixConstants.PRICE, count);
        int iQty = findTag(FixConstants.ORDER_QTY, count);
        int iSym = findTag(FixConstants.SYMBOL, count);
        if (iId < 0 || iSide < 0 || iPrice < 0 || iQty < 0 || iSym < 0) return false;

        // Symbol must match the single configured instrument.
        if (!symbolMatches(buf, valStarts[iSym], valEnds[iSym])) return false;

        // Field-type + shape validation, all into locals first.
        long orderId = FixConstants.parseLong(buf, valStarts[iId], valEnds[iId]);
        if (orderId < 0) return false;                     // non-numeric ClOrdID

        Side side = mapSide(buf, valStarts[iSide], valEnds[iSide]);
        if (side == null) return false;                    // 54=3 and friends

        long price = parsePrice(buf, valStarts[iPrice], valEnds[iPrice]);
        if (price < 0) return false;                       // 0 / negative / >2dp

        long qty = FixConstants.parseLong(buf, valStarts[iQty], valEnds[iQty]);
        if (qty <= 0) return false;                        // 38=0 and non-numeric

        // All checks passed — populate the slot. Timestamp is the gateway's job.
        target.eventType       = OrderEventType.NEW_ORDER;
        target.orderId         = orderId;
        target.side            = side;
        target.price           = price;
        target.quantity        = qty;
        target.originalOrderId = -1L;                      // unused by D; clear stale

        return true;
    }

    /** OrderCancelRequest (35=F): requires tags 11, 41. */
    private boolean parseCancel(byte[] buf, int count, OrderEvent target) {
        int iId   = findTag(FixConstants.CL_ORD_ID,      count);
        int iOrig = findTag(FixConstants.ORIG_CL_ORD_ID, count);
        if (iId < 0 || iOrig < 0) return false;

        long orderId = FixConstants.parseLong(buf, valStarts[iId], valEnds[iId]);
        if (orderId < 0) return false;

        long origId = FixConstants.parseLong(buf, valStarts[iOrig], valEnds[iOrig]);
        if (origId < 0) return false;

        target.eventType       = OrderEventType.CANCEL_ORDER;
        target.orderId         = orderId;
        target.originalOrderId = origId;
        target.side            = null;                     // unused by F; clear stale
        target.price           = -1L;
        target.quantity        = -1L;
        return true;
    }

    // ---- Step 5 helpers ----

    /** Linear scan of the tag array. Returns the field index, or -1 if absent. */
    private int findTag(int tag, int count) {
        for (int i = 0; i < count; i++) {
            if (tags[i] == tag) return i;
        }
        return -1;
    }

    /** Byte-for-byte compare of a value range against the configured symbol. */
    private boolean symbolMatches(byte[] buf, int start, int end) {
        byte[] sym = FixConstants.SYMBOL_BYTES;
        if (end - start != sym.length) return false;
        for (int k = 0; k < sym.length; k++) {
            if (buf[start + k] != sym[k]) return false;
        }
        return true;
    }

    /** FIX side code -> Side. '1' = buy, '2' = sell; anything else -> null. */
    private Side mapSide(byte[] buf, int start, int end) {
        if (end - start != 1) return null;
        switch (buf[start]) {
            case '1': return Side.BUY;
            case '2': return Side.SELL;
            default:  return null;
        }
    }
}