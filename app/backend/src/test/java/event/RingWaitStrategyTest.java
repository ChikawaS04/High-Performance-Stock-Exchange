package event;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P16-2: the outbound and snapshot rings run with an injected wait strategy, and their consumer
 * threads carry numbered names.
 */
class RingWaitStrategyTest {

    private OutboundPipeline outbound;
    private SnapshotPipeline snapshots;

    @AfterEach
    void stop() {
        if (outbound != null) outbound.halt();
        if (snapshots != null) snapshots.halt();
    }

    @Test
    void outboundConsumersRunWithEachWaitStrategyOnNamedThreads() throws InterruptedException {
        for (WaitStrategy wait : List.of(new YieldingWaitStrategy(), new BusySpinWaitStrategy())) {
            outbound = new OutboundPipeline(wait);
            Set<String> names = ConcurrentHashMap.newKeySet();
            CountDownLatch seen = new CountDownLatch(2);
            EventHandler<ExecutionEvent> first = (e, seq, end) -> {
                names.add(Thread.currentThread().getName());
                seen.countDown();
            };
            EventHandler<ExecutionEvent> second = (e, seq, end) -> {
                names.add(Thread.currentThread().getName());
                seen.countDown();
            };
            outbound.handleEventsWith(first, second);
            outbound.start();
            outbound.getRingBuffer().publishEvent((e, seq) -> e.seq = 1L);
            assertTrue(seen.await(5, TimeUnit.SECONDS), wait.getClass().getSimpleName());
            assertEquals(Set.of("outbound-consumer-1", "outbound-consumer-2"), names);
            outbound.halt();
            outbound = null;
        }
    }

    @Test
    void snapshotConsumersRunWithAnInjectedWaitStrategyOnNamedThreads() throws InterruptedException {
        snapshots = new SnapshotPipeline(new YieldingWaitStrategy());
        Set<String> names = ConcurrentHashMap.newKeySet();
        CountDownLatch seen = new CountDownLatch(1);
        snapshots.handleEventsWith((s, seq, end) -> {
            names.add(Thread.currentThread().getName());
            seen.countDown();
        });
        snapshots.start();
        snapshots.getRingBuffer().publishEvent((s, seq) -> s.lastSeq = 1L);
        assertTrue(seen.await(5, TimeUnit.SECONDS));
        assertEquals(Set.of("snapshot-consumer-1"), names);
    }

    @Test
    void theNoArgumentConstructorStillNamesItsThreads() throws InterruptedException {
        outbound = new OutboundPipeline();
        Set<String> names = ConcurrentHashMap.newKeySet();
        CountDownLatch seen = new CountDownLatch(1);
        outbound.handleEventsWith((e, seq, end) -> {
            names.add(Thread.currentThread().getName());
            seen.countDown();
        });
        outbound.start();
        outbound.getRingBuffer().publishEvent((e, seq) -> e.seq = 1L);
        assertTrue(seen.await(5, TimeUnit.SECONDS));
        assertEquals(Set.of("outbound-consumer-1"), names);
    }
}
