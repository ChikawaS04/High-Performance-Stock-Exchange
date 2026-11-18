package event;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.WaitStrategy;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbound Disruptor wiring (SRS §3.4): single producer (the matching-engine
 * adapter), multiple independent consumers each with its own sequence counter.
 * Adding a subscriber is handleEventsWith(...) — no engine change.
 *
 * BlockingWaitStrategy is the default (P16-2 makes it a setting). Ring size 1024
 * matches the inbound side.
 *
 * P15-4: a consumer exception is logged and that consumer moves on to the next event
 * ({@link LogAndContinueExceptionHandler}), so a failing subscriber can never stall the engine.
 *
 * P15-6: a consumer whose loss is unacceptable (the outbound recorder) is registered with its own
 * policy ({@link #handleEventsWith(EventHandler, ExceptionHandler)}), typically
 * {@link FailStopExceptionHandler}; every other consumer keeps the log-and-continue default.
 *
 * P16-2: the consumers' wait strategy is a constructor argument ({@code RING_WAIT_STRATEGY}); the
 * no-argument constructor keeps {@link BlockingWaitStrategy}. Consumer threads are named
 * {@value #THREAD_NAME_PREFIX}1, 2 and so on, in the order the consumers were registered.
 */
public final class OutboundPipeline {

    private static final int RING_SIZE = 1024;

    private final Disruptor<ExecutionEvent> disruptor;
    private final RingBuffer<ExecutionEvent> ringBuffer;

    /** Consumer threads are named this plus 1, 2, ... in registration order. */
    public static final String THREAD_NAME_PREFIX = "outbound-consumer-";

    public OutboundPipeline() {
        this(new BlockingWaitStrategy());
    }

    /** With the wait strategy every consumer of this ring uses. */
    public OutboundPipeline(WaitStrategy waitStrategy) {
        disruptor = new Disruptor<>(
                new ExecutionEventFactory(),
                RING_SIZE,
                namedDaemonThreads(),
                ProducerType.SINGLE,
                waitStrategy
        );
        disruptor.setDefaultExceptionHandler(new LogAndContinueExceptionHandler<>(
                "outbound", e -> "eventType=" + e.eventType + " seq=" + e.seq + " orderId=" + e.orderId));
        ringBuffer = disruptor.getRingBuffer();
    }

    /** Register one or more independent subscribers (each gets its own sequence). */
    @SafeVarargs
    public final void handleEventsWith(EventHandler<ExecutionEvent>... handlers) {
        disruptor.handleEventsWith(handlers);
    }

    /**
     * Register one subscriber with its own exception policy, overriding the ring's
     * log-and-continue default for this consumer only (Disruptor {@code handleExceptionsFor}).
     */
    public void handleEventsWith(EventHandler<ExecutionEvent> handler,
                                 ExceptionHandler<? super ExecutionEvent> exceptionHandler) {
        disruptor.handleEventsWith(handler);
        disruptor.handleExceptionsFor(handler).with(exceptionHandler);
    }

    /** Daemon threads, so a forgotten shutdown cannot wedge the JVM, each with a numbered name. */
    private static ThreadFactory namedDaemonThreads() {
        AtomicInteger count = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, THREAD_NAME_PREFIX + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public void start() {
        disruptor.start();
    }

    public RingBuffer<ExecutionEvent> getRingBuffer() {
        return ringBuffer;
    }

    /**
     * True once every registered consumer has processed everything published. Graceful shutdown
     * drains by this, not by {@code Disruptor.shutdown()}, which skips a consumer whose thread
     * is not running (P15-3 finding).
     */
    public boolean isDrained() {
        return ringBuffer.getMinimumGatingSequence() >= ringBuffer.getCursor();
    }

    public void shutdown() {
        disruptor.shutdown();
    }

    /**
     * Stops the consumer threads without waiting for a backlog. For use after draining by
     * {@link #isDrained()}, so shutdown can never block on a consumer that cannot finish.
     */
    public void halt() {
        disruptor.halt();
    }
}