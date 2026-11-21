package net;

import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.util.List;

/**
 * Who a public connection belongs to, for the per-client bucket and the connection cap (P16-9). An IPv4 address
 * is its own key. An IPv6 address is keyed by its /64 prefix, because one subscriber is routinely given a whole
 * /64 and could otherwise open a fresh key per address. An IPv4-mapped IPv6 address is keyed as IPv4.
 *
 * <p>Built at the upgrade, never per frame. Immutable; equality is by value.
 */
final class ClientKey {

    /** For a remote address that is not an IP socket address (only seen in tests). */
    static final ClientKey UNKNOWN = new ClientKey(false, -1L, "unknown");

    private final boolean v6;
    private final long bits;
    private final String text;

    private ClientKey(boolean v6, long bits, String text) {
        this.v6 = v6;
        this.bits = bits;
        this.text = text;
    }

    static ClientKey of(InetAddress address) {
        return of(address.getAddress());
    }

    /** From 4 or 16 address bytes. */
    static ClientKey of(byte[] a) {
        if (a.length == 16 && isV4Mapped(a)) {
            byte[] v4 = {a[12], a[13], a[14], a[15]};
            return of(v4);
        }
        if (a.length == 4) {
            long v = ((a[0] & 0xFFL) << 24) | ((a[1] & 0xFFL) << 16) | ((a[2] & 0xFFL) << 8) | (a[3] & 0xFFL);
            return new ClientKey(false, v, NetUtil.bytesToIpAddress(a));
        }
        if (a.length == 16) {
            long prefix = 0;
            for (int i = 0; i < 8; i++) {
                prefix = (prefix << 8) | (a[i] & 0xFFL);
            }
            byte[] masked = new byte[16];
            System.arraycopy(a, 0, masked, 0, 8);
            return new ClientKey(true, prefix, NetUtil.bytesToIpAddress(masked) + "/64");
        }
        throw new IllegalArgumentException("not an IP address: " + a.length + " bytes");
    }

    private static boolean isV4Mapped(byte[] a) {
        for (int i = 0; i < 10; i++) {
            if (a[i] != 0) return false;
        }
        return (a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF;
    }

    /**
     * The address bytes of a bare IPv4 or IPv6 literal, or null for anything else: a host name, a port,
     * brackets, a zone, {@code unknown}, an empty string. Never resolves through DNS.
     */
    static byte[] literal(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty() || t.indexOf('[') >= 0 || t.indexOf(']') >= 0 || t.indexOf('%') >= 0) return null;
        return NetUtil.createByteArrayFromIpAddressString(t);
    }

    /**
     * The client address from the {@code X-Forwarded-For} values of a request from the trusted proxy (P16-9): the
     * rightmost entry, the one the proxy itself added, across every header line joined in order. Anything to its
     * left was written by the client and is ignored. Null when there is none or it is not a bare IP literal.
     */
    static byte[] rightmostForwardedFor(List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) return null;
        String last = headerValues.get(headerValues.size() - 1);
        if (last == null) return null;
        int comma = last.lastIndexOf(',');
        return literal(comma < 0 ? last : last.substring(comma + 1));
    }

    boolean isV6() {
        return v6;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClientKey k && k.v6 == v6 && k.bits == bits;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(bits) * 31 + (v6 ? 1 : 0);
    }

    @Override
    public String toString() {
        return text;
    }
}
