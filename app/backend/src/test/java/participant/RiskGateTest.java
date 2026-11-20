package participant;

import event.ExecutionEventType;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.accepted;
import static participant.Fixtures.cancelled;
import static participant.Fixtures.fill;
import static participant.Fixtures.ticks;

class RiskGateTest {

    private static final long BASE = Bots.Owner.NOISE.base();
    private final RiskLimits risk = new RiskLimits(OPEN, 20, 1_000);

    private RiskGate gate(long maxQty, int maxOpen) {
        return new RiskGate(Bots.Owner.NOISE, risk, maxQty, maxOpen);
    }

    private static Action gtc(long id, Side side, long px, long qty) {
        return Action.newLimit(id, side, TimeInForce.GTC, px, qty);
    }

    @Test
    void anOrderAboveTheMaximumSizeIsRefused() {
        RiskGate g = gate(500, 10);
        assertEquals(RiskGate.Refusal.ORDER_TOO_LARGE, g.check(gtc(BASE, Side.BUY, OPEN, 501)));
        assertNull(g.check(gtc(BASE + 1, Side.BUY, OPEN, 500)));
        assertEquals(1, g.refused());
    }

    @Test
    void openGtcOrdersAreCappedAndACancelPendingFreesASlot() {
        RiskGate g = gate(500, 2);
        assertNull(g.check(gtc(BASE, Side.BUY, OPEN - ticks(1), 10)));
        assertNull(g.check(gtc(BASE + 1, Side.BUY, OPEN - ticks(2), 10)));
        assertEquals(RiskGate.Refusal.TOO_MANY_OPEN_ORDERS, g.check(gtc(BASE + 2, Side.BUY, OPEN - ticks(3), 10)));
        assertNull(g.check(Action.cancel(BASE + 3, BASE)), "a cancel always passes");
        assertNull(g.check(gtc(BASE + 4, Side.BUY, OPEN - ticks(3), 10)));
        assertEquals(2, g.openOrders());
    }

    @Test
    void anIocDoesNotCountAgainstTheOpenOrderCap() {
        RiskGate g = gate(500, 1);
        assertNull(g.check(gtc(BASE, Side.BUY, OPEN - ticks(1), 10)));
        assertNull(g.check(Action.newLimit(BASE + 1, Side.SELL, TimeInForce.IOC, OPEN - ticks(1), 10)));
    }

    @Test
    void aPriceOutsideTheBandOrOffTheTickIsRefused() {
        RiskGate g = gate(500, 10);
        assertEquals(RiskGate.Refusal.OUTSIDE_BAND, g.check(gtc(BASE, Side.SELL, risk.highest() + ticks(1), 10)));
        assertEquals(RiskGate.Refusal.OUTSIDE_BAND, g.check(gtc(BASE + 1, Side.SELL, OPEN + 1, 10)));
        assertNull(g.check(gtc(BASE + 2, Side.SELL, risk.highest(), 10)));
    }

    @Test
    void thePositionLimitHoldsButAnOrderBackTowardFlatPasses() {
        RiskGate g = gate(1_000, 10);
        long id = BASE;
        assertNull(g.check(Action.newLimit(id, Side.BUY, TimeInForce.IOC, OPEN, 900)));
        g.onExec(fill(id, 7L, Side.BUY, OPEN, 900, 0));
        assertEquals(900, g.position());
        assertEquals(RiskGate.Refusal.POSITION_LIMIT, g.check(Action.newLimit(BASE + 1, Side.BUY, TimeInForce.IOC, OPEN, 200)));
        assertNull(g.check(Action.newLimit(BASE + 2, Side.BUY, TimeInForce.IOC, OPEN, 100)));
        assertNull(g.check(Action.newLimit(BASE + 3, Side.SELL, TimeInForce.IOC, OPEN, 1_000)), "back toward flat");
    }

    @Test
    void restingOrdersOnTheSameSideCountTowardThePositionLimit() {
        RiskGate g = gate(1_000, 10);
        assertNull(g.check(gtc(BASE, Side.BUY, OPEN - ticks(1), 600)));
        assertEquals(RiskGate.Refusal.POSITION_LIMIT, g.check(gtc(BASE + 1, Side.BUY, OPEN - ticks(2), 600)));
        g.onExec(cancelled(BASE));
        assertNull(g.check(gtc(BASE + 2, Side.BUY, OPEN - ticks(2), 600)));
    }

    @Test
    void theGateFollowsTheVenuesAnswers() {
        RiskGate g = gate(500, 1);
        assertNull(g.check(gtc(BASE, Side.BUY, OPEN, 10)));
        g.onExec(accepted(BASE, Side.BUY, OPEN, 10));
        g.onExec(new ExecReport(ExecutionEventType.ORDER_REJECTED, BASE, -1, -1, -1, -1, -1, -1, 0L, null));
        assertEquals(0, g.openOrders());
        assertNull(g.check(gtc(BASE + 1, Side.BUY, OPEN, 10)));
    }

    @Test
    void theRefusalIsALocalRejectNamingTheOrder() {
        Action a = gtc(BASE + 9, Side.BUY, OPEN, 10);
        ExecReport r = RiskGate.refusal(a);
        assertEquals(ExecutionEventType.ORDER_REJECTED, r.execType());
        assertEquals(BASE + 9, r.orderId());
    }
}
