package event;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;

/**
 * Snapshot Disruptor wiring (Phase 4 decision 3): the second outbound buffer,
 * carrying bounded top-N depth snapshots from the matching engine to the depth
 * consumers (WebSocketPublisher BOOK frames, MarketDataService).
 *
 * Single producer — MatchingEngineHandler, the same object on the same thread
 * that produces the execution buffer — so this stays ProducerType.SINGLE and the
 * single-writer principle (SRS §5.2) holds across both outbound rings.
 *
 * Deliberately mirrors OutboundPipeline: same ring size, same wait strategy, same
 * lifecycle surface. BlockingWaitStrategy is a demo default (revisit under JMH).
 *
 * P15-4: a consumer exception is logged and that consumer moves on to the next snapshot
 * ({@link LogAndContinueExceptionHandler}), so a failing subscriber can never stall the engine.
 */
public final class SnapshotPipeline {

    private static final int RING_SIZE = 1024;

    private final Disruptor<BookSnapshotEvent> disruptor;
    private final RingBuffer<BookSnapshotEvent> ringBuffer;

    public SnapshotPipeline() {
        disruptor = new Disruptor<>(
                new BookSnapshotEventFactory(),
                RING_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new BlockingWaitStrategy()
        );
        disruptor.setDefaultExceptionHandler(new LogAndContinueExceptionHandler<>(
                "snapshot", s -> "BOOK lastSeq=" + s.lastSeq + " timestamp=" + s.timestamp));
        ringBuffer = disruptor.getRingBuffer();
    }

    /** Register one or more independent subscribers (each gets its own sequence). */
    @SafeVarargs
    public final void handleEventsWith(EventHandler<BookSnapshotEvent>... handlers) {
        disruptor.handleEventsWith(handlers);
    }

    public void start() {
        disruptor.start();
    }

    public RingBuffer<BookSnapshotEvent> getRingBuffer() {
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