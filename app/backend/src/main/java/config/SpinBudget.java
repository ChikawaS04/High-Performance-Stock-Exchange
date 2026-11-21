package config;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup warnings for thread layouts that keep more threads hot than the machine has cores (P16-2
 * carry, D17). Pure: it reads the configuration and a processor count, and imports neither Agrona nor
 * the Disruptor. A warning never refuses startup; the layouts stay measurable on purpose (P16-12).
 *
 * <p>A thread counts as hot when its strategy keeps it on a core while idle: busy-spin, and yielding
 * (which cedes the core but never sleeps). The counts are the threads this venue starts:
 * <ul>
 *   <li>engine agent: 1 ({@code ENGINE_IDLE_STRATEGY} busy-spin or yielding);</li>
 *   <li>journal: the media driver's shared thread plus the Archive, 2 in SHARED and 4 in DEDICATED
 *       (conductor, recorder, replayer) ({@code JOURNAL_IDLE_STRATEGY} busy-spin or yielding);</li>
 *   <li>rings: the six consumers of the three Disruptor rings ({@code RING_WAIT_STRATEGY} yielding or
 *       busy-spin).</li>
 * </ul>
 *
 * <p>Two rules: (1) a busy-spinning engine with yielding or busy-spinning rings always warns (seven hot
 * threads, the P16-2 finding); (2) more hot threads than {@code processors - 1} warns, so one core stays
 * free for Netty, the operating system and PostgreSQL. In a container the JVM's processor count follows
 * the cpuset and the CPU quota.
 */
public final class SpinBudget {

    /** Hot threads per setting. */
    static final int ENGINE_THREADS = 1;
    static final int JOURNAL_THREADS_SHARED = 2;
    static final int JOURNAL_THREADS_DEDICATED = 4;
    static final int RING_CONSUMERS = 6;

    private SpinBudget() {
    }

    /** Threads the configuration keeps on a core while idle. */
    public static int hotThreads(ExchangeConfig config) {
        int hot = 0;
        if (isHot(config.engineIdle())) hot += ENGINE_THREADS;
        if (isHot(config.journalIdle())) {
            hot += config.archiveThreading() == ExchangeConfig.ArchiveThreading.DEDICATED
                    ? JOURNAL_THREADS_DEDICATED : JOURNAL_THREADS_SHARED;
        }
        if (config.ringWait() != ExchangeConfig.RingWait.BLOCKING) hot += RING_CONSUMERS;
        return hot;
    }

    /**
     * The warnings to log at startup, empty when the layout fits.
     *
     * @param processors {@code Runtime.availableProcessors()}
     */
    public static List<String> warnings(ExchangeConfig config, int processors) {
        List<String> out = new ArrayList<>(2);
        if (config.engineIdle() == ExchangeConfig.EngineIdle.BUSY_SPIN
                && config.ringWait() != ExchangeConfig.RingWait.BLOCKING) {
            out.add(ExchangeConfig.ENGINE_IDLE_STRATEGY + "=busy-spin with " + ExchangeConfig.RING_WAIT_STRATEGY + "="
                    + config.ringWait().value() + " keeps the engine and all six ring consumers on a core while idle"
                    + " (seven hot threads); measured as a diagnostic layout only (P16-2, D17)");
        }
        int hot = hotThreads(config);
        if (hot > processors - 1) {
            out.add(hot + " threads stay on a core while idle (engine " + config.engineIdle().value() + ", journal "
                    + config.journalIdle().value() + " " + config.archiveThreading().value() + ", rings "
                    + config.ringWait().value() + ") but the JVM sees " + processors + " processor(s); at most "
                    + Math.max(0, processors - 1) + " leave a core for Netty, the operating system and PostgreSQL"
                    + " (D17: two spinning threads never share a core)");
        }
        return out;
    }

    private static boolean isHot(ExchangeConfig.EngineIdle idle) {
        return idle == ExchangeConfig.EngineIdle.BUSY_SPIN || idle == ExchangeConfig.EngineIdle.YIELDING;
    }
}
