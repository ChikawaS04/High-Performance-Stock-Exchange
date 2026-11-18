package journal;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import config.ExchangeConfig;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.util.function.Supplier;

/**
 * Maps the wait and idle settings of {@link ExchangeConfig} to Agrona and Disruptor strategies
 * (P16-2), so the config itself stays free of both libraries.
 *
 * Every call returns a new instance. A backoff or sleeping idle strategy and a blocking wait
 * strategy hold per-thread or per-ring state, so no instance is ever shared between threads.
 */
public final class WaitStrategies {

    private WaitStrategies() {
    }

    /** {@code ENGINE_IDLE_STRATEGY}: the engine agent's idle strategy (P15-4). */
    public static IdleStrategy engineIdle(ExchangeConfig.EngineIdle idle) {
        switch (idle) {
            case BACKOFF:   return new BackoffIdleStrategy();
            case SLEEPING:  return new SleepingMillisIdleStrategy(1);
            case YIELDING:  return new YieldingIdleStrategy();
            case BUSY_SPIN: return new BusySpinIdleStrategy();
            default:        throw new IllegalArgumentException("Unmapped engine idle: " + idle);
        }
    }

    /**
     * {@code JOURNAL_IDLE_STRATEGY}: a supplier of idle strategies for the media driver and
     * Archive threads, or null for BACKOFF. Null means the journal sets nothing, so Aeron keeps its
     * own default (a backoff strategy with Aeron's spin, yield and park bounds) and today's
     * behaviour is unchanged exactly.
     */
    public static Supplier<IdleStrategy> journalIdle(ExchangeConfig.EngineIdle idle) {
        switch (idle) {
            case BACKOFF:   return null;
            case SLEEPING:  return () -> new SleepingMillisIdleStrategy(1);
            case YIELDING:  return YieldingIdleStrategy::new;
            case BUSY_SPIN: return BusySpinIdleStrategy::new;
            default:        throw new IllegalArgumentException("Unmapped journal idle: " + idle);
        }
    }

    /** {@code RING_WAIT_STRATEGY}: a new wait strategy for one Disruptor ring. */
    public static WaitStrategy ringWait(ExchangeConfig.RingWait wait) {
        switch (wait) {
            case BLOCKING:  return new BlockingWaitStrategy();
            case YIELDING:  return new YieldingWaitStrategy();
            case BUSY_SPIN: return new BusySpinWaitStrategy();
            default:        throw new IllegalArgumentException("Unmapped ring wait: " + wait);
        }
    }
}
