package participant;

import model.Prices;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.news;
import static participant.Fixtures.ticks;
import static participant.Fixtures.view;

class NoiseTraderStrategyTest {

    private static NoiseTraderStrategy noise(long seed) {
        return new NoiseTraderStrategy(NoiseTraderStrategy.Config.defaults(OPEN),
                new Bots.IdSequence(Bots.Owner.NOISE), new SplittableRandom(seed));
    }

    /** Ten minutes of 250 ms ticks against a fixed two-sided book, with no fills. */
    private static List<Action> run(NoiseTraderStrategy n) {
        MarketView v = view(OPEN - ticks(1), OPEN + ticks(1), OPEN);
        List<Action> all = new ArrayList<>();
        for (long t = 0; t <= 600_000; t += 250) all.addAll(n.onTick(v, t));
        return all;
    }

    @Test
    void itTradesBothWaysBothPassivelyAndAggressively() {
        List<Action> all = run(noise(1));
        List<Action> news = news(all);
        assertTrue(news.size() > 200, "about one arrival every 1.2 s, saw " + news.size());
        assertTrue(news.stream().anyMatch(a -> a.side() == Side.BUY));
        assertTrue(news.stream().anyMatch(a -> a.side() == Side.SELL));
        assertTrue(news.stream().anyMatch(a -> a.tif() == TimeInForce.IOC));
        assertTrue(news.stream().anyMatch(a -> a.tif() == TimeInForce.GTC));
        assertTrue(all.stream().anyMatch(a -> a.kind() == Action.Kind.CANCEL));
    }

    @Test
    void everyOrderIsOnTheTickSizedInStepsAndAtOrBehindTheTouch() {
        for (Action a : news(run(noise(2)))) {
            assertTrue(Prices.isOnTick(a.priceUnits()));
            assertTrue(a.qty() >= 10 && a.qty() <= 100 && a.qty() % 10 == 0, "qty " + a.qty());
            if (a.tif() == TimeInForce.IOC) {
                assertEquals(a.side() == Side.BUY ? OPEN + ticks(1) : OPEN - ticks(1), a.priceUnits(), "at the far touch");
            } else if (a.side() == Side.BUY) {
                assertTrue(a.priceUnits() <= OPEN - ticks(1) && a.priceUnits() >= OPEN - ticks(4));
            } else {
                assertTrue(a.priceUnits() >= OPEN + ticks(1) && a.priceUnits() <= OPEN + ticks(4));
            }
        }
    }

    @Test
    void itNeverHasMoreThanTenWorking() {
        NoiseTraderStrategy n = noise(3);
        MarketView v = view(OPEN - ticks(1), OPEN + ticks(1), OPEN);
        for (long t = 0; t <= 600_000; t += 250) {
            n.onTick(v, t);
            assertTrue(n.ownForTest().working().size() <= 10);
        }
    }

    @Test
    void theSameSeedGivesTheSameOrders() {
        assertEquals(run(noise(42)), run(noise(42)));
    }

    @Test
    void sessionCloseCancelsEverythingRestingIncludingCancelsInFlight() {
        NoiseTraderStrategy n = noise(4);
        run(n);
        int resting = n.ownForTest().resting().size();
        assertTrue(resting > n.ownForTest().working().size(), "some cancels are in flight in this run");
        assertTrue(n.hasResting());
        assertEquals(resting, n.onSessionClose().size());
    }
}
