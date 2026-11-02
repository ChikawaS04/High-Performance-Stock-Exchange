package publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import event.ExecutionEvent;

/**
 * The one serializer of an {@link ExecutionEvent} into its JSON wire frame (SRS §3.6). The live
 * {@link WebSocketPublisher} and RESEND both use it, so a resent frame is byte-identical to the
 * original, seq included. When a new frame shape for execution events arrives (SESSION, D5), it
 * lands here and resend follows.
 *
 * Boundary code: allocation per frame is fine (SRS §5.1). Thread-safe: {@link ObjectMapper} is,
 * and nothing else is shared.
 */
public final class ExecutionFrames {

    private final ObjectMapper mapper;

    public ExecutionFrames() {
        this(new ObjectMapper());
    }

    public ExecutionFrames(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The EXEC frame for {@code e}. */
    public String toJson(ExecutionEvent e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "EXEC");
        n.put("seq", e.seq);
        n.put("execType", e.eventType.name());
        n.put("orderId", e.orderId);
        n.put("tradeId", e.tradeId);
        n.put("price", e.price);
        n.put("filledQuantity", e.filledQuantity);
        n.put("remainingQuantity", e.remainingQuantity);
        n.put("aggressorOrderId", e.aggressorOrderId);
        n.put("passiveOrderId", e.passiveOrderId);
        // D4: fills name the aggressor's side and both participants; null and -1 otherwise.
        n.put("aggressorSide", (e.aggressorSide == null) ? null : e.aggressorSide.name());
        n.put("aggressorParticipantId", e.aggressorParticipantId);
        n.put("passiveParticipantId", e.passiveParticipantId);
        n.put("timestamp", e.timestamp);
        try {
            return mapper.writeValueAsString(n);
        } catch (JsonProcessingException ex) {
            // Serializing a plain in-memory node tree effectively never fails.
            throw new IllegalStateException("JSON serialization failed", ex);
        }
    }
}
