package session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scheduler's thread: where ticks run, that one failing tick does not end them, and a bounded stop. */
class SessionSchedulerTest {

    private SessionScheduler scheduler;

    @AfterEach
    void stop() throws InterruptedException {
        if (scheduler != null) scheduler.stop(5_000L);
    }

    @Test
    void ticksRunOnTheSchedulersOwnThread() throws InterruptedException {
        AtomicReference<Thread> thread = new AtomicReference<>();
        CountDownLatch ticked = new CountDownLatch(2);
        scheduler = new SessionScheduler(() -> {
            thread.set(Thread.currentThread());
            ticked.countDown();
        }, 1L);
        scheduler.start();
        assertTrue(ticked.await(5, TimeUnit.SECONDS));
        assertEquals(SessionScheduler.THREAD_NAME, thread.get().getName());
        assertTrue(thread.get().isDaemon());
    }

    @Test
    void aThrowingTickDoesNotEndTheSchedule() throws InterruptedException {
        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch survived = new CountDownLatch(3);
        scheduler = new SessionScheduler(() -> {
            ticks.incrementAndGet();
            survived.countDown();
            throw new IllegalStateException("one bad tick");
        }, 1L);
        scheduler.start();
        assertTrue(survived.await(5, TimeUnit.SECONDS), "ticks: " + ticks.get());
    }

    @Test
    void stopInterruptsATickAndEndsTheSchedule() throws InterruptedException {
        CountDownLatch inTick = new CountDownLatch(1);
        AtomicInteger ticks = new AtomicInteger();
        scheduler = new SessionScheduler(() -> {
            ticks.incrementAndGet();
            inTick.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 1L);
        scheduler.start();
        assertTrue(inTick.await(5, TimeUnit.SECONDS));
        assertTrue(scheduler.stop(5_000L));
        assertEquals(1, ticks.get());
    }
}
