package participant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static participant.Fixtures.OPEN;
import static participant.Fixtures.ticks;

/** Amendment 4: at a live session open the maker's anchor target follows the venue's new open. */
class AnchorRefreshTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Runs tasks only when drained, so the test sees which thread-hop each step takes. */
    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable r) {
            tasks.add(r);
        }

        void drain() {
            while (!tasks.isEmpty()) tasks.remove(0).run();
        }
    }

    private static MarketMakerStrategy maker() {
        MarketMakerStrategy.Config d = MarketMakerStrategy.Config.defaults(OPEN);
        return new MarketMakerStrategy(new MarketMakerStrategy.Config(OPEN, d.baseHalfSpreadTicks(), d.levelQtys(),
                d.volMultiplier(), d.requoteMoveTicks(), d.requoteIntervalMillis(), d.skewClipQty(), d.maxSkewTicks(),
                d.risk(), 0, 5, 20), new Bots.IdSequence(Bots.Owner.MAKER));
    }

    @Test
    void aNewOpenIsReadOnTheWorkerAndDeliveredOnTheClientExecutor() {
        Queue worker = new Queue();
        Queue client = new Queue();
        long newOpen = OPEN + ticks(300);
        AnchorRefresh r = new AnchorRefresh(() -> OpenPriceSource.Answer.of(200,
                "{\"symbol\":\"ASML\",\"openPx\":" + newOpen + ",\"date\":20261119}", -1), MAPPER, worker);
        MarketMakerStrategy m = maker();

        r.request("MAKER", client, m);
        assertEquals(OPEN, m.anchor(), "nothing happens on the calling thread");
        worker.drain();
        assertEquals(OPEN, m.anchor(), "nothing touches the strategy off its own executor");
        client.drain();
        assertEquals(newOpen, m.anchor());
    }

    @Test
    void aFailedReadKeepsTheOldAnchor() {
        Queue worker = new Queue();
        Queue client = new Queue();
        MarketMakerStrategy m = maker();
        for (OpenPriceSource.Answer a : List.of(OpenPriceSource.Answer.of(503, "", 5),
                OpenPriceSource.Answer.of(404, "", -1), OpenPriceSource.Answer.failed("ConnectException"),
                OpenPriceSource.Answer.of(200, "{\"openPx\":-1}", -1))) {
            new AnchorRefresh(() -> a, MAPPER, worker).request("MAKER", client, m);
            worker.drain();
            assertTrue(client.tasks.isEmpty(), "no delivery for " + a);
            assertEquals(OPEN, m.anchor());
        }
    }
}
