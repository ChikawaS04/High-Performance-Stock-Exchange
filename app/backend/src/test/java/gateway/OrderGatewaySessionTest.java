package gateway;

import codec.EventFields;
import com.lmax.disruptor.RingBuffer;
import engine.Sessions;
import event.OrderEvent;
import event.OrderEventFactory;
import integration.FixMessages;
import model.Prices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gateway's session entry point (P15-8, D15): every field of the carrier is written for a
 * session command, whatever the previous message left in the reused scratch and slot, the receipt
 * stamp comes from the gateway's clock, and the gateway is confined to one producer thread. A raw
 * single-producer ring with no consumer: each publish lands in the next slot, read back directly.
 */
class OrderGatewaySessionTest {

    private static final long STAMP = 5_000L;
    private static final List<Field> FIELDS = EventFields.instanceFields(OrderEvent.class);

    private final RingBuffer<OrderEvent> ring = RingBuffer.createSingleProducer(new OrderEventFactory(), 8);
    private final OrderGateway gateway = new OrderGateway(ring, () -> STAMP);

    private void assertSlot(long sequence, OrderEvent expected) {
        OrderEvent got = ring.get(sequence);
        for (Field f : FIELDS) {
            Object a = EventFields.read(f, expected);
            Object b = EventFields.read(f, got);
            assertTrue(EventFields.same(a, b), "slot " + sequence + ", field " + f.getName() + ": expected "
                    + EventFields.show(a) + " but was " + EventFields.show(b));
        }
    }

    @Test
    void aSessionOpenWritesEveryFieldAfterANewLeftTheCarrierDirty() {
        gateway.onFrame(FixMessages.iceberg(9L, '1', "150.25", 1_000, 100));   // every order field set
        gateway.publishSessionOpen(Sessions.BUSINESS_DATE, Sessions.symbol(), Prices.TICK, 1L, Prices.SCALE);

        assertEquals(1, ring.getCursor());
        assertSlot(1, Sessions.openCommand(Sessions.BUSINESS_DATE, STAMP));
    }

    @Test
    void aSessionCloseResetsTheOpensReferenceFields() {
        gateway.publishSessionOpen(Sessions.BUSINESS_DATE, Sessions.symbol("BRK.B"), 7L, 3L, 99L);
        gateway.publishSessionClose(Sessions.BUSINESS_DATE);

        assertSlot(1, Sessions.closeCommand(Sessions.BUSINESS_DATE, STAMP));
        assertArrayEquals(new byte[8], ring.get(1).symbol, "no symbol bleeds from the open");
    }

    @Test
    void aNewAfterASessionCommandCarriesNoSessionField() {
        gateway.publishSessionOpen(Sessions.BUSINESS_DATE, Sessions.symbol(), Prices.TICK, 1L, Prices.SCALE);
        gateway.onFrame(FixMessages.limit(1L, '1', "150.00", 10));

        OrderEvent after = ring.get(1);
        assertEquals(-1, after.businessDate);
        assertArrayEquals(new byte[8], after.symbol);
        assertEquals(-1, after.tickSize);
        assertEquals(-1, after.lotSize);
        assertEquals(-1, after.priceScale);
    }

    @Test
    void aSymbolOfTheWrongLengthIsRefusedBeforeAnySlotIsClaimed() {
        assertThrows(IllegalArgumentException.class,
                () -> gateway.publishSessionOpen(Sessions.BUSINESS_DATE, new byte[4], Prices.TICK, 1L, Prices.SCALE));
        assertEquals(-1, ring.getCursor(), "nothing published");
    }

    @Test
    void thePublishingThreadIsConfinedAndAnotherThreadIsRefusedBeforeClaimingASlot() throws Exception {
        assertNull(gateway.producerThread(), "unowned before the first publish");
        gateway.publishSessionOpen(Sessions.BUSINESS_DATE, Sessions.symbol(), Prices.TICK, 1L, Prices.SCALE);
        assertSame(Thread.currentThread(), gateway.producerThread());

        AtomicReference<Throwable> fromFrame = new AtomicReference<>();
        AtomicReference<Throwable> fromSession = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                gateway.onFrame(FixMessages.limit(1L, '1', "150.00", 10));
            } catch (Throwable t) {
                fromFrame.set(t);
            }
            try {
                gateway.publishSessionClose(Sessions.BUSINESS_DATE);
            } catch (Throwable t) {
                fromSession.set(t);
            }
        }, "not-the-gateway");
        other.start();
        other.join(5_000);

        assertInstanceOf(IllegalStateException.class, fromFrame.get());
        assertTrue(fromFrame.get().getMessage().contains("not-the-gateway"), fromFrame.get().getMessage());
        assertInstanceOf(IllegalStateException.class, fromSession.get());
        assertEquals(0, ring.getCursor(), "the refused calls claimed no slot");
        assertSame(Thread.currentThread(), gateway.producerThread(), "ownership never moves");
    }
}
