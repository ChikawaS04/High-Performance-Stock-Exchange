package engine;

import codec.OrderEventCodec;
import event.OrderEvent;
import event.OrderEventType;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared apply path (P15-5): recovery's replay and the live agent both drive it, so its seq
 * check, progress and listener call are the same for both.
 */
class CommandApplierTest {

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[OrderEventCodec.MAX_ENCODED_LENGTH]);
    private final OrderEventCodec codec = new OrderEventCodec();

    private void encode(long seq, long orderId) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.NEW_ORDER;
        e.orderId = orderId;
        e.side = Side.BUY;
        e.ordType = OrdType.LIMIT;
        e.tif = TimeInForce.GTC;
        e.price = 1_000_000L;
        e.quantity = 10L;
        e.timestamp = orderId;
        e.originalOrderId = -1L;
        e.clearSessionFields();
        codec.encode(e, seq, buffer, 0);
    }

    @Test
    void appliesInOrderAndTellsTheListenerTheSeqAndPosition() {
        List<Long> handled = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        CommandApplier applier = new CommandApplier(c -> handled.add(c.orderId),
                (seq, position) -> applied.add(seq + "@" + position), 4L, 256L);
        assertEquals(4L, applier.appliedSeq());
        assertEquals(256L, applier.appliedPosition());

        encode(5L, 50L);
        applier.apply(buffer, 0, 352L);
        encode(6L, 60L);
        applier.apply(buffer, 0, 448L);

        assertEquals(List.of(50L, 60L), handled);
        assertEquals(List.of("5@352", "6@448"), applied);
        assertEquals(6L, applier.appliedSeq());
        assertEquals(448L, applier.appliedPosition());
    }

    @Test
    void theHandlerIsToldTheCommandsSeq() {
        List<Long> seqs = new ArrayList<>();
        CommandApplier applier = new CommandApplier(new CommandHandler() {
            @Override
            public void onCommand(OrderEvent command) {
                fail("the applier passes the seq");
            }

            @Override
            public void onCommand(OrderEvent command, long seq) {
                seqs.add(seq);
            }
        }, null, 4L, 256L);
        encode(5L, 50L);
        applier.apply(buffer, 0, 352L);
        assertEquals(List.of(5L), seqs);
    }

    @Test
    void aGapOrARepeatIsRefusedBeforeTheHandlerSeesIt() {
        List<Long> handled = new ArrayList<>();
        CommandApplier applier = new CommandApplier(c -> handled.add(c.orderId), null, 4L, 256L);
        encode(6L, 60L);
        assertThrows(IllegalStateException.class, () -> applier.apply(buffer, 0, 352L), "gap");
        encode(4L, 40L);
        assertThrows(IllegalStateException.class, () -> applier.apply(buffer, 0, 352L), "repeat");
        assertTrue(handled.isEmpty());
        assertEquals(4L, applier.appliedSeq());
        assertEquals(256L, applier.appliedPosition());
    }

    @Test
    void aHandlerFailureLeavesProgressOnThePreviousCommand() {
        CommandApplier applier = new CommandApplier(c -> { throw new IllegalStateException("boom"); },
                (seq, position) -> fail("not applied"), 0L, 0L);
        encode(1L, 10L);
        assertThrows(IllegalStateException.class, () -> applier.apply(buffer, 0, 96L));
        assertEquals(0L, applier.appliedSeq());
    }
}
