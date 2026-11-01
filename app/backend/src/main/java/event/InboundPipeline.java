package event;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.concurrent.ThreadFactory;

/**
 * Owns the inbound Disruptor: gateway (producer) -> matching engine (consumer).
 * SRS §3.2. Single source of truth for ring size and wait strategy.
 *
 * Producer type is SINGLE — the gateway is the sole producer (single-writer
 * principle, SRS §5.2), which lets the Disruptor skip multi-producer CAS on
 * publish. The consumer is attached at construction; Step 6.5 passes a test
 * handler, Step 7.5 will pass the real MatchingEngine handler. Nothing else
 * about this class changes when the handler is swapped.
 *
 * The RingBuffer this exposes is what Step 7's OrderGateway produces into.
 *
 * P15-4: the live consumer is the journal sequencer, installed with a fail-stop exception
 * handler, because the Disruptor's default handler kills the consumer thread and leaves the
 * gateway blocked on a full ring with no exit.
 */
public final class InboundPipeline {

    /**
     * Power-of-two ring size. 1024 slots is generous headroom for a single-
     * symbol demo book; this is the knob to revisit under JMH in Phase 6 if
     * the producer ever out-runs the engine.
     */
    public static final int RING_SIZE = 1024;

    private final Disruptor<OrderEvent> disruptor;

    /** Canonical constructor: default ring size. */
    public InboundPipeline(EventHandler<OrderEvent> consumer) {
        this(RING_SIZE, consumer);
    }

    /**
     * Default ring size and an explicit exception handler for the consumer. The handler must not
     * return normally from handleEventException unless skipping the slot is acceptable; the
     * journaled pipeline's handler halts the venue or rethrows.
     */
    public InboundPipeline(EventHandler<OrderEvent> consumer, ExceptionHandler<OrderEvent> exceptionHandler) {
        this(RING_SIZE, consumer);
        disruptor.setDefaultExceptionHandler(exceptionHandler);
    }

    /**
     * Explicit-ring-size constructor. Package-private: tests use it to force
     * slot wrap-around on a tiny ring; production uses the canonical one.
     */
    InboundPipeline(int ringSize, EventHandler<OrderEvent> consumer) {
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "inbound-consumer");
            t.setDaemon(true); // a forgotten shutdown() must not wedge the JVM
            return t;
        };

        disruptor = new Disruptor<>(
                new OrderEventFactory(),
                ringSize,
                threadFactory,
                ProducerType.SINGLE,       // gateway is the only producer (SRS §5.2)
                new BlockingWaitStrategy()  // demo default; revisit under JMH (Phase 6)
        );
        disruptor.handleEventsWith(consumer);
    }

    /** The buffer Step 7's gateway produces into. */
    public RingBuffer<OrderEvent> getRingBuffer() {
        return disruptor.getRingBuffer();
    }

    /** Starts the consumer thread. Call before publishing. */
    public void start() {
        disruptor.start();
    }

    /**
     * True once the consumer has processed everything published. Graceful shutdown drains by
     * this, not by {@link #shutdown()} alone (P15-3 finding).
     */
    public boolean isDrained() {
        RingBuffer<OrderEvent> ring = disruptor.getRingBuffer();
        return ring.getMinimumGatingSequence() >= ring.getCursor();
    }

    /** Drains outstanding events and stops the consumer thread. */
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