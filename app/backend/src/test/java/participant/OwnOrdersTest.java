package participant;

import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.accepted;
import static participant.Fixtures.cancelled;
import static participant.Fixtures.fill;

class OwnOrdersTest {

    private final Bots.IdSequence ids = new Bots.IdSequence(Bots.Owner.MAKER);
    private final OwnOrders own = new OwnOrders(ids);

    private long rest(Side side, long qty) {
        Action a = own.sent(Action.newLimit(ids.next(), side, TimeInForce.GTC, OPEN, qty));
        return a.clOrdId();
    }

    @Test
    void aPassiveFillDecrementsAndMovesThePositionAgainstTheAggressor() {
        long bid = rest(Side.BUY, 100);
        own.onExec(fill(9L, bid, Side.SELL, OPEN, 40, 0));   // an outsider sells into our bid
        assertEquals(40, own.position());
        assertEquals(60, own.resting().iterator().next().remaining());

        own.onExec(fill(9L, bid, Side.SELL, OPEN, 60, 0));
        assertEquals(100, own.position());
        assertTrue(own.resting().isEmpty(), "fully consumed");
    }

    @Test
    void anAggressorFillTakesItsRemainingFromTheReport() {
        long ask = rest(Side.SELL, 100);
        own.onExec(fill(ask, 9L, Side.SELL, OPEN, 30, 70));   // our GTC crossed on arrival
        assertEquals(-30, own.position());
        assertEquals(70, own.resting().iterator().next().remaining());
    }

    @Test
    void anIocIsCountedInThePositionButNeverRests() {
        Action ioc = own.sent(Action.newLimit(ids.next(), Side.BUY, TimeInForce.IOC, OPEN, 50));
        assertTrue(own.resting().isEmpty());
        own.onExec(fill(ioc.clOrdId(), 9L, Side.BUY, OPEN, 50, 0));
        assertEquals(50, own.position());
    }

    @Test
    void aTradeBetweenTwoOfOurOwnOrdersNetsToZero() {
        long bid = rest(Side.BUY, 100);
        long ask = rest(Side.SELL, 100);
        own.onExec(fill(ask, bid, Side.SELL, OPEN, 100, 0));
        assertEquals(0, own.position());
        assertTrue(own.resting().isEmpty());
    }

    @Test
    void anAcceptedRestatesTheRemainingAndTerminalReportsRemoveTheOrder() {
        long bid = rest(Side.BUY, 100);
        own.onExec(accepted(bid, Side.BUY, OPEN, 80));
        assertEquals(80, own.resting().iterator().next().remaining());
        own.onExec(cancelled(bid));
        assertTrue(own.resting().isEmpty());
    }

    @Test
    void aCancelIsSentOnceAndThePendingOrderLeavesTheWorkingSet() {
        long bid = rest(Side.BUY, 100);
        long ask = rest(Side.SELL, 100);
        Action c = own.cancel(bid);
        assertEquals(bid, c.origClOrdId());
        assertNull(own.cancel(bid), "never cancelled twice");
        assertEquals(List.of(ask), own.working().stream().map(r -> r.id).toList());
        assertEquals(2, own.resting().size(), "still resting until the venue answers");

        List<Action> rest = own.cancelAll();
        assertEquals(1, rest.size(), "only the order without a pending cancel");
        assertEquals(ask, rest.get(0).origClOrdId());
        assertEquals(0, own.workingQty(Side.SELL));
    }

    @Test
    void cancelEverythingAlsoResendsACancelAlreadyInFlight() {
        long bid = rest(Side.BUY, 100);
        long ask = rest(Side.SELL, 100);
        own.cancel(bid);
        List<Action> all = own.cancelEverything();
        assertEquals(List.of(bid, ask), all.stream().map(Action::origClOrdId).toList());
        own.onExec(cancelled(bid));
        own.onExec(new ExecReport(event.ExecutionEventType.ORDER_REJECTED, ask, -1, -1, -1, -1, -1, -1, 0L, null));
        assertTrue(own.resting().isEmpty(), "a duplicate cancel's reject also clears the order");
    }

    @Test
    void withoutASideOnTheWireThePositionComesFromOurOwnRecord() {
        long bid = rest(Side.BUY, 100);
        own.onExec(fill(9L, bid, null, OPEN, 25, 0));
        assertEquals(25, own.position());
    }

    @Test
    void anotherBotsFillsAreIgnored() {
        rest(Side.BUY, 100);
        long otherBot = Bots.Owner.NOISE.base() + 7;
        own.onExec(fill(otherBot, 9L, Side.BUY, OPEN, 25, 0));
        assertEquals(0, own.position());
    }
}
