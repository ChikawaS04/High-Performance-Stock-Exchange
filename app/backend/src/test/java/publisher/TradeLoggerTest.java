package publisher;

import event.ExecutionEvent;
import event.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradeLoggerTest {

    /** Immutable copy of the fields we assert on — the live slot is reused, so we must copy. */
    private record Captured(ExecutionEventType type, long tradeId, long price, long qty,
                            long remaining, long aggressor, long passive, long ts) { }

    private static ExecutionEvent exec(ExecutionEventType type, long tradeId, long price,
                                       long qty, long remaining, long aggressor, long passive,
                                       long ts) {
        ExecutionEvent e = new ExecutionEvent();
        e.eventType = type;
        e.orderId = aggressor;
        e.tradeId = tradeId;
        e.price = price;
        e.filledQuantity = qty;
        e.remainingQuantity = remaining;
        e.aggressorOrderId = aggressor;
        e.passiveOrderId = passive;
        e.timestamp = ts;
        return e;
    }

    private static List<Captured> drive(ExecutionEvent... events) {
        List<Captured> out = new ArrayList<>();
        TradeLogger logger = new TradeLogger(e ->
                out.add(new Captured(e.eventType, e.tradeId, e.price, e.filledQuantity,
                        e.remainingQuantity, e.aggressorOrderId, e.passiveOrderId, e.timestamp)));
        long seq = 0;
        for (ExecutionEvent e : events) {
            logger.onEvent(e, seq++, true);
        }
        return out;
    }

    @Test
    void logsFullFill() {
        List<Captured> out = drive(
                exec(ExecutionEventType.ORDER_FILLED, 7L, 1_500_000L, 100L, 0L, 2L, 1L, 999L));
        assertEquals(1, out.size());
        Captured c = out.get(0);
        assertEquals(ExecutionEventType.ORDER_FILLED, c.type());
        assertEquals(7L, c.tradeId());
        assertEquals(1_500_000L, c.price());
        assertEquals(100L, c.qty());
        assertEquals(2L, c.aggressor());
        assertEquals(1L, c.passive());
        assertEquals(999L, c.ts());
    }

    @Test
    void logsPartialFill() {
        List<Captured> out = drive(
                exec(ExecutionEventType.ORDER_PARTIALLY_FILLED, 8L, 1_500_000L, 40L, 60L, 2L, 1L, 5L));
        assertEquals(1, out.size());
        assertEquals(ExecutionEventType.ORDER_PARTIALLY_FILLED, out.get(0).type());
        assertEquals(60L, out.get(0).remaining());
    }

    @Test
    void silentOnAccept() {
        assertTrue(drive(
                exec(ExecutionEventType.ORDER_ACCEPTED, -1L, 1_500_000L, -1L, 100L, 3L, -1L, 1L)).isEmpty());
    }

    @Test
    void silentOnCancel() {
        assertTrue(drive(
                exec(ExecutionEventType.ORDER_CANCELLED, -1L, -1L, -1L, -1L, 3L, -1L, 1L)).isEmpty());
    }

    @Test
    void silentOnReject() {
        assertTrue(drive(
                exec(ExecutionEventType.ORDER_REJECTED, -1L, -1L, -1L, -1L, 3L, -1L, 1L)).isEmpty());
    }

    @Test
    void mixedStreamCapturesOnlyFillsInOrder() {
        List<Captured> out = drive(
                exec(ExecutionEventType.ORDER_ACCEPTED, -1L, 1_500_000L, -1L, 100L, 3L, -1L, 1L),
                exec(ExecutionEventType.ORDER_PARTIALLY_FILLED, 8L, 1_500_000L, 40L, 60L, 4L, 3L, 2L),
                exec(ExecutionEventType.ORDER_CANCELLED, -1L, -1L, -1L, -1L, 5L, -1L, 3L),
                exec(ExecutionEventType.ORDER_FILLED, 9L, 1_500_500L, 60L, 0L, 4L, 3L, 4L),
                exec(ExecutionEventType.ORDER_REJECTED, -1L, -1L, -1L, -1L, 6L, -1L, 5L));
        assertEquals(2, out.size());
        assertEquals(8L, out.get(0).tradeId());
        assertEquals(9L, out.get(1).tradeId());
    }

    @Test
    void reusedSlotIsCopiedAtCallTime() {
        // Prove the sink reads the slot immediately: mutate one instance between publishes.
        List<Captured> out = new ArrayList<>();
        TradeLogger logger = new TradeLogger(e ->
                out.add(new Captured(e.eventType, e.tradeId, e.price, e.filledQuantity,
                        e.remainingQuantity, e.aggressorOrderId, e.passiveOrderId, e.timestamp)));

        ExecutionEvent slot = exec(ExecutionEventType.ORDER_FILLED, 1L, 10_000L, 10L, 0L, 2L, 1L, 1L);
        logger.onEvent(slot, 0L, true);
        slot.tradeId = 2L;
        slot.price = 20_000L;
        logger.onEvent(slot, 1L, true);

        assertEquals(2, out.size());
        assertEquals(1L, out.get(0).tradeId());
        assertEquals(10_000L, out.get(0).price());
        assertEquals(2L, out.get(1).tradeId());
        assertEquals(20_000L, out.get(1).price());
    }

    @Test
    void defaultConstructorLogsWithoutThrowing() {
        // Smoke: real SLF4J sink path (console) must not blow up on a fill.
        new TradeLogger().onEvent(
                exec(ExecutionEventType.ORDER_FILLED, 1L, 1_500_000L, 100L, 0L, 2L, 1L, 1L), 0L, true);
    }
}