package net;

import net.ChannelAccess.ClientState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The public access limits of the WebSocket port (P16-9, D15 as replaced on 2026-11-18). Venue-wide, one per
 * server, and confined to the server's single Netty worker thread (the same invariant that keeps the inbound
 * ring single-producer), so every field is plain and nothing here locks or allocates per frame.
 *
 * <p><b>Who is exempt.</b> A connection whose socket address is in the exempt list (the bots' fixed address on
 * the Compose network) skips every limit. The match is on the socket address only, never on
 * {@code X-Forwarded-For}; a connection from the trusted proxy is never exempt (the config refuses the two lists
 * overlapping).
 *
 * <p><b>Who a client is.</b> The socket address, or, when the socket address is the trusted proxy, the rightmost
 * {@code X-Forwarded-For} entry (the one the proxy added; anything to its left came from the client). A missing or
 * malformed header from the trusted proxy falls back to the proxy's own address, which puts every such
 * connection in one shared client, logged and counted as {@link #xffFallback()}. See {@link ClientKey}.
 *
 * <p><b>Connections</b> are capped at the upgrade ({@link #admit}): non-exempt connections venue-wide, and per
 * client. {@link #release} gives the counts back at close, once.
 *
 * <p><b>Frames</b> ({@link #onFrame}) pass three buckets in order: the channel's, its client's, and one public
 * bucket shared by every non-exempt client. A frame is admitted only if all three conform, and only then are the
 * tokens spent, so a frame refused by a later bucket costs nothing from the earlier ones. A refused frame is
 * counted against the first bucket that refused it. Channel and client refusals that keep coming (no gap of a
 * second or more) for the close interval close the channel with 1008; public refusals never close.
 *
 * <p><b>Client state</b> is held in an active map (clients with an open connection, bounded by the connection
 * cap) and an access-ordered idle map (clients whose last connection closed), bounded at the idle limit by
 * evicting the least recently closed. An evicted client's bucket would have been full again within seconds; it
 * loses only its public RESEND interval stamp.
 */
public final class AccessControl {

    private static final Logger log = LoggerFactory.getLogger(AccessControl.class);

    /** Two refusals more than this apart start a new over-limit episode. */
    static final long EPISODE_GAP_NANOS = 1_000_000_000L;

    /** Verdicts of {@link #onFrame}. Constants, so the frame path allocates nothing. */
    static final int PASS = 0;
    static final int DROP = 1;
    static final int CLOSE = 2;

    /** Every limit, as {@code config.ExchangeConfig} reads them. */
    public record Limits(int channelRate, int channelBurst, int clientRate, int clientBurst,
                         int publicRate, int publicBurst, long overLimitCloseMillis,
                         int maxConnections, int maxConnectionsPerClient, int idleClientsMax,
                         List<String> trustedProxies, List<String> exemptAddresses) {

        public Limits {
            trustedProxies = List.copyOf(trustedProxies);
            exemptAddresses = List.copyOf(exemptAddresses);
            if (maxConnectionsPerClient > maxConnections) {
                throw new IllegalArgumentException("per-client cap above the venue cap");
            }
        }

        /** The ExchangeConfig defaults, with the given proxy and exempt lists. */
        public static Limits defaults(List<String> trustedProxies, List<String> exemptAddresses) {
            return new Limits(10, 30, 10, 30, 20, 100, 5_000, 200, 10, 4_096, trustedProxies, exemptAddresses);
        }
    }

    private final Limits limits;
    private final LongSupplier nanos;
    private final Gcra channelBucket;
    private final Gcra clientBucket;
    private final Gcra publicBucket;
    private final long closeAfterNanos;
    private final Set<InetAddress> trusted;
    private final Set<InetAddress> exempt;

    private final Map<ClientKey, ClientState> active = new HashMap<>();
    private final LinkedHashMap<ClientKey, ClientState> idle;

    /** The public bucket's arrival time. */
    private long publicTat;

    private int publicConnections;
    private int exemptConnections;
    private long droppedChannel;
    private long droppedClient;
    private long droppedPublic;
    private long closed1008;
    private long refusedVenue;
    private long refusedClient;
    private long xffFallback;
    private long idleEvicted;

    private final MinuteLog refusedLog = new MinuteLog();
    private final MinuteLog closeLog = new MinuteLog();
    private final MinuteLog xffLog = new MinuteLog();

    public AccessControl(Limits limits) {
        this(limits, System::nanoTime);
    }

    AccessControl(Limits limits, LongSupplier nanos) {
        this.limits = limits;
        this.nanos = nanos;
        this.channelBucket = new Gcra(limits.channelRate(), limits.channelBurst());
        this.clientBucket = new Gcra(limits.clientRate(), limits.clientBurst());
        this.publicBucket = new Gcra(limits.publicRate(), limits.publicBurst());
        this.closeAfterNanos = limits.overLimitCloseMillis() * 1_000_000L;
        this.trusted = addresses(limits.trustedProxies());
        this.exempt = addresses(limits.exemptAddresses());
        for (InetAddress a : exempt) {
            if (trusted.contains(a)) {
                throw new IllegalArgumentException(a.getHostAddress() + " is both exempt and the trusted proxy");
            }
        }
        final int idleMax = limits.idleClientsMax();
        this.idle = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ClientKey, ClientState> eldest) {
                if (size() > idleMax) {
                    idleEvicted++;
                    return true;
                }
                return false;
            }
        };
        this.publicTat = nanos.getAsLong();
    }

    private static Set<InetAddress> addresses(List<String> literals) {
        Set<InetAddress> out = new HashSet<>();
        for (String s : literals) {
            byte[] b = ClientKey.literal(s);
            if (b == null) throw new IllegalArgumentException("not an IP literal: " + s);
            try {
                out.add(InetAddress.getByAddress(b));   // from bytes: no lookup
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("not an IP literal: " + s, e);
            }
        }
        return out;
    }

    public Limits limits() {
        return limits;
    }

    /** The clock every bucket runs on: {@link System#nanoTime()} in production, a test clock in tests. */
    long now() {
        return nanos.getAsLong();
    }

    /**
     * Admits a WebSocket upgrade, or refuses it because a connection cap is reached. On the worker thread, once
     * per channel; fills {@code channel}. A refused upgrade leaves {@code channel} unadmitted and changes no count.
     *
     * @param remote          the socket's remote address
     * @param forwardedFor    every {@code X-Forwarded-For} header value of the upgrade request, in order
     * @return true when admitted
     */
    boolean admit(ChannelAccess channel, SocketAddress remote, List<String> forwardedFor) {
        if (channel.admitted) return true;
        long now = nanos.getAsLong();
        InetAddress socketIp = remote instanceof InetSocketAddress isa ? isa.getAddress() : null;
        if (socketIp != null && exempt.contains(socketIp)) {
            channel.admitted = true;
            channel.exempt = true;
            exemptConnections++;
            return true;
        }
        ClientKey key = clientKey(socketIp, forwardedFor, now);
        if (publicConnections >= limits.maxConnections()) {
            refusedVenue++;
            logRefused(now, key, "the venue-wide cap of " + limits.maxConnections());
            return false;
        }
        ClientState client = active.get(key);
        if (client != null && client.connections >= limits.maxConnectionsPerClient()) {
            refusedClient++;
            logRefused(now, key, "the per-client cap of " + limits.maxConnectionsPerClient());
            return false;
        }
        if (client == null) {
            client = idle.remove(key);
            if (client == null) {
                client = new ClientState(key, now);
            }
            active.put(key, client);
        }
        client.connections++;
        publicConnections++;
        channel.admitted = true;
        channel.client = client;
        channel.channelTat = now;
        return true;
    }

    private ClientKey clientKey(InetAddress socketIp, List<String> forwardedFor, long now) {
        if (socketIp == null) {
            return ClientKey.UNKNOWN;
        }
        if (trusted.contains(socketIp)) {
            byte[] forwarded = ClientKey.rightmostForwardedFor(forwardedFor);
            if (forwarded != null) {
                return ClientKey.of(forwarded);
            }
            xffFallback++;
            long held = xffLog.occurrence(now);
            if (held >= 0) {
                log.warn("WebSocket upgrade from the trusted proxy {} with a missing or malformed X-Forwarded-For; "
                        + "keyed as the proxy itself, so it shares one client's limits{}", socketIp.getHostAddress(),
                        held > 0 ? " (" + held + " more since the last warning)" : "");
            }
        }
        return ClientKey.of(socketIp);
    }

    private void logRefused(long now, ClientKey key, String cap) {
        long held = refusedLog.occurrence(now);
        if (held >= 0) {
            log.warn("WebSocket upgrade from {} refused with 503 at {}; refused so far: {} venue-wide, {} per client{}",
                    key, cap, refusedVenue, refusedClient, held > 0 ? " (" + held + " more since the last warning)" : "");
        }
    }

    /** Gives back a closed channel's counts. Idempotent: only the first call for an admitted channel counts. */
    void release(ChannelAccess channel) {
        if (!channel.admitted || channel.released) return;
        channel.released = true;
        if (channel.exempt) {
            exemptConnections--;
            return;
        }
        publicConnections--;
        ClientState client = channel.client;
        if (--client.connections == 0) {
            active.remove(client.key);
            idle.put(client.key, client);
        }
    }

    /**
     * Meters one inbound frame of {@code channel} (any frame but Close). No allocation: three compares and at most
     * three stores, plus a counter.
     *
     * @return {@link #PASS}, {@link #DROP}, or {@link #CLOSE} when the channel must now be closed with 1008
     */
    int onFrame(ChannelAccess channel, long now) {
        if (!channel.admitted || channel.exempt) return PASS;
        if (channel.closing) return DROP;
        ClientState client = channel.client;
        boolean channelOk = channelBucket.conforms(channel.channelTat, now);
        boolean clientOk = clientBucket.conforms(client.tat, now);
        boolean publicOk = publicBucket.conforms(publicTat, now);
        if (channelOk && clientOk && publicOk) {
            channel.channelTat = channelBucket.spend(channel.channelTat, now);
            client.tat = clientBucket.spend(client.tat, now);
            publicTat = publicBucket.spend(publicTat, now);
            return PASS;
        }
        String layer;
        if (!channelOk) {
            droppedChannel++;
            layer = "channel";
        } else if (!clientOk) {
            droppedClient++;
            layer = "client";
        } else {
            droppedPublic++;
            layer = "public";
        }
        if (channelOk && clientOk) {
            logDrop(channel, now, layer);   // the public bucket drops but never closes
            return DROP;
        }
        if (!channel.dropping || now - channel.lastDropNanos >= EPISODE_GAP_NANOS) {
            channel.episodeStartNanos = now;
            channel.dropping = true;
        }
        channel.lastDropNanos = now;
        if (now - channel.episodeStartNanos >= closeAfterNanos) {
            channel.closing = true;
            closed1008++;
            long held = closeLog.occurrence(now);
            if (held >= 0) {
                log.warn("Closing a WebSocket of {} with 1008: over its {} limit for {} ms; closed so far: {}{}",
                        client.key, layer, limits.overLimitCloseMillis(), closed1008,
                        held > 0 ? " (" + held + " more since the last warning)" : "");
            }
            return CLOSE;
        }
        logDrop(channel, now, layer);
        return DROP;
    }

    private void logDrop(ChannelAccess channel, long now, String layer) {
        long held = channel.dropLog.occurrence(now);
        if (held >= 0) {
            log.warn("Dropping WebSocket frames from {}: over the {} limit{}", channel.client.key, layer,
                    held > 0 ? " (" + held + " more dropped on this channel since the last warning)" : "");
        }
    }

    // --- counters for /health (worker thread) ---

    int publicConnections() { return publicConnections; }
    int exemptConnections() { return exemptConnections; }
    int activeClients() { return active.size(); }
    int idleClients() { return idle.size(); }
    long idleEvicted() { return idleEvicted; }
    long droppedChannel() { return droppedChannel; }
    long droppedClient() { return droppedClient; }
    long droppedPublic() { return droppedPublic; }
    long closed1008() { return closed1008; }
    long refusedVenue() { return refusedVenue; }
    long refusedClient() { return refusedClient; }
    long xffFallback() { return xffFallback; }

    /** Whether {@code key} is remembered as an idle client (tests). */
    boolean isIdle(ClientKey key) {
        return idle.containsKey(key);
    }
}
