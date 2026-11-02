package event;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;

/**
 * Outbound Disruptor wiring (SRS §3.4): single producer (the matching-engine
 * adapter), multiple independent consumers each with its own sequence counter.
 * Adding a subscriber is handleEventsWith(...) — no engine change.
 *
 * BlockingWaitStrategy is a demo default (revisit under JMH). Ring size 1024
 * matches the inbound side.
 *
 * P15-4: a consumer exception is logged and that consumer moves on to the next event
 * ({@link LogAndContinueExceptionHandler}), so a failing subscriber can never stall the engine.
 *
 * P15-6: a consumer whose loss is unacceptable (the outbound recorder) is registered with its own
 * policy ({@link #handleEventsWith(EventHandler, ExceptionHandler)}), typically
 * {@link FailStopExceptionHandler}; every other consumer keeps the log-and-continue default.
 */
public final class OutboundPipeline {

    private static final int RING_SIZE = 1024;

    private final Disruptor<ExecutionEvent> disruptor;
    private final RingBuffer<ExecutionEvent> ringBuffer;

    public OutboundPipeline() {
        disruptor = new Disruptor<>(
                new ExecutionEventFactory(),
                RING_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new BlockingWaitStrategy()
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