package participant;

import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.cancelled;
import static participant.Fixtures.cancels;
import static participant.Fixtures.fill;
import static participant.Fixtures.news;
import static participant.Fixtures.print;
import static participant.Fixtures.ticks;
import static participant.Fixtures.view;

class MeanReversionStrategyTest {

    private static MeanReversionStrategy revert() {
        return new MeanReversionStrategy(MeanReversionStrategy.Config.defaults(OPEN),
                new Bots.IdSequence(Bots.Owner.REVERT));
    }

    @Test
    void nearFairItDoesNothing() {
        MeanReversionStrategy m = revert();
        assertTrue(m.onTick(view(OPEN - ticks(1), OPEN + ticks(1), -1), 1_000).isEmpty());
        m.onExec(print(OPEN + ticks(3)));
        assertTrue(m.onTick(view(OPEN + ticks(2), OPEN + ticks(4), OPEN + ticks(3)), 2_000).isEmpty());
    }

    @Test
    void aSellOffBringsABidOneTickInsideTheTouch() {
        MeanReversionStrategy m = revert();
        long last = OPEN - ticks(10);
        m.onExec(print(last));
        List<Action> out = m.onTick(view(OPEN - ticks(11), OPEN - ticks(9), last), 1_000);
        assertEquals(1, out.size());
        Action a = out.get(0);
        assertEquals(Side.BUY, a.side());
        assertEquals(TimeInForce.GTC, a.tif());
        assertEquals(OPEN - ticks(10), a.priceUnits());
        assertEquals(100, a.qty(), "one step of 6 ticks");

        assertTrue(m.onTick(view(OPEN - ticks(10), OPEN - ticks(9), last), 2_000).isEmpty(), "already working");
    }

    @Test
    void aBiggerMoveFadesBiggerAndAOneTickSpreadIsJoined() {
        MeanReversionStrategy m = revert();
        long last = OPEN + ticks(19);
        m.onExec(print(last));
        Action a = m.onTick(view(OPEN + ticks(19), OPEN + ticks(20), last), 1_000).get(0);
        assertEquals(Side.SELL, a.side());
        assertEquals(OPEN + ticks(20), a.priceUnits(), "no room inside a one-tick spread: join the offer");
        assertEquals(300, a.qty(), "three steps");
    }

    @Test
    void whenPriceComesBackItWithdrawsTheFadeAndClosesWhatItHolds() {
        MeanReversionStrategy m = revert();
        m.onExec(print(OPEN - ticks(10)));
        Action bid = m.onTick(view(OPEN - ticks(11), OPEN - ticks(9), OPEN - ticks(10)), 1_000).get(0);
        m.onExec(fill(9L, bid.clOrdId(), Side.SELL, bid.priceUnits(), 40, 0));   // partly filled
        assertEquals(40, m.ownForTest().position());

        for (int i = 0; i < 5; i++) m.onExec(print(OPEN));
        List<Action> out = m.onTick(view(OPEN - ticks(1), OPEN + ticks(1), OPEN), 2_000);
        assertEquals(List.of(bid.clOrdId()), cancels(out).stream().map(Action::origClOrdId).toList());
        Action close = news(out).get(0);
        assertEquals(Side.SELL, close.side());
        assertEquals(OPEN + ticks(1), close.priceUnits(), "rests at the offer");
        assertEquals(40, close.qty());
    }

    @Test
    void itWaitsForAnInFlightCancelBeforePostingAgain() {
        MeanReversionStrategy m = revert();
        m.onExec(print(OPEN - ticks(10)));
        Action bid = m.onTick(view(OPEN - ticks(11), OPEN - ticks(9), OPEN - ticks(10)), 1_000).get(0);
        for (int i = 0; i < 5; i++) m.onExec(print(OPEN));
        assertEquals(1, m.onTick(view(OPEN - ticks(1), OPEN + ticks(1), OPEN), 2_000).size(), "only the cancel");

        m.onExec(print(OPEN - ticks(12)));
        assertTrue(m.onTick(view(OPEN - ticks(13), OPEN - ticks(11), OPEN - ticks(12)), 3_000).isEmpty(),
                "the old bid is not confirmed cancelled yet");
        m.onExec(cancelled(bid.clOrdId()));
        assertEquals(Side.BUY, m.onTick(view(OPEN - ticks(13), OPEN - ticks(11), OPEN - ticks(12)), 4_000)
                .get(0).side());
    }

    @Test
    void sessionCloseCancelsTheFade() {
        MeanReversionStrategy m = revert();
        m.onExec(print(OPEN - ticks(10)));
        Action bid = m.onTick(view(OPEN - ticks(11), OPEN - ticks(9), OPEN - ticks(10)), 1_000).get(0);
        assertEquals(List.of(bid.clOrdId()), m.onSessionClose().stream().map(Action::origClOrdId).toList());
    }
}
