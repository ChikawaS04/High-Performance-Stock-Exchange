package codec;

import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import event.OrderEventType;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Steady-state encode and decode allocate nothing (SRS §5.1). Measured with the thread's
 * allocated-byte counter over many cycles after a warm-up: one 16-byte allocation per cycle
 * would show as megabytes, so the small slack only absorbs the measurement itself.
 */
class CodecAllocationTest {

    private static final int WARMUP = 50_000;
    private static final int CYCLES = 100_000;
    private static final long SLACK_BYTES = 1_024;

    private static com.sun.management.ThreadMXBean allocationCounter() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean,
                "per-thread allocation counter not available on this JVM");
        com.sun.management.ThreadMXBean counter = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(counter.isThreadAllocatedMemorySupported(),
                "per-thread allocation counter not supported on this JVM");
        counter.setThreadAllocatedMemoryEnabled(true);
        return counter;
    }

    @Test
    void commandEncodeAndDecodeAllocateNothing() {
        com.sun.management.ThreadMXBean counter = allocationCounter();
        OrderEventCodec codec = new OrderEventCodec();
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(OrderEventCodec.MAX_ENCODED_LENGTH));

        OrderEvent order = new OrderEvent();
        order.eventType = OrderEventType.NEW_ORDER;
        order.side = Side.BUY;
        order.ordType = OrdType.LIMIT;
        order.tif = TimeInForce.GTC;
        order.price = 1_000_000L;
        order.quantity = 10L;
        order.originalOrderId = -1L;
        order.clearSessionFields();

        OrderEvent open = new OrderEvent();
        open.eventType = OrderEventType.SESSION_OPEN;
        open.businessDate = 20261102;
        open.symbol[0] = 'X';
        open.tickSize = 100L;
        open.lotSize = 1L;
        open.priceScale = 10_000L;

        OrderEvent decoded = new OrderEvent();

        long sink = run(codec, buffer, order, open, decoded, WARMUP);
        long before = counter.getCurrentThreadAllocatedBytes();
        sink += run(codec, buffer, order, open, decoded, CYCLES);
        long allocated = counter.getCurrentThreadAllocatedBytes() - before;

        assertTrue(sink != 0);
        assertTrue(allocated < SLACK_BYTES,
                "command codec allocated " + allocated + " bytes over " + CYCLES + " cycles");
    }

    private static long run(OrderEventCodec codec, UnsafeBuffer buffer, OrderEvent order, OrderEvent open,
                            OrderEvent decoded, int cycles) {
        long sink = 0;
        for (int i = 0; i < cycles; i++) {
            order.orderId = i;
            order.timestamp = i;
            codec.encode(order, i, buffer, 0);
            sink += codec.decode(buffer, 0, decoded);
            codec.encode(open, i, buffer, 0);
            sink += codec.decode(buffer, 0, decoded);
        }
        return sink;
    }

    @Test
    void executionEncodeAndDecodeAllocateNothing() {
        com.sun.management.ThreadMXBean counter = allocationCounter();
        ExecutionEventCodec codec = new ExecutionEventCodec();
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(ExecutionEventCodec.ENCODED_LENGTH));

        ExecutionEvent fill = new ExecutionEvent();
        fill.eventType = ExecutionEventType.ORDER_FILLED;
        fill.aggressorSide = Side.SELL;
        fill.businessDate = -1;
        ExecutionEvent decoded = new ExecutionEvent();

        long sink = run(codec, buffer, fill, decoded, WARMUP);
        long before = counter.getCurrentThreadAllocatedBytes();
        sink += run(codec, buffer, fill, decoded, CYCLES);
        long allocated = counter.getCurrentThreadAllocatedBytes() - before;

        assertTrue(sink != 0);
        assertTrue(allocated < SLACK_BYTES,
                "execution codec allocated " + allocated + " bytes over " + CYCLES + " cycles");
    }

    private static long run(ExecutionEventCodec codec, UnsafeBuffer buffer, ExecutionEvent fill,
                            ExecutionEvent decoded, int cycles) {
        long sink = 0;
        for (int i = 0; i < cycles; i++) {
            fill.seq = i + 1;
            fill.tradeId = i;
            codec.encode(fill, buffer, 0);
            codec.decode(buffer, 0, decoded);
            sink += decoded.seq;
        }
        return sink;
    }
}
