package codec;

import event.OrderEvent;
import event.OrderEventType;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Hand-written adapter between {@link OrderEvent} and the SBE command messages of the
 * journal (SRS §3.8). One of the D7 copy points: every OrderEvent field is either carried
 * by the message for its command type or reset on decode, and CodecFieldGuardTest fails
 * if a field is added to OrderEvent without being handled here.
 *
 * The command type is the SBE template id: NEW_ORDER is NewOrderCommand, CANCEL_ORDER is
 * CancelOrderCommand, SESSION_OPEN and SESSION_CLOSE are the session commands. The inbound
 * seq is not an OrderEvent field (SRS §3.2), so it is passed to encode and returned by
 * decode.
 *
 * Zero allocation: the generated encoders and decoders are flyweights held in final fields
 * and re-wrapped per call over a buffer the caller owns. Enum mapping is by switch, never
 * values(). Not thread-safe: one instance per thread (the single writer of a stream, or
 * its single reader). The only allocation is the exception thrown for a message from
 * another schema or an unknown template, which means a corrupt journal, never steady state.
 */
public final class OrderEventCodec {

    /** Largest encoded command, header included: the size a caller's buffer must have. */
    public static final int MAX_ENCODED_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH + Math.max(
            Math.max(NewOrderCommandEncoder.BLOCK_LENGTH, CancelOrderCommandEncoder.BLOCK_LENGTH),
            Math.max(SessionOpenCommandEncoder.BLOCK_LENGTH, SessionCloseCommandEncoder.BLOCK_LENGTH));

    private final MessageHeaderEncoder        headerEncoder       = new MessageHeaderEncoder();
    private final MessageHeaderDecoder        headerDecoder       = new MessageHeaderDecoder();
    private final NewOrderCommandEncoder      newOrderEncoder     = new NewOrderCommandEncoder();
    private final NewOrderCommandDecoder      newOrderDecoder     = new NewOrderCommandDecoder();
    private final CancelOrderCommandEncoder   cancelEncoder       = new CancelOrderCommandEncoder();
    private final CancelOrderCommandDecoder   cancelDecoder       = new CancelOrderCommandDecoder();
    private final SessionOpenCommandEncoder   sessionOpenEncoder  = new SessionOpenCommandEncoder();
    private final SessionOpenCommandDecoder   sessionOpenDecoder  = new SessionOpenCommandDecoder();
    private final SessionCloseCommandEncoder  sessionCloseEncoder = new SessionCloseCommandEncoder();
    private final SessionCloseCommandDecoder  sessionCloseDecoder = new SessionCloseCommandDecoder();

    /**
     * Encodes {@code src} as the command message for its event type, header first.
     *
     * @return the number of bytes written, header included
     */
    public int encode(OrderEvent src, long seq, MutableDirectBuffer buffer, int offset) {
        switch (src.eventType) {
            case NEW_ORDER:
                newOrderEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(seq)
                        .timestamp(src.timestamp)
                        .orderId(src.orderId)
                        .price(src.price)
                        .quantity(src.quantity)
                        .maxFloor(src.maxFloor)
                        .side(toCode(src.side))
                        .ordType(toCode(src.ordType))
                        .tif(toCode(src.tif));
                return MessageHeaderEncoder.ENCODED_LENGTH + newOrderEncoder.encodedLength();
            case CANCEL_ORDER:
                cancelEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(seq)
                        .timestamp(src.timestamp)
                        .orderId(src.orderId)
                        .originalOrderId(src.originalOrderId);
                return MessageHeaderEncoder.ENCODED_LENGTH + cancelEncoder.encodedLength();
            case SESSION_OPEN:
                sessionOpenEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(seq)
                        .timestamp(src.timestamp)
                        .tickSize(src.tickSize)
                        .lotSize(src.lotSize)
                        .priceScale(src.priceScale)
                        .businessDate(src.businessDate)
                        .putSymbol(src.symbol, 0);
                return MessageHeaderEncoder.ENCODED_LENGTH + sessionOpenEncoder.encodedLength();
            case SESSION_CLOSE:
                sessionCloseEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(seq)
                        .timestamp(src.timestamp)
                        .businessDate(src.businessDate);
                return MessageHeaderEncoder.ENCODED_LENGTH + sessionCloseEncoder.encodedLength();
            default:
                throw new IllegalArgumentException("Unencodable event type: " + src.eventType);
        }
    }

