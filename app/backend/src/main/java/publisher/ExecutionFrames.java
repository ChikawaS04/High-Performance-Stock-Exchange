package publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import event.ExecutionEvent;
import event.ExecutionEventType;

/**
 * The one serializer of an {@link ExecutionEvent} into its JSON wire frame (SRS §3.6). The live
 * {@link WebSocketPublisher} and RESEND both use it, so a resent frame is byte-identical to the
 * original, seq included. Session events become SESSION frames here (D5, P15-8), so resend
 * carries them too.
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

    /**
     * The wire frame for {@code e}: a SESSION frame for SESSION_OPENED and SESSION_CLOSED (D5,
     * SRS §3.6), an EXEC frame for every other type. No session event ever becomes an EXEC frame.
     */
    public String toJson(ExecutionEvent e) {
        if (e.eventType == ExecutionEventType.SESSION_OPENED || e.eventType == ExecutionEventType.SESSION_CLOSED) {
            return sessionJson(e);
        }
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
        // An ACCEPTED names the resting order's side under "side" instead, so "aggressorSide" keeps
        // meaning the aggressor on the wire and another participant can place the order in the book.
        boolean accepted = e.eventType == ExecutionEventType.ORDER_ACCEPTED;
        n.put("aggressorSide", (accepted || e.aggressorSide == null) ? null : e.aggressorSide.name());
        if (accepted) {
            n.put("side", (e.aggressorSide == null) ? null : e.aggressorSide.name());
        }
        n.put("aggressorParticipantId", e.aggressorParticipantId);
        n.put("passiveParticipantId", e.passiveParticipantId);
        n.put("timestamp", e.timestamp);
        return write(n);
    }

    /** {@code {"type":"SESSION","seq":N,"state":"OPEN"|"CLOSED","businessDate":D,"timestamp":T}} (SRS §3.6). */
    private String sessionJson(ExecutionEvent e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "SESSION");
        n.put("seq", e.seq);
        n.put("state", e.eventType == ExecutionEventType.SESSION_OPENED ? "OPEN" : "CLOSED");
        n.put("businessDate", e.businessDate);
        n.put("timestamp", e.timestamp);
        return write(n);
    }

    private String write(ObjectNode n) {
        try {
            return mapper.writeValueAsString(n);
        } catch (JsonProcessingException ex) {
            // Serializing a plain in-memory node tree effectively never fails.
            throw new IllegalStateException("JSON serialization failed", ex);
        }
    }
}
