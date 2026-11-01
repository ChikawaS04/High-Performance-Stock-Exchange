package codec;

import event.ExecutionEvent;
import event.OrderEvent;
import event.OrderEventType;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D7 guard: every field of OrderEvent and ExecutionEvent is handled by the journal codecs.
 *
 * Fields are discovered by reflection, so adding a field to either event without handling
 * it in the codec fails here. For OrderEvent, each command type either carries a field or
 * resets it to a stated value; the tables below are the contract, and a field that appears
 * in neither table for some command type fails with a message naming it. ExecutionEvent has
 * one message, which carries every field.
 */
class CodecFieldGuardTest {

    /** Fields each command message carries. eventType is carried by the template id. */
    private static final Map<OrderEventType, Set<String>> CARRIED = Map.of(
            OrderEventType.NEW_ORDER, Set.of(
                    "eventType", "timestamp", "orderId", "side", "ordType", "tif",
                    "price", "quantity", "maxFloor"),
            OrderEventType.CANCEL_ORDER, Set.of(
                    "eventType", "timestamp", "orderId", "originalOrderId"),
            OrderEventType.SESSION_OPEN, Set.of(
                    "eventType", "timestamp", "businessDate", "symbol",
                    "tickSize", "lotSize", "priceScale"),
            OrderEventType.SESSION_CLOSE, Set.of(
                    "eventType", "timestamp", "businessDate"));

    /** The "does not apply" value decode writes for a field a message does not carry. */
    private static final Map<String, Object> RESET = new HashMap<>();
    static {
        RESET.put("orderId", -1L);
        RESET.put("side", null);
        RESET.put("ordType", null);
        RESET.put("tif", null);
        RESET.put("price", -1L);
        RESET.put("quantity", -1L);
        RESET.put("maxFloor", 0L);
        RESET.put("originalOrderId", -1L);
        RESET.put("businessDate", -1);
        RESET.put("symbol", new byte[OrderEvent.SYMBOL_LENGTH]);
        RESET.put("tickSize", -1L);
        RESET.put("lotSize", -1L);
        RESET.put("priceScale", -1L);
    }

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);

    @Test
    void everyOrderEventFieldIsCarriedOrResetForEveryCommandType() {
        OrderEventCodec codec = new OrderEventCodec();
        List<Field> fields = EventFields.instanceFields(OrderEvent.class);
        Set<String> carriedSomewhere = new HashSet<>();

        for (OrderEventType type : OrderEventType.values()) {
            Set<String> carried = CARRIED.get(type);
            if (carried == null) {
                fail("OrderEventType." + type + " has no row in CodecFieldGuardTest.CARRIED; "
                        + "give it a message in the schema and OrderEventCodec");
            }
            carriedSomewhere.addAll(carried);

            OrderEvent src = new OrderEvent();
            EventFields.fill(src, 1);
            src.eventType = type;
            OrderEvent dst = new OrderEvent();
            EventFields.fill(dst, 2);   // garbage that must be fully overwritten

            codec.encode(src, 42L, buffer, 0);
            codec.decode(buffer, 0, dst);

            for (Field f : fields) {
                String name = f.getName();
                Object actual = EventFields.read(f, dst);
                if (carried.contains(name)) {
                    Object expected = EventFields.read(f, src);
                    assertTrue(EventFields.same(expected, actual), "OrderEvent." + name + " not carried by "
                            + type + ": expected " + EventFields.show(expected) + " got " + EventFields.show(actual));
                } else if (RESET.containsKey(name)) {
                    Object expected = RESET.get(name);
                    assertTrue(EventFields.same(expected, actual), "OrderEvent." + name + " not reset by "
                            + type + " decode: expected " + EventFields.show(expected) + " got " + EventFields.show(actual));
                } else {
                    fail("OrderEvent." + name + " is not classified for " + type
                            + "; carry or reset it in OrderEventCodec and list it in CodecFieldGuardTest");
                }
            }
        }

        for (Field f : fields) {
            assertTrue(carriedSomewhere.contains(f.getName()),
                    "OrderEvent." + f.getName() + " is carried by no command message");
        }
    }

    @Test
    void everyExecutionEventFieldIsCarried() {
        ExecutionEventCodec codec = new ExecutionEventCodec();
        ExecutionEvent src = new ExecutionEvent();
        EventFields.fill(src, 1);
        ExecutionEvent dst = new ExecutionEvent();
        EventFields.fill(dst, 2);

        codec.encode(src, buffer, 0);
        codec.decode(buffer, 0, dst);

        for (Field f : EventFields.instanceFields(ExecutionEvent.class)) {
            Object expected = EventFields.read(f, src);
            Object actual = EventFields.read(f, dst);
            assertTrue(EventFields.same(expected, actual), "ExecutionEvent." + f.getName()
                    + " not carried by ExecutionEventCodec: expected " + EventFields.show(expected)
                    + " got " + EventFields.show(actual));
        }
    }
}
