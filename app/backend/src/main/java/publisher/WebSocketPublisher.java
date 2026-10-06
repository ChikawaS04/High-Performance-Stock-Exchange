package publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lmax.disruptor.EventHandler;
import event.BookSnapshotEvent;
import event.ExecutionEvent;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import util.BookFrameCache;

/**
 * WebSocket fan-out publisher (SRS §3.5 / §3.6). Serves the two outbound streams to every
 * connected React client as typed JSON text frames:
 *
 * <ul>
 *   <li>{@code EXEC} frames from {@link ExecutionEvent} on the outbound ring, and</li>
 *   <li>{@code BOOK} frames from {@link BookSnapshotEvent} on the snapshot ring.</li>
 * </ul>
 *
 * <p><b>Two roles, two objects (erasure).</b> A single class cannot implement both
 * {@code EventHandler<ExecutionEvent>} and {@code EventHandler<BookSnapshotEvent>} — the same
 * generic interface twice is illegal under erasure. So the publisher exposes two stored
 * handlers via {@link #executionHandler()} and {@link #snapshotHandler()} (each a
 * method-reference allocated once at construction, never per message). P4-7 registers them on
 * the {@code OutboundPipeline} / {@code SnapshotPipeline} respectively. They run on two
 * <i>different</i> Disruptor consumer threads, so there is no shared mutable serialization
 * scratch — each {@code onEvent} builds a fresh node tree and {@code String}, and
 * {@link ObjectMapper} is thread-safe.
 *
 * <p><b>Slot discipline (guide decision 5).</b> Each {@code onEvent} serializes the reused
 * carrier to a {@code String} <i>fully inside the call</i>, before the slot can advance, then
 * hands a {@link TextWebSocketFrame} (which owns only the {@code String}) to
 * {@link ChannelGroup#writeAndFlush}. The mutable slot is never retained past {@code onEvent}.
 * Fan-out via the group is safe from the consumer thread — Netty marshals each write onto that
 * channel's own event loop.
 *
 * <p><b>Wire format (guide decision 4).</b> Integer cents only; price formatting is React's job
 * at the UI boundary (§4). {@code execType} is the {@link event.ExecutionEventType} name.
 * BOOK frames serialize <i>only</i> the valid {@code [0, levelCount)} prefix of each side, per
 * the {@link BookSnapshotEvent} count-authoritative reuse contract — array tails past the count
 * are stale and must never be read.
 *
 * <p><b>Boundary layer.</b> This is a system edge (§5.5), so Jackson + {@code String} +
 * {@code TextWebSocketFrame} allocation past the outbound ring is fine (§5.1), and it may import
 * {@code com.lmax} / {@code io.netty} like the other handler-layer classes. Logging lives here at
 * the dispatch boundary (§5.4).
 */
