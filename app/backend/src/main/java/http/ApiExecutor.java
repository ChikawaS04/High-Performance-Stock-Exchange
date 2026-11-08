package http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The dedicated executor for {@code /api} database reads (SRS 3.6): a few named daemon threads, never
 * a Netty event loop. Bounded: at most {@code maxPending} tasks are queued or running at once, and a
 * submit beyond that is refused at once, so the caller answers 503 rather than queueing without end.
 *
 * Built like {@code net.ResendService}: a {@link ConcurrentLinkedQueue}, an atomic bound and
 * park/unpark, because BlockingQueue (and so {@code ThreadPoolExecutor}) is a rejected technology.
 * Each thread owns a piece of state ({@code W}, a database connection for the api), created when the
 * thread starts and handed to {@code onExit} when it ends, so no state is shared between threads.
 *
 * @param <W> the per-thread state
 */
final class ApiExecutor<W> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ApiExecutor.class);

    private static final long IDLE_PARK_NANOS = 100_000_000L;

    private final int maxPending;
    private final long closeTimeoutMs;
    private final ConcurrentLinkedQueue<Consumer<W>> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final Thread[] threads;
    private volatile boolean running = true;

    /**
     * @param threadName    prefix of the thread names: {@code threadName-1} and on
     * @param threadCount   how many threads
     * @param maxPending    tasks queued or running at once, at most
     * @param state         creates one thread's state, on that thread
     * @param onExit        receives that state when the thread ends
     * @param closeTimeoutMs bound on {@link #close()}
     */
    ApiExecutor(String threadName, int threadCount, int maxPending, Supplier<W> state, Consumer<W> onExit,
                long closeTimeoutMs) {
        this.maxPending = maxPending;
        this.closeTimeoutMs = closeTimeoutMs;
        this.threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> run(state, onExit), threadName + "-" + (i + 1));
            t.setDaemon(true);
            threads[i] = t;
        }
        for (Thread t : threads) {
            t.start();
        }
    }

    /**
     * Queues {@code task}. Never blocks.
     *
     * @return false if the executor already holds {@code maxPending} tasks or is closed; the task will not run
     */
    boolean submit(Consumer<W> task) {
        if (!running) return false;
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet();
            return false;
        }
        queue.add(task);
        for (Thread t : threads) {
            LockSupport.unpark(t);
        }
        return true;
    }

    /** Tasks queued or running now. */
    int pending() {
        return pending.get();
    }

    /** Stops the threads, abandoning queued tasks; waits for each at most the close timeout in total. */
    @Override
    public void close() {
        running = false;
        for (Thread t : threads) {
            LockSupport.unpark(t);
        }
        long deadline = System.nanoTime() + closeTimeoutMs * 1_000_000L;
        for (Thread t : threads) {
            long remainingMs = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            try {
                t.join(remainingMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread t : threads) {
            if (t.isAlive()) {
                log.warn("{} did not stop within {} ms; it is a daemon and ends with the JVM", t.getName(), closeTimeoutMs);
            }
        }
    }

    private void run(Supplier<W> stateFactory, Consumer<W> onExit) {
        W state = stateFactory.get();
        try {
            while (running) {
                Consumer<W> task = queue.poll();
                if (task == null) {
                    LockSupport.parkNanos(this, IDLE_PARK_NANOS);
                    continue;
                }
                try {
                    task.accept(state);
                } catch (RuntimeException e) {
                    log.warn("An /api task failed", e);
                } finally {
                    pending.decrementAndGet();
                }
            }
        } finally {
            onExit.accept(state);
        }
    }
}
