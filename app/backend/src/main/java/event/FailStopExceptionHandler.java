package event;

import com.lmax.disruptor.ExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.FailStop;

/**
 * Exception policy for a ring consumer whose loss is unacceptable: the sequencer on the inbound
 * ring and the outbound recorder on the outbound ring (P15-6). Reports to the {@link FailStop},
 * then rethrows, so the Disruptor stops that consumer with the slot unconsumed even when the
 * FailStop returns (tests). Returning normally from {@link #handleEventException} would make the
 * processor skip the slot, which is exactly what must never happen to a journal writer.
 *
 * The opposite of {@link LogAndContinueExceptionHandler}; one ring can use both, one per
 * consumer ({@link OutboundPipeline#handleEventsWith(com.lmax.disruptor.EventHandler, ExceptionHandler)}).
 */
public final class FailStopExceptionHandler<T> implements ExceptionHandler<T> {

    private static final Logger log = LoggerFactory.getLogger(FailStopExceptionHandler.class);

    private final String component;
    private final FailStop failStop;

    /**
     * @param component the consumer's name, reported to the FailStop
     * @param failStop  what to do when the consumer cannot go on
     */
    public FailStopExceptionHandler(String component, FailStop failStop) {
        this.component = component;
        this.failStop = failStop;
    }

    @Override
    public void handleEventException(Throwable ex, long sequence, T event) {
        failStop.fatal(component, ex);
        if (ex instanceof RuntimeException runtime) throw runtime;
        if (ex instanceof Error error) throw error;
        throw new IllegalStateException(ex);
    }

    @Override
    public void handleOnStartException(Throwable ex) {
        failStop.fatal(component, ex);
    }

    @Override
    public void handleOnShutdownException(Throwable ex) {
        log.error("{} failed on shutdown", component, ex);
    }
}
