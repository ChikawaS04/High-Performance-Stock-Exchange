package recovery;

import codec.snapshot.EngineSnapshotDecoder;
import codec.snapshot.EngineSnapshotEncoder;
import codec.snapshot.LocationCode;
import codec.snapshot.MessageHeaderDecoder;
import codec.snapshot.MessageHeaderEncoder;
import codec.snapshot.OrdTypeCode;
import codec.snapshot.RestingOrderDecoder;
import codec.snapshot.RestingOrderEncoder;
import codec.snapshot.SideCode;
import codec.snapshot.StatusCode;
import codec.snapshot.TimeInForceCode;
import engine.MatchingEngine;
import engine.RestingOrderVisitor;
import model.OrdType;
import model.Order;
import model.Side;
import model.Status;
import model.TimeInForce;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Encodes the engine's complete state as a snapshot body and decodes it back (P15-5, D14,
 * schema {@code snapshot-schema.xml}): one {@code EngineSnapshot} message, then one
 * {@code RestingOrder} message per resting order in restore order. The file trailer (length and
 * checksum) belongs to {@link SnapshotStore}.
 *
 * Encode runs on the engine thread, between two commands, so the state is the consistent cut
 * after one inbound seq. It writes the orders first and the header last, so the order count is
 * whatever was actually written. It reuses flyweights and one visitor; the only allocation is
 * the book's own iterators (the same as {@code snapshotInto}) and the buffer growing past its
 * initial size. Not thread-safe: one instance per thread that encodes.
 *
 * Decode validates everything before returning, and builds every {@link Order} through
 * {@link Order#restore}, so a snapshot that decodes is one recovery can apply without failing.
 */
public final class SnapshotCodec {

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final EngineSnapshotEncoder snapshotEncoder = new EngineSnapshotEncoder();
    private final RestingOrderEncoder orderEncoder = new RestingOrderEncoder();
    private final OrderWriter orderWriter = new OrderWriter();

    /** Bytes the header message occupies; the first order starts here. */
    static final int HEADER_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH + EngineSnapshotEncoder.BLOCK_LENGTH;
    /** Bytes each order occupies. */
    static final int ORDER_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH + RestingOrderEncoder.BLOCK_LENGTH;

    /**
     * Writes the snapshot body for the engine's current state into {@code buffer} from offset 0.
     * {@code buffer} must grow on demand (an expandable buffer) or be large enough.
     *
     * @return the body length in bytes
     */
    public int encode(MutableDirectBuffer buffer, MatchingEngine engine, long inboundSeq, long inboundPosition,
                      long inboundRecordingId, long outboundLastSeq, long lastCommandTimestamp) {
        orderWriter.start(buffer, HEADER_LENGTH);
        engine.visitResting(orderWriter);
        snapshotEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .inboundSeq(inboundSeq)
                .inboundPosition(inboundPosition)
                .inboundRecordingId(inboundRecordingId)
                .outboundLastSeq(outboundLastSeq)
                .nextTradeId(engine.getNextTradeId())
                .lastCommandTimestamp(lastCommandTimestamp)
                .orderCount(orderWriter.count);
        return orderWriter.offset;
    }

    /** Writes one RestingOrder message per visited order, advancing an offset. */
    private final class OrderWriter implements RestingOrderVisitor {
        private MutableDirectBuffer buffer;
        private int offset;
        private long count;

        void start(MutableDirectBuffer buffer, int offset) {
            this.buffer = buffer;
            this.offset = offset;
            this.count = 0;
        }

        @Override
        public void onRestingOrder(Order o, boolean pegged) {
            orderEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
                    .orderId(o.getOrderID())
                    .timeStamp(o.getTimeStamp())
                    .participantId(o.getParticipantID())
                    .price(o.getPrice())
                    .quantity(o.getQuantity())
                    .peak(o.getPeak())
                    .visibleQty(o.getVisibleQty())
                    .location(location(o.getSide(), pegged))
                    .side(o.getSide() == Side.BUY ? SideCode.BUY : SideCode.SELL)
                    .status(toCode(o.getStatus()))
                    .tif(toCode(o.getTimeInForce()))
                    .ordType(o.getOrdType() == OrdType.PEG_MID ? OrdTypeCode.PEG_MID : OrdTypeCode.LIMIT);
            offset += ORDER_LENGTH;
            count++;
        }
    }

    /**
     * Decodes and validates a snapshot body of exactly {@code length} bytes.
     *
     * @throws InvalidSnapshotException naming the first reason the body cannot be used, including
     *                                  an enum value this build does not know
     */
    public static RestoredSnapshot decode(DirectBuffer buffer, int length) throws InvalidSnapshotException {
        try {
            return decodeChecked(buffer, length);
        } catch (RuntimeException e) {
            throw new InvalidSnapshotException("undecodable: " + e, e);
        }
    }

    private static RestoredSnapshot decodeChecked(DirectBuffer buffer, int length) throws InvalidSnapshotException {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        if (length < HEADER_LENGTH) {
            throw new InvalidSnapshotException("body of " + length + " bytes is shorter than the snapshot header");
        }
        header.wrap(buffer, 0);
        checkHeader(header, EngineSnapshotDecoder.TEMPLATE_ID, EngineSnapshotDecoder.BLOCK_LENGTH);
        EngineSnapshotDecoder snapshot = new EngineSnapshotDecoder()
                .wrap(buffer, MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());

        long orderCount = snapshot.orderCount();
        int ordersStart = MessageHeaderDecoder.ENCODED_LENGTH + header.blockLength();
        if (orderCount < 0 || orderCount > (length - ordersStart) / ORDER_LENGTH) {
            throw new InvalidSnapshotException("order count " + orderCount + " cannot fit a body of "
                    + length + " bytes");
        }
        if (snapshot.inboundSeq() < 0 || snapshot.inboundPosition() < 0 || snapshot.outboundLastSeq() < 0
                || snapshot.nextTradeId() < 1) {
            throw new InvalidSnapshotException("negative seq or position, or trade id below 1");
        }

        List<RestoredSnapshot.RestoredOrder> orders = new ArrayList<>((int) orderCount);
        Set<Long> ids = new HashSet<>();
        RestingOrderDecoder order = new RestingOrderDecoder();
        int offset = ordersStart;
        LocationCode previous = LocationCode.BID;
        for (long i = 0; i < orderCount; i++) {
            if (offset + ORDER_LENGTH > length) {
                throw new InvalidSnapshotException("order " + i + " runs past the end of the body");
            }
            header.wrap(buffer, offset);
            checkHeader(header, RestingOrderDecoder.TEMPLATE_ID, RestingOrderDecoder.BLOCK_LENGTH);
            if (offset + MessageHeaderDecoder.ENCODED_LENGTH + header.blockLength() > length) {
                throw new InvalidSnapshotException("order " + i + " runs past the end of the body");
            }
            order.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());
            offset += MessageHeaderDecoder.ENCODED_LENGTH + header.blockLength();

            LocationCode location = order.location();
            if (location == LocationCode.NULL_VAL || location.ordinal() < previous.ordinal()) {
                throw new InvalidSnapshotException("order " + i + " is out of restore order: " + location);
            }
            previous = location;
            boolean pegged = location == LocationCode.PEG_BID || location == LocationCode.PEG_ASK;
            Side side = toSide(order.side());
            Side locationSide = (location == LocationCode.BID || location == LocationCode.PEG_BID) ? Side.BUY : Side.SELL;
            if (side != locationSide) {
                throw new InvalidSnapshotException("order " + order.orderId() + " has side " + side + " in " + location);
            }
            OrdType ordType = toOrdType(order.ordType());
            if (ordType != null && (ordType == OrdType.PEG_MID) != pegged) {
                throw new InvalidSnapshotException("order " + order.orderId() + " of type " + ordType + " in " + location);
            }
            Order restored;
            try {
                restored = Order.restore(order.orderId(), order.timeStamp(), side, order.quantity(), order.price(),
                        toStatus(order.status()), order.participantId(), toTif(order.tif()), order.peak(),
                        order.visibleQty(), ordType);
            } catch (IllegalArgumentException e) {
                throw new InvalidSnapshotException("order " + order.orderId() + ": " + e.getMessage(), e);
            }
            if (!ids.add(restored.getOrderID())) {
                throw new InvalidSnapshotException("duplicate order id " + restored.getOrderID());
            }
            orders.add(new RestoredSnapshot.RestoredOrder(restored, pegged));
        }
        if (offset != length) {
            throw new InvalidSnapshotException((length - offset) + " bytes follow the last order");
        }
        checkLitLevels(orders);

        return new RestoredSnapshot(snapshot.inboundSeq(), snapshot.inboundPosition(), snapshot.inboundRecordingId(),
                snapshot.outboundLastSeq(), snapshot.nextTradeId(), snapshot.lastCommandTimestamp(),
                List.copyOf(orders));
    }

    /** Lit levels must arrive best-first with each price level contiguous: bids descending, asks ascending. */
    private static void checkLitLevels(List<RestoredSnapshot.RestoredOrder> orders) throws InvalidSnapshotException {
        long previousPrice = 0;
        Side previousSide = null;
        for (RestoredSnapshot.RestoredOrder entry : orders) {
            if (entry.pegged()) continue;
            Order o = entry.order();
            if (o.getSide() == previousSide) {
                boolean ordered = (o.getSide() == Side.BUY) ? o.getPrice() <= previousPrice : o.getPrice() >= previousPrice;
                if (!ordered) {
                    throw new InvalidSnapshotException("lit " + o.getSide() + " levels out of price order at order "
                            + o.getOrderID());
                }
            }
            previousSide = o.getSide();
            previousPrice = o.getPrice();
        }
    }

    private static void checkHeader(MessageHeaderDecoder header, int templateId, int blockLength)
            throws InvalidSnapshotException {
        if (header.schemaId() != MessageHeaderDecoder.SCHEMA_ID) {
            throw new InvalidSnapshotException("foreign schema id " + header.schemaId());
        }
        if (header.version() > MessageHeaderDecoder.SCHEMA_VERSION) {
            throw new InvalidSnapshotException("snapshot version " + header.version()
                    + " is newer than this build's " + MessageHeaderDecoder.SCHEMA_VERSION);
        }
        if (header.templateId() != templateId) {
            throw new InvalidSnapshotException("template id " + header.templateId() + ", expected " + templateId);
        }
        if (header.blockLength() < blockLength) {
            throw new InvalidSnapshotException("block length " + header.blockLength() + " below " + blockLength);
        }
    }

    // --- enum mapping, by switch so encoding allocates nothing ---

    private static LocationCode location(Side side, boolean pegged) {
        if (side == Side.BUY) return pegged ? LocationCode.PEG_BID : LocationCode.BID;
        return pegged ? LocationCode.PEG_ASK : LocationCode.ASK;
    }

    private static StatusCode toCode(Status status) {
        switch (status) {
            case OPEN:             return StatusCode.OPEN;
            case PARTIALLY_FILLED: return StatusCode.PARTIALLY_FILLED;
            case FILLED:           return StatusCode.FILLED;
            case CANCELLED:        return StatusCode.CANCELLED;
            case EXPIRED:          return StatusCode.EXPIRED;
            default:               throw new IllegalArgumentException("Unmapped status: " + status);
        }
    }

    private static TimeInForceCode toCode(TimeInForce tif) {
        switch (tif) {
            case GTC: return TimeInForceCode.GTC;
            case IOC: return TimeInForceCode.IOC;
            case FOK: return TimeInForceCode.FOK;
            default:  throw new IllegalArgumentException("Unmapped tif: " + tif);
        }
    }

    private static Side toSide(SideCode code) {
        switch (code) {
            case BUY:  return Side.BUY;
            case SELL: return Side.SELL;
            default:   return null;
        }
    }

    private static Status toStatus(StatusCode code) {
        switch (code) {
            case OPEN:             return Status.OPEN;
            case PARTIALLY_FILLED: return Status.PARTIALLY_FILLED;
            case FILLED:           return Status.FILLED;
            case CANCELLED:        return Status.CANCELLED;
            case EXPIRED:          return Status.EXPIRED;
            default:               return null;
        }
    }

    private static TimeInForce toTif(TimeInForceCode code) {
        switch (code) {
            case GTC: return TimeInForce.GTC;
            case IOC: return TimeInForce.IOC;
            case FOK: return TimeInForce.FOK;
            default:  return null;
        }
    }

    private static OrdType toOrdType(OrdTypeCode code) {
        switch (code) {
            case LIMIT:   return OrdType.LIMIT;
            case PEG_MID: return OrdType.PEG_MID;
            default:      return null;
        }
    }
}
