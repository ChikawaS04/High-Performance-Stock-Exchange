package integration;

import java.nio.charset.StandardCharsets;

/**
 * Complete FIX 4.2 wire messages for the journaled-pipeline tests: body fields in, a correct
 * BodyLength (tag 9) and CheckSum (tag 10) added. Side 1 is BUY, 2 is SELL; prices in dollars.
 */
public final class FixMessages {

    private static final char SOH = (char) 0x01;

    private FixMessages() { }

    public static byte[] limit(long clOrdId, char side, String price, long qty) {
        return msg("35=D", "11=" + clOrdId, "54=" + side, "44=" + price, "38=" + qty, "55=ASML");
    }

    public static byte[] limit(long clOrdId, char side, String price, long qty, char tif) {
        return msg("35=D", "11=" + clOrdId, "54=" + side, "44=" + price, "38=" + qty, "55=ASML", "59=" + tif);
    }

    public static byte[] iceberg(long clOrdId, char side, String price, long qty, long maxFloor) {
        return msg("35=D", "11=" + clOrdId, "54=" + side, "44=" + price, "38=" + qty, "55=ASML",
                "111=" + maxFloor);
    }

    public static byte[] peg(long clOrdId, char side, long qty, char tif) {
        return msg("35=D", "11=" + clOrdId, "54=" + side, "40=P", "18=M", "38=" + qty, "55=ASML", "59=" + tif);
    }

    public static byte[] cancel(long clOrdId, long origClOrdId) {
        return msg("35=F", "11=" + clOrdId, "41=" + origClOrdId);
    }

    /** Assembles a message from body fields (35= onward, without SOH). */
    public static byte[] msg(String... bodyFields) {
        StringBuilder b = new StringBuilder();
        for (String f : bodyFields) b.append(f).append(SOH);
        byte[] body = b.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] header = ("8=FIX.4.2" + SOH + "9=" + body.length + SOH).getBytes(StandardCharsets.US_ASCII);
        int sum = 0;
        for (byte x : header) sum += (x & 0xFF);
        for (byte x : body) sum += (x & 0xFF);
        byte[] trailer = ("10=" + String.format("%03d", sum & 0xFF) + SOH).getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[header.length + body.length + trailer.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        System.arraycopy(trailer, 0, out, header.length + body.length, trailer.length);
        return out;
    }
}
