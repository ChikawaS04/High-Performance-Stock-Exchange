package net;

import gateway.FixConstants;
import model.Prices;
import model.Side;
import model.TimeInForce;

import java.nio.charset.StandardCharsets;

/**
 * Encodes a validated manual order into a complete FIX 4.2 tag-value message that the existing
 * FixParser accepts. Promotes the Phase-3 test {@code msg(...)} builder to production so the
 * WebSocket inbound path reuses the real FIX gateway rather than a parallel path (decision 1).
 *
 * <p>Framework-free: no io.netty, no com.lmax, no Jackson. Prices arrive as long units of $0.0001
 * (§4) and are formatted to FIX decimal dollars (tag 44) here — the single units&lt;-&gt;FIX bridge. The
 * {@code 9=} BodyLength and {@code 10=} CheckSum are computed exactly as the parser's checksum
 * validation expects; a round-trip test asserts {@code parse()} accepts the output.
 */
public final class JsonToFix {

    private static final byte SOH = FixConstants.SOH;   // 0x01 — the delimiter the parser splits on
    private static final char SOH_C = (char) SOH;
    private static final String BEGIN_STRING = "FIX.4.2";

    private JsonToFix() { }

    /**
     * NewOrderSingle (35=D), legacy 5-arg form. Kept so existing callers and tests are unchanged;
     * delegates with GTC time in force (Phase 14 decision J). Because it delegates, it now also
     * emits tag 59=1, so a frame it produces is correct FIX whichever default the reader applies.
     */
    public static byte[] newOrderSingle(long clOrdId, Side side, long priceUnits, long qty, String symbol) {
        return newOrderSingle(clOrdId, side, priceUnits, qty, symbol, TimeInForce.GTC);
    }

    /**
     * NewOrderSingle (35=D) with an explicit time in force (legacy 6-arg form). Kept as a
     * delegating overload so existing callers are unchanged; delegates with max floor 0, a
     * non-iceberg order (Phase 14 decision J).
     */
    public static byte[] newOrderSingle(long clOrdId, Side side, long priceUnits, long qty, String symbol,
                                        TimeInForce tif) {
        return newOrderSingle(clOrdId, side, priceUnits, qty, symbol, tif, 0L);
    }

    /**
     * NewOrderSingle (35=D) with an explicit time in force and iceberg display size: tags 11, 55,
     * 54, 38, 44, 59, plus 111 only when {@code maxFloor > 0} (an order that hides nothing omits
     * it). The terminal always sends tag 59 (§3.1/§3.6). The tag order leaves room for 40 (OrdType)
     * to land between 38 and 44 at P14-9; the parser is tag-order-agnostic, so appending 111 last
     * is valid FIX now and reorders cleanly then.
     */
    public static byte[] newOrderSingle(long clOrdId, Side side, long priceUnits, long qty, String symbol,
                                        TimeInForce tif, long maxFloor) {
        if (maxFloor > 0) {
            return assemble(
                    "35=D",
                    "11=" + clOrdId,
                    "55=" + symbol,
                    "54=" + sideCode(side),
                    "38=" + qty,
                    "44=" + formatPrice(priceUnits),
                    "59=" + tifCode(tif),
                    "111=" + maxFloor);
        }
        return assemble(
                "35=D",
                "11=" + clOrdId,
                "55=" + symbol,
                "54=" + sideCode(side),
                "38=" + qty,
                "44=" + formatPrice(priceUnits),
                "59=" + tifCode(tif));
    }

    /** OrderCancelRequest (35=F): tags 11, 41 — the set parseCancel requires. */
    public static byte[] orderCancelRequest(long clOrdId, long origClOrdId) {
        return assemble(
                "35=F",
                "11=" + clOrdId,
                "41=" + origClOrdId);
    }

    /** FIX side code: 1 = buy, 2 = sell (mirrors FixParser.mapSide). */
    private static char sideCode(Side side) {
        return side == Side.BUY ? '1' : '2';
    }

    /** FIX TimeInForce code: 1 = GTC, 3 = IOC, 4 = FOK (mirrors FixParser.mapTif). */
    private static char tifCode(TimeInForce tif) {
        switch (tif) {
            case IOC: return '3';
            case FOK: return '4';
            case GTC:
            default:  return '1';
        }
    }

    /**
     * long units of $0.0001 -> FIX decimal dollars. An on-tick price emits exactly two places
     * (1_502_500 -> "150.25", 500 -> "0.05"); an off-tick price emits four places
     * (1_502_550 -> "150.2550"), which parsePrice then rejects (more than two decimals), so an
     * off-tick limit price is dropped at the FIX authority rather than silently rounded.
     */
    static String formatPrice(long px) {
        long dollars = px / Prices.SCALE;
        long frac = px % Prices.SCALE;                  // 0..9999 ten-thousandths of a dollar
        if (frac % 100 == 0) {                          // on the one-cent tick -> two places
            long centPart = frac / 100;
            return dollars + "." + (centPart < 10 ? "0" + centPart : Long.toString(centPart));
        }
        return dollars + "." + String.format("%04d", frac);   // off tick -> four places
    }

    /** Prepend 8=/9=&lt;bodylen&gt;, append 10=&lt;checksum&gt; — identical framing to the Phase-3 msg() builder. */
    private static byte[] assemble(String... bodyFields) {
        StringBuilder sb = new StringBuilder();
        for (String f : bodyFields) {
            sb.append(f).append(SOH_C);
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.US_ASCII);

        byte[] header = ("8=" + BEGIN_STRING + SOH_C + "9=" + body.length + SOH_C)
                .getBytes(StandardCharsets.US_ASCII);

        int sum = 0;
        for (byte x : header) sum += (x & 0xFF);
        for (byte x : body) sum += (x & 0xFF);
        byte[] trailer = ("10=" + String.format("%03d", sum & 0xFF) + SOH_C)
                .getBytes(StandardCharsets.US_ASCII);

        byte[] out = new byte[header.length + body.length + trailer.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        System.arraycopy(trailer, 0, out, header.length + body.length, trailer.length);
        return out;
    }
}
