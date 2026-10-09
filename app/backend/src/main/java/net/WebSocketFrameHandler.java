package net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gateway.OrderGateway;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

import util.BookFrameCache;

/**
 * Per-connection tail handler for the WebSocket pipeline. On a completed handshake it registers
 * the channel in the shared {@link ChannelGroup} (P4-4); on a text frame it translates a manual
 * JSON order into FIX bytes and feeds the existing gateway (P4-5), then echoes those exact bytes
 * back to the originating channel (P7-2).
 *
 * <p>Registration happens on {@link WebSocketServerProtocolHandler.HandshakeComplete}, not
 * channelActive, so a still-upgrading HTTP channel never joins the broadcast group.
 * {@code DefaultChannelGroup} auto-removes on close, so there is no deregistration to maintain.
 *
 * <p>Not {@code @Sharable} — the server initializer creates one per channel.
 *
 * <p><b>Raw inbound FIX echo (P7-2).</b> The inspector must show the bytes the server actually
 * parsed, not a browser-side reconstruction — there is no FIX encoder in the frontend, and
 * rebuilding {@code 9=BodyLength} / {@code 10=CheckSum} client-side would invent exactly the two
 * fields most likely to diverge. So after {@link OrderGateway#onFrame} consumes the byte array,
 * the same array is serialized to a {@code FIX} JSON frame and written back to the originating
 * channel <i>only</i>, never the group: another client's inspector must not show this client's
 * packets. The echo is emitted after {@code onFrame} so observability never delays the inbound
 * path, and only on a path that actually produced bytes — malformed JSON, an unknown type and an
 * invalid side all return or throw before {@code fix} exists, so none of them echoes.
 *
 * <p><b>seqNum.</b> A per-channel outbound counter for this echo stream, incremented only when an
 * echo is actually emitted. It is deliberately <i>not</i> the FIX {@code 34=} MsgSeqNum — the
 * produced-and-parsed subset carries no tag 34/49/56/52 at all (P7-0/Q7-4) — and it mirrors no
 * existing counter, because neither EXEC nor BOOK carries a sequence number on the wire. Because
 * this handler is per-channel and the worker group is one thread, the counter is
 * single-thread-confined and needs no atomic.
 *
 * <p><b>Latin-1 is load-bearing.</b> {@code raw} is decoded ISO-8859-1, a bijective byte-to-char
 * map, so SOH (0x01) and every other byte survive exactly and the string maps back to the
 * original array. Jackson escapes SOH as {@code \u0001}; {@code JSON.parse} restores it. That is
 * what makes the byte-identity assertion in the tests exact rather than approximate.
 */
