package net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.ClearingStatus;
import util.ReporterStatus;
import util.VenueStatus;

import java.nio.charset.StandardCharsets;

/**
 * Routes plain HTTP on the WebSocket port (SRS 3.6, P15-7). Sits after the aggregator, so it sees
 * whole requests, and ahead of the WebSocket protocol handler.
 *
 * <ul>
 *   <li>A request whose URI is exactly {@code /ws} passes through untouched to the WebSocket
 *       handshake. This is the same test {@code WebSocketServerProtocolHandler} applies (an exact
 *       match on the whole URI, query string included), so the set of requests that upgrade is
 *       the same as before the router existed.</li>
 *   <li>Every other route matches on the path only. {@code GET /health} answers 200 with a small
 *       JSON body, or 503 with the same body when the venue is not running; another method on
 *       {@code /health} gets 405 with {@code Allow: GET}.</li>
 *   <li>Anything else gets 404: {@code /api} until P15-12, and a path of {@code /ws} with any
 *       other URI (such as {@code /ws?x=1}), which never upgraded and used to get no answer.</li>
 * </ul>
 *
 * Keep-alive follows the request ({@link HttpUtil#isKeepAlive}); a request that does not keep
 * the connection alive is answered and the channel closed. Every response sets
 * {@code Content-Type} and {@code Content-Length}.
 *
 * The router stays in the pipeline after an upgrade. WebSocket frames are not
 * {@link FullHttpRequest}s, so they pass through with one type check, and socket errors fired
 * from the head of an upgraded channel are still caught here.
 *
 * Not {@code @Sharable}: the server initializer creates one per channel. Runs on the single
 * worker thread, which is what makes sharing the server's {@link ObjectMapper} safe.
 */
public final class HttpRouter extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpRouter.class);

    static final String HEALTH_PATH = "/health";
    private static final String JSON = "application/json";

    private static final byte[] NOT_FOUND = "{\"error\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] METHOD_NOT_ALLOWED = "{\"error\":\"METHOD_NOT_ALLOWED\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BAD_REQUEST = "{\"error\":\"BAD_REQUEST\"}".getBytes(StandardCharsets.UTF_8);

    private final String webSocketPath;
    private final VenueStatus status;
    private final ObjectMapper objectMapper;

    /**
     * @param webSocketPath the path the WebSocket protocol handler upgrades
     * @param status        what {@code /health} reports; null means the server serves no
     *                      {@code /health} route, and it answers 404
     */
    public HttpRouter(String webSocketPath, VenueStatus status, ObjectMapper objectMapper) {
        this.webSocketPath = webSocketPath;
        this.status = status;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.decoderResult().isFailure()) {
            send(ctx, req, HttpResponseStatus.BAD_REQUEST, BAD_REQUEST, false);
            return;
        }
        if (req.uri().equals(webSocketPath)) {
            // Balances SimpleChannelInboundHandler's release; the handshaker releases it after.
            ctx.fireChannelRead(req.retain());
            return;
        }
        String path = new QueryStringDecoder(req.uri()).path();
        if (HEALTH_PATH.equals(path) && status != null) {
            if (!HttpMethod.GET.equals(req.method())) {
                FullHttpResponse response = response(req, HttpResponseStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED);
                response.headers().set(HttpHeaderNames.ALLOW, HttpMethod.GET.name());
                write(ctx, response, HttpUtil.isKeepAlive(req));
                return;
            }
            health(ctx, req);
            return;
        }
        send(ctx, req, HttpResponseStatus.NOT_FOUND, NOT_FOUND, HttpUtil.isKeepAlive(req));
    }

    /**
     * The lagging counter of each pair is read first: recorded before published, so the body never
     * shows recorded above published, and applied before journaled, which narrows (but cannot
     * close) the transient {@link VenueStatus} describes.
     */
    private static void putDate(ObjectNode node, String field, int date) {
        if (date < 0) {
            node.putNull(field);
        } else {
            node.put(field, date);
        }
    }

    private void health(ChannelHandlerContext ctx, FullHttpRequest req) {
        boolean running = status.running();
        long applied = status.appliedSeq();
        long journaled = status.journaledSeq();
        long recorded = status.recordedSeq();
        long published = status.publishedSeq();
        long session = status.session();   // one read: the state and its date belong together

        ObjectNode body = objectMapper.createObjectNode();
        body.put("running", running);
        body.put("session", VenueStatus.isSessionOpen(session) ? "OPEN" : "CLOSED");
        // The open day while OPEN, the last closed day while CLOSED, null if there has been none (P15-8).
        int businessDate = VenueStatus.sessionDate(session);
        if (businessDate < 0) {
            body.putNull("businessDate");
        } else {
            body.put("businessDate", businessDate);
        }
        body.put("appliedInboundSeq", applied);
        body.put("journaledInboundSeq", journaled);
        body.put("recordedOutboundSeq", recorded);
        body.put("publishedOutboundSeq", published);
        // Additive, absent without a database. Reporting never changes the venue's own 200 or 503 (SRS 5.9).
        ReporterStatus reporter = status.reporter();
        if (reporter != null) {
            ObjectNode node = body.putObject("reporter");
            long outbound = reporter.outboundSeq();
            node.put("state", reporter.state());
            node.put("inboundSeq", reporter.inboundSeq());
            node.put("outboundSeq", outbound);
            node.put("outboundLag", Math.max(0L, recorded - outbound));
        }
        // Additive and absent without a database, like the Reporter's (P15-11).
        ClearingStatus clearing = status.clearing();
        if (clearing != null) {
            ObjectNode node = body.putObject("clearing");
            node.put("state", clearing.state());
            putDate(node, "lastClearedDate", clearing.lastClearedDate());
            putDate(node, "pendingDate", clearing.pendingDate());
            String heldBy = clearing.heldBy();
            if (heldBy == null) {
                node.putNull("heldBy");
            } else {
                node.put("heldBy", heldBy);
            }
        }

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize the health body", e);
        }
        FullHttpResponse response = response(req,
                running ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE, bytes);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE);
        write(ctx, response, HttpUtil.isKeepAlive(req));
    }

    private static void send(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus code,
                             byte[] body, boolean keepAlive) {
        write(ctx, response(req, code, body), keepAlive);
    }

    private static FullHttpResponse response(FullHttpRequest req, HttpResponseStatus code, byte[] body) {
        ByteBuf content = Unpooled.wrappedBuffer(body);
        HttpVersion version = req.protocolVersion();
        FullHttpResponse response = new DefaultFullHttpResponse(version, code, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, JSON);
        HttpUtil.setContentLength(response, content.readableBytes());
        return response;
    }

    private static void write(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        HttpUtil.setKeepAlive(response, keepAlive);
        ChannelFuture written = ctx.writeAndFlush(response);
        if (!keepAlive) {
            written.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ChannelErrors.closeOnException(ctx, cause, log);
    }
}
