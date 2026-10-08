package event;

import com.lmax.disruptor.RingBuffer;
import model.Side;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InboundPipelineTest {

    private static final long TIMEOUT_MS = 2_000;

    // --- Test 1: factory produces distinct, non-null, empty slots ---------

    @Test
    void factoryProducesDistinctEmptySlots() {
        OrderEventFactory factory = new OrderEventFactory();

        OrderEvent a = factory.newInstance();
        OrderEvent b = factory.newInstance();

        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, "each slot must be its own object");

        // "empty" == default-initialised
        assertNull(a.eventType);
        assertNull(a.side);
        assertEquals(0L, a.orderId);
        assertEquals(0L, a.price);
        assertEquals(0L, a.quantity);
        assertEquals(0L, a.timestamp);
        assertEquals(0L, a.originalOrderId);
    }

    // --- Test 2: round-trip through a slot --------------------------------

    @Test
    void publishedEventIsObservedByConsumer() throws InterruptedException {
        CapturingOrderHandler handler = new CapturingOrderHandler();
        InboundPipeline pipeline = new InboundPipeline(handler);
        pipeline.start();
        try {
            publish(pipeline.getRingBuffer(),
                    OrderEventType.NEW_ORDER, 7L, Side.BUY, 1_502_500L, 100L, 111L, -1L);

            CapturingOrderHandler.Observed obs = handler.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertNotNull(obs, "consumer did not observe the published event in time");
            assertEquals(OrderEventType.NEW_ORDER, obs.eventType());
            assertEquals(7L, obs.orderId());
            assertEquals(Side.BUY, obs.side());
            assertEquals(1_502_500L, obs.price());
            assertEquals(100L, obs.quantity());
            assertEquals(111L, obs.timestamp());
            assertEquals(-1L, obs.originalOrderId());
        } finally {
            pipeline.shutdown();
        }
    }

    // --- Test 3: slot reuse — second publish fully overwrites the first ---

    @Test
    void reusedSlotDoesNotBleedStaleFields() throws InterruptedException {
        CapturingOrderHandler handler = new CapturingOrderHandler();
        // Ring size 1 forces slot 0 to be reused for every publish, so this
        // genuinely exercises overwrite-on-reuse rather than two fresh slots.
        InboundPipeline pipeline = new InboundPipeline(1, handler);
        pipeline.start();
        try {
            RingBuffer<OrderEvent> rb = pipeline.getRingBuffer();

            // First: a NEW_ORDER populating side/price/quantity.
            publish(rb, OrderEventType.NEW_ORDER, 7L, Side.BUY, 1_502_500L, 100L, 111L, -1L);

            // Second (same physical slot): a CANCEL that clears those fields.
            publish(rb, OrderEventType.CANCEL_ORDER, 8L, null, -1L, -1L, 222L, 7L);

            CapturingOrderHandler.Observed first =
                    handler.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            CapturingOrderHandler.Observed second =
                    handler.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertNotNull(first);
            assertNotNull(second);

            // First seen intact.
            assertEquals(OrderEventType.NEW_ORDER, first.eventType());
            assertEquals(Side.BUY, first.side());
            assertEquals(1_502_500L, first.price());
            assertEquals(100L, first.quantity());

            // Second: no bleed from the NEW that occupied this slot.
            assertEquals(OrderEventType.CANCEL_ORDER, second.eventType());
            assertEquals(8L, second.orderId());
            assertNull(second.side(), "BUY bled through from the previous slot occupant");
            assertEquals(-1L, second.price(), "price bled through");
            assertEquals(-1L, second.quantity(), "quantity bled through");
            assertEquals(222L, second.timestamp());
            assertEquals(7L, second.originalOrderId());
        } finally {
            pipeline.shutdown();
        }
    }

    // --- helper: write every field, mirroring a correct producer ----------

    private static void publish(RingBuffer<OrderEvent> rb,
                                OrderEventType type, long orderId, Side side,
                                long price, long qty, long ts, long origId) {
        long seq = rb.next();
        try {
            OrderEvent e = rb.get(seq);
            e.eventType = type;
            e.orderId = orderId;
            e.side = side;
            e.price = price;
            e.quantity = qty;
            e.timestamp = ts;
            e.originalOrderId = origId;
        } finally {
            rb.publish(seq);
        }
    }
}