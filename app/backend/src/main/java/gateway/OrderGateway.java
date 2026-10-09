package gateway;

import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.RingBuffer;
import event.OrderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * Inbound ring-buffer producer: the boundary between the FIX wire and the SRS
 * hot path.
 *
 * One frame in (a complete, length-framed FIX message that FixFrameDecoder has
 * already copied to a fresh byte[]), one of two outcomes:
 *   - valid   -> stamped with a receipt timestamp and published onto the inbound
 *                RingBuffer<OrderEvent> for the matching engine;
 *   - invalid -> logged once at this boundary (SRS §5.4) and dropped. No feedback
 *                path to the client in this demo (locked decision).
 *
 * Boundary discipline:
 *   - Sole producer on the inbound ring (single-writer, SRS §5.2), so
 *     ProducerType.SINGLE is safe and {@link #onFrame} is NOT thread-safe:
 *     exactly one thread (the Netty event loop, or a test thread) may call it.
 *   - Imports com.lmax (it *is* the ring producer) but deliberately NOT io.netty:
 *     the decoder already handed off a plain byte[] copy, so no Netty refcount
 *     discipline leaks in. The decoder->gateway adapter is Phase 4, not this class.
 *
 * Zero allocation on the accept path: parser, parse target (scratch), and publish
 * translator are all pre-allocated and reused. No per-message lambda, no String,
 * no boxing.
 */
public final class OrderGateway {

    private static final Logger log = LoggerFactory.getLogger(OrderGateway.class);

    /**
     * Stored, non-capturing translator (allocated once at class load). Copies every
     * field of the validated scratch event into the claimed ring slot.
     *
     * Why copy instead of parsing straight into the slot: the Disruptor has no
     * "abandon a claimed sequence" — once you take one you must publish it. Parsing
     * into a claimed slot would force publishing rejects (or push reject-awareness
     * into the engine, breaking boundary separation, SRS §5.5). So we parse into a
     * reusable scratch, decide, and only claim+copy on success. The parser clears
     * type-specific fields per branch (Step 5) and this copies all ten, so a
     * reused slot never bleeds stale data.
     */
    private static final EventTranslatorOneArg<OrderEvent, OrderEvent> COPY_INTO_SLOT =
            (slot, sequence, src) -> {
                slot.eventType       = src.eventType;
                slot.orderId         = src.orderId;
                slot.side            = src.side;
                slot.ordType         = src.ordType;
                slot.tif             = src.tif;
                slot.price           = src.price;
                slot.quantity        = src.quantity;
                slot.maxFloor        = src.maxFloor;
                slot.timestamp       = src.timestamp;
                slot.originalOrderId = src.originalOrderId;
            };

    private final RingBuffer<OrderEvent> ringBuffer;
    private final FixParser  parser  = new FixParser();   // holds reusable scan state
    private final OrderEvent scratch = new OrderEvent();  // reusable parse target
    private final LongSupplier clock;

    /**
     * Default constructor: receipt timestamps come from System.nanoTime(), a monotonic
     * source with an arbitrary origin. Suitable for tests and any caller that does not
     * need epoch time.
     *
     * Production (Main) uses the two-arg form to inject the shared EpochNanoClock instead
     * (P7-1), so Order.timeStamp lands in the same epoch-nanos domain as the outbound
     * streams. That clock is affine over System.nanoTime(), so the receipt->publish delta
     * the end-to-end latency benchmark measures (SRS §6.3) stays exact to the nanosecond;
     * only the absolute origin differs.
     */
    public OrderGateway(RingBuffer<OrderEvent> ringBuffer) {
        this(ringBuffer, System::nanoTime);
    }

    /**
     * Clock injection point. Public because Main (root package) wires the shared
     * EpochNanoClock in through here (P7-1); it is also the seam tests use to inject a
     * deterministic clock so they can assert the receipt stamp.
     */
    public OrderGateway(RingBuffer<OrderEvent> ringBuffer, LongSupplier clock) {
        this.ringBuffer = ringBuffer;
        this.clock = clock;
    }

    /**
     * Handle one complete, length-framed FIX message.
     *
     * Not thread-safe (see class doc). Never throws for a bad message: parse
     * failures are logged at this boundary and swallowed.
     *
     * @param frame a full FIX message (8=...9=...body...10=xxx&lt;SOH&gt;), owned by us
     */
    public void onFrame(byte[] frame) {
        if (frame == null) {
            log.warn("Dropped null frame");
            return;
        }

        // Parse into the reusable scratch. On reject the scratch may be partially
        // written, but we never publish it, so that is harmless.
        if (!parser.parse(frame, 0, frame.length, scratch)) {
            if (log.isWarnEnabled()) {
                log.warn("Rejected FIX frame ({} bytes): {}", frame.length, render(frame));
            }
            return;
        }

        // Stamp receipt time here — deferred out of the parser (Step 5) to keep
        // parse deterministic and unit-testable.
        scratch.timestamp = clock.getAsLong();

        if (log.isDebugEnabled()) {
            log.debug("Accepted {} orderId={} side={} price={} qty={} origId={}",
                    scratch.eventType, scratch.orderId, scratch.side,
                    scratch.price, scratch.quantity, scratch.originalOrderId);
        }

        // Claim a slot, copy the validated fields in, publish. One call, no
        // per-message allocation.
        ringBuffer.publishEvent(COPY_INTO_SLOT, scratch);
    }

    /**
     * Render a rejected frame for the log with SOH shown as '|'. Reject path only —
     * allocates a String, which is fine off the accept path.
     */
    private static String render(byte[] frame) {
        StringBuilder sb = new StringBuilder(frame.length);
        for (byte b : frame) {
            sb.append(b == FixConstants.SOH ? '|' : (char) (b & 0xFF));
        }
        return sb.toString();
    }
}