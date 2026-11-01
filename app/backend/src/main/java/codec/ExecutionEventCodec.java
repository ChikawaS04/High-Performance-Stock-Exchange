package codec;

import event.ExecutionEvent;
import event.ExecutionEventType;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Hand-written adapter between {@link ExecutionEvent} and the SBE ExecutionEventMessage of
 * the outbound journal (SRS §3.4, §3.9). Every ExecutionEvent field is carried, for every
 * event type; CodecFieldGuardTest fails if a field is added to ExecutionEvent without being
 * carried here.
 *
 * Zero allocation, flyweights over a caller-owned buffer, not thread-safe: the same
 * discipline as {@link OrderEventCodec}.
 */
public final class ExecutionEventCodec {

    /** Encoded size of every execution event, header included. */
    public static final int ENCODED_LENGTH =
            MessageHeaderEncoder.ENCODED_LENGTH + ExecutionEventMessageEncoder.BLOCK_LENGTH;

    private final MessageHeaderEncoder         headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder         headerDecoder = new MessageHeaderDecoder();
    private final ExecutionEventMessageEncoder encoder       = new ExecutionEventMessageEncoder();
    private final ExecutionEventMessageDecoder decoder       = new ExecutionEventMessageDecoder();

    /** @return the number of bytes written, header included */
    public int encode(ExecutionEvent src, MutableDirectBuffer buffer, int offset) {
        encoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                .seq(src.seq)
                .timestamp(src.timestamp)
                .orderId(src.orderId)
                .tradeId(src.tradeId)
                .price(src.price)
                .filledQuantity(src.filledQuantity)
                .remainingQuantity(src.remainingQuantity)
                .aggressorOrderId(src.aggressorOrderId)
                .passiveOrderId(src.passiveOrderId)
                .aggressorParticipantId(src.aggressorParticipantId)
                .passiveParticipantId(src.passiveParticipantId)
                .businessDate(src.businessDate)
                .eventType(toCode(src.eventType))
                .aggressorSide(OrderEventCodec.toCode(src.aggressorSide));
        return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
    }

    /** Decodes one execution event into {@code dst}, writing every field. */
    public void decode(DirectBuffer buffer, int offset, ExecutionEvent dst) {
        headerDecoder.wrap(buffer, offset);
        final int schemaId = headerDecoder.schemaId();
        if (schemaId != MessageHeaderDecoder.SCHEMA_ID) {
            throw new IllegalStateException("Foreign schema id: " + schemaId);
        }
        final int templateId = headerDecoder.templateId();
        if (templateId != ExecutionEventMessageDecoder.TEMPLATE_ID) {
            throw new IllegalStateException("Unknown execution template id: " + templateId);
        }
        ExecutionEventMessageDecoder d = decoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                headerDecoder.blockLength(), headerDecoder.version());

        dst.seq                    = d.seq();
        dst.eventType              = toType(d.eventType());
        dst.orderId                = d.orderId();
        dst.tradeId                = d.tradeId();
        dst.price                  = d.price();
        dst.filledQuantity         = d.filledQuantity();
        dst.remainingQuantity      = d.remainingQuantity();
        dst.aggressorOrderId       = d.aggressorOrderId();
        dst.passiveOrderId         = d.passiveOrderId();
        dst.aggressorSide          = OrderEventCodec.toSide(d.aggressorSide());
        dst.aggressorParticipantId = d.aggressorParticipantId();
        dst.passiveParticipantId   = d.passiveParticipantId();
        dst.businessDate           = d.businessDate();
        dst.timestamp              = d.timestamp();
    }

    // --- enum mapping: null <-> NULL_VAL, by switch so nothing allocates ---

    static ExecTypeCode toCode(ExecutionEventType type) {
        if (type == null) return ExecTypeCode.NULL_VAL;
        switch (type) {
            case ORDER_ACCEPTED:         return ExecTypeCode.ORDER_ACCEPTED;
            case ORDER_FILLED:           return ExecTypeCode.ORDER_FILLED;
            case ORDER_PARTIALLY_FILLED: return ExecTypeCode.ORDER_PARTIALLY_FILLED;
            case ORDER_CANCELLED:        return ExecTypeCode.ORDER_CANCELLED;
            case ORDER_REJECTED:         return ExecTypeCode.ORDER_REJECTED;
            case ORDER_EXPIRED:          return ExecTypeCode.ORDER_EXPIRED;
            case SESSION_OPENED:         return ExecTypeCode.SESSION_OPENED;
            case SESSION_CLOSED:         return ExecTypeCode.SESSION_CLOSED;
            default: throw new IllegalArgumentException("Unmapped execution type: " + type);
        }
    }

    static ExecutionEventType toType(ExecTypeCode code) {
        switch (code) {
            case ORDER_ACCEPTED:         return ExecutionEventType.ORDER_ACCEPTED;
            case ORDER_FILLED:           return ExecutionEventType.ORDER_FILLED;
            case ORDER_PARTIALLY_FILLED: return ExecutionEventType.ORDER_PARTIALLY_FILLED;
            case ORDER_CANCELLED:        return ExecutionEventType.ORDER_CANCELLED;
            case ORDER_REJECTED:         return ExecutionEventType.ORDER_REJECTED;
            case ORDER_EXPIRED:          return ExecutionEventType.ORDER_EXPIRED;
            case SESSION_OPENED:         return ExecutionEventType.SESSION_OPENED;
            case SESSION_CLOSED:         return ExecutionEventType.SESSION_CLOSED;
            default:                     return null;   // NULL_VAL
        }
    }
}
