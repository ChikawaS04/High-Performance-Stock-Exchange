package net;

import io.netty.util.AttributeKey;

/**
 * One WebSocket channel's access state (P16-9). Created per channel by the server's initializer and shared by
 * that channel's {@link HttpRouter} (which admits it at the upgrade) and {@link InboundFrameLimiter} (which
 * meters its frames); {@link ResendService} reads it through {@link #KEY}. Confined to the single Netty worker
 * thread, so the fields are plain.
 */
final class ChannelAccess {

    /** Set on the channel once it is admitted, for the resend service. */
    static final AttributeKey<ChannelAccess> KEY = AttributeKey.valueOf("credo.access");

    /** True once the upgrade was admitted; frames of a channel never admitted pass unmetered. */
    boolean admitted;
    /** The socket address is exempt: every limit is skipped. */
    boolean exempt;
    /** The counts were given back at close; guards against a second release. */
    boolean released;
    /** The client this channel belongs to; null when exempt. */
    ClientState client;

    /** The channel bucket's arrival time (see {@link Gcra}). */
    long channelTat;
    /** When the current over-limit episode began, and the last channel or client drop; valid when {@link #dropping}. */
    long episodeStartNanos;
    long lastDropNanos;
    boolean dropping;
    /** A 1008 close has been started: every later frame is dropped without counting. */
    boolean closing;
    /** At most one drop WARN a minute for this channel. */
    final MinuteLog dropLog = new MinuteLog();

    /** Whether this channel's RESEND counts against the public limits. */
    boolean isPublic() {
        return admitted && !exempt;
    }

    /**
     * One client address (P16-9): its shared bucket and its open connections. Lives in {@link AccessControl}'s
     * active map while it has a connection and in its bounded idle map after, so a client that reconnects does not
     * get a fresh bucket. Confined to the single Netty worker thread.
     */
    static final class ClientState {
        final ClientKey key;
        int connections;
        /** The client bucket's arrival time (see {@link Gcra}). */
        long tat;
        /** The last accepted public RESEND, valid when {@link #resent}. */
        long lastResendNanos;
        boolean resent;

        ClientState(ClientKey key, long now) {
            this.key = key;
            this.tat = now;
        }
    }
}