    /**
     * Decodes one command into {@code dst}, writing every field: the message's own fields,
     * and the "does not apply" value for every other field (the same values the FIX parser
     * writes), so no value from a previous use of {@code dst} survives.
     *
     * @return the inbound seq carried by the command
     */
    public long decode(DirectBuffer buffer, int offset, OrderEvent dst) {
        headerDecoder.wrap(buffer, offset);
        final int schemaId = headerDecoder.schemaId();
        if (schemaId != MessageHeaderDecoder.SCHEMA_ID) {
            throw new IllegalStateException("Foreign schema id: " + schemaId);
        }
        final int bodyOffset  = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int blockLength = headerDecoder.blockLength();
        final int version     = headerDecoder.version();

        switch (headerDecoder.templateId()) {
            case NewOrderCommandDecoder.TEMPLATE_ID: {
                NewOrderCommandDecoder d = newOrderDecoder.wrap(buffer, bodyOffset, blockLength, version);
                dst.eventType       = OrderEventType.NEW_ORDER;
                dst.orderId         = d.orderId();
                dst.side            = toSide(d.side());
                dst.ordType         = toOrdType(d.ordType());
                dst.tif             = toTif(d.tif());
                dst.price           = d.price();
                dst.quantity        = d.quantity();
                dst.maxFloor        = d.maxFloor();
                dst.timestamp       = d.timestamp();
                dst.originalOrderId = -1L;
                dst.clearSessionFields();
                return d.seq();
            }
            case CancelOrderCommandDecoder.TEMPLATE_ID: {
                CancelOrderCommandDecoder d = cancelDecoder.wrap(buffer, bodyOffset, blockLength, version);
                dst.eventType       = OrderEventType.CANCEL_ORDER;
                dst.orderId         = d.orderId();
                dst.originalOrderId = d.originalOrderId();
                dst.timestamp       = d.timestamp();
                clearOrderFields(dst);
                dst.clearSessionFields();
                return d.seq();
            }
            case SessionOpenCommandDecoder.TEMPLATE_ID: {
                SessionOpenCommandDecoder d = sessionOpenDecoder.wrap(buffer, bodyOffset, blockLength, version);
                dst.eventType       = OrderEventType.SESSION_OPEN;
                dst.timestamp       = d.timestamp();
                dst.businessDate    = d.businessDate();
                d.getSymbol(dst.symbol, 0);
                dst.tickSize        = d.tickSize();
                dst.lotSize         = d.lotSize();
                dst.priceScale      = d.priceScale();
                dst.orderId         = -1L;
                dst.originalOrderId = -1L;
                clearOrderFields(dst);
                return d.seq();
            }
            case SessionCloseCommandDecoder.TEMPLATE_ID: {
                SessionCloseCommandDecoder d = sessionCloseDecoder.wrap(buffer, bodyOffset, blockLength, version);
                dst.eventType       = OrderEventType.SESSION_CLOSE;
                dst.timestamp       = d.timestamp();
                dst.orderId         = -1L;
                dst.originalOrderId = -1L;
                clearOrderFields(dst);
                dst.clearSessionFields();
                dst.businessDate    = d.businessDate();
                return d.seq();
            }
            default:
                throw new IllegalStateException("Unknown command template id: " + headerDecoder.templateId());
        }
    }

    /** The NEW-only fields, reset exactly as the FIX parser's CANCEL branch resets them. */
    private static void clearOrderFields(OrderEvent dst) {
        dst.side     = null;
        dst.ordType  = null;
        dst.tif      = null;
        dst.price    = -1L;
        dst.quantity = -1L;
        dst.maxFloor = 0L;
    }

    // --- enum mapping: null <-> NULL_VAL, by switch so nothing allocates ---

    static SideCode toCode(Side side) {
        if (side == null) return SideCode.NULL_VAL;
        switch (side) {
            case BUY:  return SideCode.BUY;
            case SELL: return SideCode.SELL;
            default:   throw new IllegalArgumentException("Unmapped side: " + side);
        }
    }

    static Side toSide(SideCode code) {
        switch (code) {
            case BUY:  return Side.BUY;
            case SELL: return Side.SELL;
            default:   return null;   // NULL_VAL
        }
    }

    static OrdTypeCode toCode(OrdType ordType) {
        if (ordType == null) return OrdTypeCode.NULL_VAL;
        switch (ordType) {
            case LIMIT:   return OrdTypeCode.LIMIT;
            case PEG_MID: return OrdTypeCode.PEG_MID;
            default:      throw new IllegalArgumentException("Unmapped ordType: " + ordType);
        }
    }

    static OrdType toOrdType(OrdTypeCode code) {
        switch (code) {
            case LIMIT:   return OrdType.LIMIT;
            case PEG_MID: return OrdType.PEG_MID;
            default:      return null;   // NULL_VAL
        }
    }

    static TimeInForceCode toCode(TimeInForce tif) {
        if (tif == null) return TimeInForceCode.NULL_VAL;
        switch (tif) {
            case GTC: return TimeInForceCode.GTC;
            case IOC: return TimeInForceCode.IOC;
            case FOK: return TimeInForceCode.FOK;
            default:  throw new IllegalArgumentException("Unmapped tif: " + tif);
        }
    }

    static TimeInForce toTif(TimeInForceCode code) {
        switch (code) {
            case GTC: return TimeInForce.GTC;
            case IOC: return TimeInForce.IOC;
            case FOK: return TimeInForce.FOK;
            default:  return null;   // NULL_VAL
        }
    }
}
