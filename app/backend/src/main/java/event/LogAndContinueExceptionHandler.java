package event;

import com.lmax.disruptor.ExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Exception policy for the outbound and snapshot rings: log at ERROR and carry on with the
 * next event. A publisher or TradeLogger exception must never kill a consumer thread, because
 * a dead consumer stops advancing its sequence, the ring fills, and the engine blocks on its
 * next publish. Losing one WebSocket frame or log line is acceptable: the journal is the record
 * and clients recover by snapshot.
 *
 * Not for a consumer whose loss is unacceptable: the outbound recorder (P15-6) is registered
 * with {@link FailStopExceptionHandler} instead, overriding this default for that consumer only.
 * Runs on the failing consumer's thread, on the error path only, so allocating the description
 * and logging here is off the hot path.
 */
public final class LogAndContinueExceptionHandler<T> implements ExceptionHandler<T> {

    private static final Logger log = LoggerFactory.getLogger(LogAndContinueExceptionHandler.class);

    private final String ring;
    private final Function<? super T, String> describe;

    /**
     * @param ring     the ring's name, for the log line
     * @param describe renders the identifying fields of the event that failed
     */
    public LogAndContinueExceptionHandler(String ring, Function<? super T, String> describe) {
        this.ring = ring;
        this.describe = describe;
    }

    @Override
    public void handleEventException(Throwable ex, long sequence, T event) {
        String what;
        try {
            what = event == null ? "null event" : describe.apply(event);
        } catch (RuntimeException describeFailure) {
            what = "event not describable: " + describeFailure;
        }
        log.error("{} ring consumer failed at ring sequence {} ({}); event skipped for that consumer only",
                ring, sequence, what, ex);
    }

    @Override
    public void handleOnStartException(Throwable ex) {
        log.error("{} ring consumer failed on start", ring, ex);
    }

    @Override
    public void handleOnShutdownException(Throwable ex) {
        log.error("{} ring consumer failed on shutdown", ring, ex);
    }
}