public class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(WebSocketFrameHandler.class);

    private final ChannelGroup channelGroup;
    private final OrderGateway gateway;
    private final ObjectMapper objectMapper;

    /**
     * Shared {@link util.EpochNanoClock} instance (P7-1). Injected rather than read locally so the
     * echo timestamp lands in the same epoch-nanos domain as the EXEC and BOOK stamps it sits
     * beside in the P7-9 inspector. One anchor across every site is the load-bearing part.
     */
    private final LongSupplier clock;

    /**
     * Shared cache of the latest published BOOK frame. Read on HandshakeComplete to send a
     * connecting client the current depth immediately; null when the server wired no cache (tests).
     */
    private final BookFrameCache bookCache;

    /** Per-channel echo counter; single-thread-confined to this channel's event loop. */
    private long fixSeqNum;

    public WebSocketFrameHandler(ChannelGroup channelGroup,
                                 OrderGateway gateway,
                                 ObjectMapper objectMapper,
                                 LongSupplier clock) {
        this(channelGroup, gateway, objectMapper, clock, null);
    }

    public WebSocketFrameHandler(ChannelGroup channelGroup,
                                 OrderGateway gateway,
                                 ObjectMapper objectMapper,
                                 LongSupplier clock,
                                 BookFrameCache bookCache) {
        this.channelGroup = channelGroup;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.bookCache = bookCache;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            channelGroup.add(ctx.channel());
            log.info("WS client connected: {} ({} total)",
                    ctx.channel().remoteAddress(), channelGroup.size());
            sendBookSnapshot(ctx.channel());
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * Hand a just-connected client the current book. The book is a pure projection of the stream
     * and snapshots are published only per inbound event, so without this a fresh or reconnecting
     * client would render an empty ladder until the next order flowed anywhere on the book. The
     * latest BOOK frame text comes from the shared {@link BookFrameCache}, which the publisher fills
     * on its own consumer thread; that field is volatile, so this worker-thread read sees a safely
     * published value with no lock and with no cross-thread read of the live book (which the
     * single-writer design forbids). A null cache or a null value means nothing has been published
     * yet (empty venue) — the client waits for the first live snapshot, exactly as before. BOOK is
     * wholesale-replace, so if a live broadcast races in right after this send, whichever the client
     * applies last wins and the next event reconciles anyway.
     */
    private void sendBookSnapshot(Channel channel) {
        if (bookCache == null) {
            return;
        }
        String json = bookCache.get();
        if (json != null) {
            channel.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame text) {
            handleOrderJson(ctx.channel(), text.text());
        }
        // binary/ping/pong/close are handled upstream; ignore anything else here.
    }

    /**
     * Edge translation (§5.5): JSON -> FIX 4.2 bytes -> the existing FIX inbound authority.
     * Runs on the single worker thread, so {@link OrderGateway} stays the single inbound producer
     * (§5.2). FixFrameDecoder is not needed — each WS frame is already exactly one message, so the
     * JsonToFix output goes straight to onFrame. Malformed input is logged and dropped at the
     * boundary (§5.4); onFrame independently logs+drops FIX-invalid frames.
     *
     * <p>The channel is threaded in (P7-2) purely so the echo can target the originating
     * connection. Nothing else here consults it.
     */
    private void handleOrderJson(Channel channel, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String type = node.path("type").asText();

            byte[] fix;
            if ("NEW".equals(type)) {
                Side side = mapSide(node.path("side").asText(null));
                if (side == null) {
                    log.warn("Dropping NEW order with invalid/missing side: {}", json);
                    return;
                }
                // Order type: a missing or null ordType defaults to LIMIT; an unknown value is
                // dropped at the boundary with a warning, exactly as an invalid side is (SRS 3.6).
                JsonNode ordTypeNode = node.path("ordType");
                String ordTypeStr = (ordTypeNode.isMissingNode() || ordTypeNode.isNull()) ? "LIMIT" : ordTypeNode.asText();
                OrdType ordType = mapOrdType(ordTypeStr);
                if (ordType == null) {
                    log.warn("Dropping NEW order with unknown ordType '{}': {}", ordTypeStr, json);
                    return;
                }
                // Time in force: a missing or null tif defaults to GTC; an unknown value is dropped
                // at the boundary with a warning, exactly as an invalid side is (§3.6).
                JsonNode tifNode = node.path("tif");
                String tifStr = (tifNode.isMissingNode() || tifNode.isNull()) ? "GTC" : tifNode.asText();
                TimeInForce tif = mapTif(tifStr);
                if (tif == null) {
                    log.warn("Dropping NEW order with unknown tif '{}': {}", tifStr, json);
                    return;
                }
                // Max floor (iceberg display size): a missing or null maxFloor defaults to 0 (not an
                // iceberg). Structural and domain validation of the value is the FIX parser's and the
                // Order constructor's job downstream (§3.1), as for price and qty.
                JsonNode maxFloorNode = node.path("maxFloor");
                long maxFloor = (maxFloorNode.isMissingNode() || maxFloorNode.isNull()) ? 0L : maxFloorNode.asLong();
                // A peg carries no price of its own; the frontend sends price:-1, but branch on
                // ordType so a peg frame that omits price never trips the reader (P14-0 finding).
                long price = (ordType == OrdType.PEG_MID) ? -1L : node.get("price").asLong();
                fix = JsonToFix.newOrderSingle(
                        node.get("clOrdId").asLong(),
                        side,
                        ordType,
                        price,
                        node.get("qty").asLong(),
                        node.get("symbol").asText(),
                        tif,
                        maxFloor);
            } else if ("CANCEL".equals(type)) {
                fix = JsonToFix.orderCancelRequest(
                        node.get("clOrdId").asLong(),
                        node.get("origClOrdId").asLong());
            } else {
                log.warn("Dropping WS frame with unknown type '{}'", type);
                return;
            }

            gateway.onFrame(fix);
            echoRawFix(channel, fix);
        } catch (Exception e) {
            log.warn("Dropping malformed WS order frame: {}", e.toString());
        }
    }

    /**
     * Write the raw inbound packet back to the originating channel as a {@code FIX} frame.
     *
     * <p>Guards its own failures rather than falling through to the caller's catch: a
     * serialization problem here is an observability fault on a frame the engine has already
     * accepted, and logging it as a malformed inbound order would be actively misleading.
     * Serialization allocation is fine at this boundary (§5.1).
     */
    private void echoRawFix(Channel channel, byte[] fix) {
        try {
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("type", "FIX");
            frame.put("direction", "INBOUND");
            frame.put("raw", new String(fix, StandardCharsets.ISO_8859_1));
            frame.put("seqNum", ++fixSeqNum);
            frame.put("timestamp", clock.getAsLong());
            channel.writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(frame)));
        } catch (Exception e) {
            log.warn("Failed to echo raw inbound FIX: {}", e.toString());
        }
    }

    private static Side mapSide(String s) {
        if ("BUY".equals(s)) return Side.BUY;
        if ("SELL".equals(s)) return Side.SELL;
        return null;
    }

    /** JSON tif string -> enum. "GTC"/"IOC"/"FOK" map through; anything else -> null (dropped). */
    private static TimeInForce mapTif(String s) {
        if ("GTC".equals(s)) return TimeInForce.GTC;
        if ("IOC".equals(s)) return TimeInForce.IOC;
        if ("FOK".equals(s)) return TimeInForce.FOK;
        return null;
    }

    /** JSON ordType string -> enum. "LIMIT"/"PEG_MID" map through; anything else -> null (dropped). */
    private static OrdType mapOrdType(String s) {
        if ("LIMIT".equals(s)) return OrdType.LIMIT;
        if ("PEG_MID".equals(s)) return OrdType.PEG_MID;
        return null;
    }
}
