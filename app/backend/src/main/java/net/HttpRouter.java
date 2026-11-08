package net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import http.ApiHandler;
import http.ApiRequest;
import http.ApiResponse;
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
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;

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
 *   <li>{@code /api} and every path under it go to the {@link ApiHandler} (P15-12): a route that is
 *       not one of its own is a 404, another method on a route a 405 with {@code Allow: GET}. With no
 *       handler (no database configured) all of {@code /api} is a 404, as {@code /health} is with no
 *       status.</li>
 *   <li>Anything else gets 404, including a path of {@code /ws} with any other URI (such as
 *       {@code /ws?x=1}), which never upgraded and used to get no answer.</li>
 * </ul>
 *
 * <b>Order of answers (P15-12).</b> An {@code /api} GET is answered later, from the api executor, so
 * a keep-alive client that pipelines a second request could otherwise get the answers out of order.
 * While an {@code /api} answer is outstanding the router stops reading the channel and holds any
 * request already decoded (at most {@value #MAX_HELD}, beyond which it closes the connection); when
 * the answer is written it handles the held requests in arrival order, then reads again. The
 * request object never leaves the event loop: its path and parameters are copied, and the answer
 * comes back as bytes that the loop turns into a response.
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

    static final String API_PATH = "/api";
    /** Requests held behind an outstanding /api answer on one channel, at most. */
    static final int MAX_HELD = 16;

    private final String webSocketPath;
    private final VenueStatus status;
    private final ObjectMapper objectMapper;
    private final ApiHandler api;

    /** True while an /api answer for this channel is outstanding; event loop only. */
    private boolean awaitingApi;
    /** Requests decoded while {@link #awaitingApi}, each retained; event loop only. */
    private final ArrayDeque<FullHttpRequest> held = new ArrayDeque<>();

    /**
     * @param webSocketPath the path the WebSocket protocol handler upgrades
     * @param status        what {@code /health} reports; null means the server serves no
     *                      {@code /health} route, and it answers 404
     */
    public HttpRouter(String webSocketPath, VenueStatus status, ObjectMapper objectMapper) {
        this(webSocketPath, status, objectMapper, null);
    }

    /**
     * @param api the {@code /api} routes; null means the server serves none, and all of {@code /api} is a 404
     */
    public HttpRouter(String webSocketPath, VenueStatus status, ObjectMapper objectMapper, ApiHandler api) {
        this.webSocketPath = webSocketPath;
        this.status = status;
        this.objectMapper = objectMapper;
        this.api = api;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (awaitingApi) {
            if (held.size() >= MAX_HELD) {
                log.warn("Closing {}: more than {} requests pipelined behind an /api answer",
                        ctx.channel().remoteAddress(), MAX_HELD);
                ctx.close();
                return;
            }
            held.add(req.retain());   // released after it is handled, or when the channel goes
            return;
        }
        route(ctx, req);
    }

    private void route(ChannelHandlerContext ctx, FullHttpRequest req) {
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
        if (api != null && (API_PATH.equals(path) || path.startsWith(API_PATH + "/"))) {
            api(ctx, req, path);
            return;
        }
        send(ctx, req, HttpResponseStatus.NOT_FOUND, NOT_FOUND, HttpUtil.isKeepAlive(req));
    }

    private void api(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (!api.isRoute(path)) {
            send(ctx, req, HttpResponseStatus.NOT_FOUND, NOT_FOUND, keepAlive);
            return;
        }
        if (!HttpMethod.GET.equals(req.method())) {
            FullHttpResponse response = response(req, HttpResponseStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED);
            response.headers().set(HttpHeaderNames.ALLOW, HttpMethod.GET.name());
            write(ctx, response, keepAlive);
            return;
        }
        HttpVersion version = req.protocolVersion();
        ApiRequest request = new ApiRequest(path, new QueryStringDecoder(req.uri()).parameters());
        awaitingApi = true;
        ApiResponse now = api.handle(request, answer -> answerFromExecutor(ctx, version, keepAlive, answer));
        if (now != null) {
            answerApi(ctx, version, keepAlive, now);
        } else {
            ctx.channel().config().setAutoRead(false);   // nothing more is read until the answer is written
        }
    }

    /** Called on an api executor thread: the answer is handed to this channel's event loop. */
    private void answerFromExecutor(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive,
                                    ApiResponse answer) {
        try {
            ctx.executor().execute(() -> answerApi(ctx, version, keepAlive, answer));
        } catch (RejectedExecutionException e) {
            // The event loop has shut down (server stop): the channel is gone with it.
        }
    }

    /** On the event loop: writes the /api answer, then handles what was held behind it. */
    private void answerApi(ChannelHandlerContext ctx, HttpVersion version, boolean keepAlive, ApiResponse answer) {
        awaitingApi = false;
        if (!ctx.channel().isActive()) {
            releaseHeld();
            return;
        }
        ByteBuf content = Unpooled.wrappedBuffer(answer.body());
        FullHttpResponse response = new DefaultFullHttpResponse(version, HttpResponseStatus.valueOf(answer.status()),
                content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, JSON);
        HttpUtil.setContentLength(response, content.readableBytes());
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE);
        if (answer.retryAfterSeconds() > 0) {
            response.headers().set(HttpHeaderNames.RETRY_AFTER, answer.retryAfterSeconds());
        }
        write(ctx, response, keepAlive);
        if (!keepAlive) {
            releaseHeld();   // the channel closes after this write; nothing after it is answered
            return;
        }
        FullHttpRequest next;
        while (!awaitingApi && (next = held.poll()) != null) {
            try {
                route(ctx, next);
            } finally {
                next.release();
            }
        }
        if (!awaitingApi) {
            ctx.channel().config().setAutoRead(true);
        }
    }

    private void releaseHeld() {
        FullHttpRequest r;
        while ((r = held.poll()) != null) {
            r.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseHeld();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseHeld();
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
