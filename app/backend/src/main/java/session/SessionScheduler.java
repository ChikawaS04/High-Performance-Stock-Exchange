package session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the session controller's {@link SessionController#roll()} on its own thread while the venue
 * runs, so the business day closes and opens by the calendar (SRS §3.12). It polls rather than
 * computing a delay to midnight, so a daylight-saving change, a clock step or a sleeping host cannot
 * make it miss a day. It stores nothing: the engine's session state decides every tick, so a restart
 * has nothing to restore. It never publishes itself; {@code roll()} hands each command to the
 * gateway's thread (D15).
 */
public final class SessionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionScheduler.class);

    public static final String THREAD_NAME = "session-scheduler";
    public static final long TICK_INTERVAL_MS = 1_000L;

    private final Runnable tick;
    private final long intervalMs;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });

    public SessionScheduler(Runnable tick, long intervalMs) {
        this.tick = tick;
        this.intervalMs = intervalMs;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::runTick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops ticking and interrupts a tick in progress, waiting at most {@code timeoutMs} for it.
     *
     * @return whether the thread finished in time
     */
    public boolean stop(long timeoutMs) throws InterruptedException {
        executor.shutdownNow();
        boolean stopped = executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        if (!stopped) {
            log.warn("The session scheduler did not stop within {} ms", timeoutMs);
        }
        return stopped;
    }

    /** A thrown exception would cancel the schedule, and the venue would silently stop rolling. */
    private void runTick() {
        try {
            tick.run();
        } catch (RuntimeException e) {
            log.error("Session scheduler tick failed: {}", e.getMessage(), e);
        }
    }
}
