package http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The bounded /api executor (P15-12): named threads, refusal when full, a bounded close. */
class ApiExecutorTest {

    private static final long AWAIT_S = 10L;

    private ApiExecutor<StringBuilder> executor;
    private final List<StringBuilder> exited = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        if (executor != null) executor.close();
    }

    private ApiExecutor<StringBuilder> executor(int threads, int maxPending) {
        executor = new ApiExecutor<>("api", threads, maxPending, StringBuilder::new, exited::add, 5_000L);
        return executor;
    }

    @Test
    void tasksRunOnTheNamedThreadsWithTheirOwnState() throws InterruptedException {
        ApiExecutor<StringBuilder> e = executor(2, 16);
        Set<String> names = ConcurrentHashMap.newKeySet();
        Set<StringBuilder> states = ConcurrentHashMap.newKeySet();
        CountDownLatch both = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            assertTrue(e.submit(state -> {
                names.add(Thread.currentThread().getName());
                states.add(state);
                both.countDown();
                await(release);
            }));
        }
        assertTrue(both.await(AWAIT_S, TimeUnit.SECONDS), "both threads took a task");
        release.countDown();
        assertEquals(Set.of("api-1", "api-2"), names);
        assertEquals(2, states.size(), "one state per thread");
    }

    @Test
    void aFullExecutorRefusesAtOnceAndAcceptsAgainWhenATaskEnds() throws InterruptedException {
        ApiExecutor<StringBuilder> e = executor(1, 3);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            assertTrue(e.submit(state -> {
                await(release);
                ran.countDown();
            }));
        }
        assertEquals(3, e.pending());
        assertFalse(e.submit(state -> fail("a refused task never runs")));
        assertEquals(3, e.pending(), "a refusal leaves the count as it was");
        release.countDown();
        assertTrue(ran.await(AWAIT_S, TimeUnit.SECONDS));
        awaitPending(e, 0);
        AtomicInteger later = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(e.submit(state -> {
            later.incrementAndGet();
            done.countDown();
        }));
        assertTrue(done.await(AWAIT_S, TimeUnit.SECONDS));
        assertEquals(1, later.get());
    }

    @Test
    void aFailingTaskDoesNotStopItsThread() throws InterruptedException {
        ApiExecutor<StringBuilder> e = executor(1, 4);
        assertTrue(e.submit(state -> {
            throw new IllegalStateException("expected by the test");
        }));
        CountDownLatch next = new CountDownLatch(1);
        assertTrue(e.submit(state -> next.countDown()));
        assertTrue(next.await(AWAIT_S, TimeUnit.SECONDS));
        awaitPending(e, 0);
    }

    @Test
    void closeStopsTheThreadsAndHandsBackEachState() {
        ApiExecutor<StringBuilder> e = executor(2, 16);
        e.close();
        assertEquals(2, exited.size(), "each thread's state reaches onExit");
        assertFalse(e.submit(state -> fail("nothing runs after close")));
        assertFalse(threadAlive("api-1") || threadAlive("api-2"));
        executor = null;
    }

    @Test
    void closeIsBoundedByATaskThatWillNotEnd() {
        executor = new ApiExecutor<>("api-stuck", 1, 2, StringBuilder::new, exited::add, 200L);
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        assertTrue(executor.submit(state -> {
            started.countDown();
            await(never);
        }));
        assertDoesNotThrow(() -> assertTrue(started.await(AWAIT_S, TimeUnit.SECONDS)));
        long t0 = System.nanoTime();
        executor.close();
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L, "close returned within its bound");
        never.countDown();
        executor = null;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(AWAIT_S, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitPending(ApiExecutor<?> e, int expected) {
        long deadline = System.nanoTime() + AWAIT_S * 1_000_000_000L;
        while (e.pending() != expected) {
            assertTrue(System.nanoTime() < deadline, "pending reached " + expected);
            Thread.onSpinWait();
        }
    }

    private static boolean threadAlive(String name) {
        Thread[] threads = new Thread[Thread.activeCount() * 2];
        int n = Thread.enumerate(threads);
        for (int i = 0; i < n; i++) {
            if (name.equals(threads[i].getName())) return true;
        }
        return false;
    }
}
