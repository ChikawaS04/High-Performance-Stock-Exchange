package gateway;

import codec.EventFields;
import com.lmax.disruptor.EventTranslatorOneArg;
import event.OrderEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7 guard on the gateway's slot copy, the copy point that bit P14-5, P14-7 and P14-9.
 * Fields are discovered by reflection, so a field added to OrderEvent and not copied by
 * OrderGateway.COPY_INTO_SLOT fails here instead of reaching the engine as its default.
 */
class OrderGatewayCopyGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void copyIntoSlotCopiesEveryOrderEventField() throws Exception {
        Field translatorField = OrderGateway.class.getDeclaredField("COPY_INTO_SLOT");
        translatorField.setAccessible(true);
        EventTranslatorOneArg<OrderEvent, OrderEvent> copy =
                (EventTranslatorOneArg<OrderEvent, OrderEvent>) translatorField.get(null);

        OrderEvent src = new OrderEvent();
        EventFields.fill(src, 1);
        OrderEvent slot = new OrderEvent();
        EventFields.fill(slot, 2);   // a reused slot holding someone else's values

        copy.translateTo(slot, 0L, src);

        for (Field f : EventFields.instanceFields(OrderEvent.class)) {
            Object expected = EventFields.read(f, src);
            Object actual = EventFields.read(f, slot);
            assertTrue(EventFields.same(expected, actual), "OrderGateway.COPY_INTO_SLOT does not copy OrderEvent."
                    + f.getName() + ": expected " + EventFields.show(expected) + " got " + EventFields.show(actual));
        }
        assertNotSame(src.symbol, slot.symbol, "the symbol is copied by value, never shared between carriers");
    }
}