public final class WebSocketPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPublisher.class);

    private final ChannelGroup channelGroup;
    private final ObjectMapper mapper;

    /** Shared latest-BOOK cache for snapshot-on-connect; null when unwired (test seam). */
    private final BookFrameCache bookCache;

    /** Allocated once here — not per message. Handed to the pipelines in P4-7. */
    private final EventHandler<ExecutionEvent> executionHandler = this::onExecution;
    private final EventHandler<BookSnapshotEvent> snapshotHandler = this::onSnapshot;

    /** Production entry point with no book cache: the publisher owns its own {@link ObjectMapper}. */
    public WebSocketPublisher(ChannelGroup channelGroup) {
        this(channelGroup, null, new ObjectMapper());
    }

    /**
     * Production entry point with the shared {@link BookFrameCache}: every BOOK frame this publisher
     * emits is also stashed in the cache so a newly connected client can be sent the current depth
     * on connect (see {@code net.WebSocketFrameHandler}).
     */
    public WebSocketPublisher(ChannelGroup channelGroup, BookFrameCache bookCache) {
        this(channelGroup, bookCache, new ObjectMapper());
    }

    /** Test / reuse seam: inject a shared, pre-configured {@link ObjectMapper}; no book cache. */
    WebSocketPublisher(ChannelGroup channelGroup, ObjectMapper mapper) {
        this(channelGroup, null, mapper);
    }

    /** Full seam: both the book cache and the mapper are injected. */
    WebSocketPublisher(ChannelGroup channelGroup, BookFrameCache bookCache, ObjectMapper mapper) {
        this.channelGroup = channelGroup;
        this.bookCache = bookCache;
        this.mapper = mapper;
    }

    /** Register on the outbound (execution) ring — emits {@code EXEC} frames. */
    public EventHandler<ExecutionEvent> executionHandler() {
        return executionHandler;
    }

    /** Register on the snapshot ring — emits {@code BOOK} frames. */
    public EventHandler<BookSnapshotEvent> snapshotHandler() {
        return snapshotHandler;
    }

    // --- consumer-thread event handlers -------------------------------------------------

    private void onExecution(ExecutionEvent event, long sequence, boolean endOfBatch) {
        if (channelGroup.isEmpty()) {
            return; // no clients — skip serialization entirely
        }
        final String json;
        try {
            json = serializeExecution(event);
        } catch (RuntimeException ex) {
            log.warn("EXEC serialization failed; dropping frame", ex);
            return;
        }
        channelGroup.writeAndFlush(new TextWebSocketFrame(json));
        log.debug("dispatched EXEC frame to {} client(s)", channelGroup.size());
    }

    private void onSnapshot(BookSnapshotEvent event, long sequence, boolean endOfBatch) {
        // With no cache wired (test seam / pre-snapshot-on-connect), keep the original
        // optimization: skip serialization entirely when no client is listening. With a cache,
        // serialize even for zero clients so the cache stays current for snapshot-on-connect.
        if (channelGroup.isEmpty() && bookCache == null) {
            return;
        }
        final String json;
        try {
            json = serializeSnapshot(event);
        } catch (RuntimeException ex) {
            log.warn("BOOK serialization failed; dropping frame", ex);
            return;
        }
        // Cache the latest book for snapshot-on-connect, independent of the current client count: a
        // client that connects later must be handed the TRUE current book, not whatever was last
        // broadcast. Order flow in this venue originates from clients, so a snapshot serialized with
        // zero clients attached (the only extra work this adds) is rare.
        if (bookCache != null) {
            bookCache.set(json);
        }
        if (channelGroup.isEmpty()) {
            return; // no clients to broadcast to; the cache above is already current
        }
        channelGroup.writeAndFlush(new TextWebSocketFrame(json));
        log.debug("dispatched BOOK frame to {} client(s)", channelGroup.size());
    }

    // --- serialization (package-private for direct unit testing) ------------------------

    String serializeExecution(ExecutionEvent e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "EXEC");
        n.put("execType", e.eventType.name());
        n.put("orderId", e.orderId);
        n.put("tradeId", e.tradeId);
        n.put("price", e.price);
        n.put("filledQuantity", e.filledQuantity);
        n.put("remainingQuantity", e.remainingQuantity);
        n.put("aggressorOrderId", e.aggressorOrderId);
        n.put("passiveOrderId", e.passiveOrderId);
        n.put("timestamp", e.timestamp);
        return write(n);
    }

    String serializeSnapshot(BookSnapshotEvent s) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "BOOK");
        n.put("bestBid", s.bestBid);
        n.put("bestAsk", s.bestAsk);

        // Only [0, levelCount) is valid — counts are authoritative, tails are stale.
        ArrayNode bids = n.putArray("bids");
        for (int i = 0; i < s.bidLevelCount; i++) {
            ArrayNode level = bids.addArray();
            level.add(s.bidPrices[i]);
            level.add(s.bidQtys[i]);
        }
        ArrayNode asks = n.putArray("asks");
        for (int i = 0; i < s.askLevelCount; i++) {
            ArrayNode level = asks.addArray();
            level.add(s.askPrices[i]);
            level.add(s.askQtys[i]);
        }

        n.put("timestamp", s.timestamp);
        return write(n);
    }

    private String write(ObjectNode n) {
        try {
            return mapper.writeValueAsString(n);
        } catch (JsonProcessingException ex) {
            // Serializing a plain in-memory node tree effectively never fails; surface it as
            // an unchecked fault so the onEvent boundary can log-and-drop this one frame.
            throw new IllegalStateException("JSON serialization failed", ex);
        }
    }
}