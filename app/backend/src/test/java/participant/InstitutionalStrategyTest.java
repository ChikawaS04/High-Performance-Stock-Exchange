package participant;

import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.cancelled;
import static participant.Fixtures.cancels;
import static participant.Fixtures.fill;
import static participant.Fixtures.news;
import static participant.Fixtures.ticks;
import static participant.Fixtures.view;

class InstitutionalStrategyTest {

    private static final MarketView BOOK = view(OPEN - ticks(1), OPEN + ticks(1), OPEN);

    private static InstitutionalStrategy inst(long seed) {
        return new InstitutionalStrategy(InstitutionalStrategy.Config.defaults(OPEN),
                new Bots.IdSequence(Bots.Owner.INST), new SplittableRandom(seed));
    }

    /** Ticks through the first delay; returns the campaign's opening iceberg. */
    private static Action start(InstitutionalStrategy s) {
        assertTrue(s.onTick(BOOK, 0).isEmpty(), "waits for the market to form");
        assertTrue(s.onTick(BOOK, 14_000).isEmpty());
        List<Action> out = s.onTick(BOOK, 15_000);
        assertEquals(1, out.size());
        return out.get(0);
    }

    @Test
    void aCampaignWorksTheWholeParentAsAnIcebergAtItsOwnTouch() {
        InstitutionalStrategy s = inst(1);
        Action ice = start(s);
        Side side = s.sideForTest();
        assertEquals(side, ice.side());
        assertEquals(TimeInForce.GTC, ice.tif());
        assertEquals(200, ice.maxFloor());
        assertEquals(s.parentForTest(), ice.qty());
        assertTrue(ice.qty() >= 10_000 && ice.qty() <= 30_000 && ice.qty() % 100 == 0);
        assertEquals(side == Side.BUY ? OPEN - ticks(1) : OPEN + ticks(1), ice.priceUnits());
    }

    @Test
    void whenTheTouchMovesAwayItCancelsAndRepostsAtTheNewTouch() {
        InstitutionalStrategy s = inst(1);
        Action ice = start(s);
        long shift = s.sideForTest() == Side.BUY ? ticks(3) : -ticks(3);
        MarketView moved = view(BOOK.bestBid() + shift, BOOK.bestAsk() + shift, OPEN + shift);

        List<Action> out = s.onTick(moved, 16_000);
        assertEquals(List.of(ice.clOrdId()), cancels(out).stream().map(Action::origClOrdId).toList());
        assertTrue(news(out).isEmpty(), "never two icebergs at once");
        assertTrue(news(s.onTick(moved, 17_000)).isEmpty(), "waits for the cancel to land");

        s.onExec(cancelled(ice.clOrdId()));
        Action again = news(s.onTick(moved, 18_000)).get(0);
        assertEquals(ice.priceUnits() + shift, again.priceUnits());
    }

    @Test
    void fillsCountTowardTheParentAndAFinishedParentEndsTheCampaign() {
        InstitutionalStrategy s = inst(1);
        Action ice = start(s);
        Side side = s.sideForTest();
        long parent = s.parentForTest();
        s.onExec(fill(9L, ice.clOrdId(), side == Side.BUY ? Side.SELL : Side.BUY, ice.priceUnits(), 200, 0));
        assertEquals(200, s.filledForTest());

        s.onExec(fill(9L, ice.clOrdId(), side == Side.BUY ? Side.SELL : Side.BUY, ice.priceUnits(), parent - 200, 0));
        List<Action> out = s.onTick(BOOK, 16_000);
        assertTrue(out.isEmpty(), "nothing left resting to cancel");
        assertNull(s.sideForTest(), "between campaigns");
        assertTrue(s.onTick(BOOK, 40_000).isEmpty(), "paused at least 30 s");
    }

    @Test
    void wellBehindScheduleItAlsoTakesAClipAtTheFarTouch() {
        InstitutionalStrategy s = inst(1);
        start(s);
        Side side = s.sideForTest();
        List<Action> out = s.onTick(BOOK, 15_000 + 150_000);   // a quarter of the target gone, nothing filled
        Action take = out.stream().filter(a -> a.kind() == Action.Kind.NEW && a.tif() == TimeInForce.IOC)
                .findFirst().orElseThrow();
        assertEquals(side, take.side());
        assertEquals(side == Side.BUY ? OPEN + ticks(1) : OPEN - ticks(1), take.priceUnits());
        assertEquals(200, take.qty());
    }

    @Test
    void sessionCloseCancelsTheIcebergAndEndsTheCampaign() {
        InstitutionalStrategy s = inst(1);
        Action ice = start(s);
        assertEquals(List.of(ice.clOrdId()), s.onSessionClose().stream().map(Action::origClOrdId).toList());
        assertNull(s.sideForTest());
    }
}
