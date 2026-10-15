package participant;

import event.ExecutionEventType;
import model.Prices;
import model.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure decision-logic tests for the maker. No socket, no threads; seeded id sequence. */
class MarketMakerStrategyTest {

    private static final long OPEN = 1_500_000L;   // $150.00

    private MarketMakerStrategy maker() {
        // halfSpread 5 ticks, clip 50, requote if moved >=2 ticks or 3s elapsed, skew 1 tick/clip, cap 10.
        return new MarketMakerStrategy(
                new MarketMakerStrategy.Config(OPEN, 5, 50, 2, 3_000, 1, 10),
                new Bots.IdSequence(Bots.MAKER_BASE));
    }

    @Test
    void firstTick_quotesSymmetricallyAroundOpen_onTick_whenNoTradeYet() {
        List<Action> out = maker().onTick(MarketView.empty(), 1_000L);
        assertEquals(2, out.size());   // no prior quotes to cancel

        Action bid = out.get(0);
        Action ask = out.get(1);
        assertEquals(Side.BUY, bid.side());
        assertEquals(Side.SELL, ask.side());
        assertEquals(OPEN - 5 * Prices.TICK, bid.priceUnits());   // 1_499_500
        assertEquals(OPEN + 5 * Prices.TICK, ask.priceUnits());   // 1_500_500
        assertEquals(50L, bid.qty());
        assertTrue(bid.priceUnits() % Prices.TICK == 0 && ask.priceUnits() % Prices.TICK == 0);
        assertEquals(Bots.Owner.MAKER, Bots.owner(bid.clOrdId()));
        assertEquals(Bots.Owner.MAKER, Bots.owner(ask.clOrdId()));
    }

    @Test
    void unchangedFairWithinInterval_doesNotRequote() {
        MarketMakerStrategy m = maker();
        m.onTick(MarketView.empty(), 1_000L);
        List<Action> second = m.onTick(MarketView.empty(), 1_200L);   // same fair, 200ms later
        assertTrue(second.isEmpty());
    }

    @Test
    void lastTradeAboveOpen_reCentresUp_andCancelsPriorQuotes() {
        MarketMakerStrategy m = maker();
        List<Action> first = m.onTick(MarketView.empty(), 1_000L);
        long priorBid = first.get(0).clOrdId();
        long priorAsk = first.get(1).clOrdId();

        long traded = OPEN + 10 * Prices.TICK;   // taker walked price up 10 cents
        MarketView moved = new MarketView(Prices.NA, Prices.NA, Prices.NA, traded);
        List<Action> out = m.onTick(moved, 1_500L);

        // two cancels (prior bid+ask) then two fresh quotes
        assertEquals(4, out.size());
        assertEquals(Action.Kind.CANCEL, out.get(0).kind());
        assertEquals(Action.Kind.CANCEL, out.get(1).kind());
        assertEquals(priorBid, out.get(0).origClOrdId());
        assertEquals(priorAsk, out.get(1).origClOrdId());
        assertEquals(traded - 5 * Prices.TICK, out.get(2).priceUnits());
        assertEquals(traded + 5 * Prices.TICK, out.get(3).priceUnits());
    }

    @Test
    void passiveBuyFill_updatesInventory_andRepostsThatSide() {
        MarketMakerStrategy m = maker();
        List<Action> first = m.onTick(MarketView.empty(), 1_000L);
        long bidId = first.get(0).clOrdId();
        assertEquals(bidId, m.bidIdForTest());

        // The maker's resting bid is fully lifted by an aggressor.
        m.onExec(new ExecReport(ExecutionEventType.ORDER_FILLED,
                /*orderId(aggressor)*/ 2_000_050L, /*tradeId*/ 1L, /*price*/ OPEN - 5 * Prices.TICK,
                /*filled*/ 50L, /*remaining(aggressor)*/ 0L,
                /*aggressorId*/ 2_000_050L, /*passiveId*/ bidId, /*ts*/ 10L));

        assertEquals(50L, m.inventoryForTest());   // bought 50
        assertEquals(0L, m.bidIdForTest());          // bid cleared -> next tick reposts

        List<Action> next = m.onTick(MarketView.empty(), 1_200L);   // missing side forces requote
        assertTrue(next.stream().anyMatch(a -> a.kind() == Action.Kind.NEW && a.side() == Side.BUY));
    }
}
