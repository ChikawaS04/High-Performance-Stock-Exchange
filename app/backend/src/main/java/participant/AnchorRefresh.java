package participant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * Re-reads the day's open at each live session open (P16-5) and hands it to a strategy's
 * {@link Strategy#onAnchor} (only the market maker uses it, as its anchor target). The band, the
 * limits and every other setting stay as built at start.
 *
 * <p>The read runs on {@code worker}, never on a socket or client thread; the answer is delivered
 * on the client's own executor, so the strategy stays single-thread confined. A failed read keeps
 * the old anchor and logs one WARN.
 */
final class AnchorRefresh {

    private static final Logger log = LoggerFactory.getLogger(AnchorRefresh.class);

    private final OpenPriceSource.Fetcher fetcher;
    private final ObjectMapper mapper;
    private final Executor worker;

    AnchorRefresh(OpenPriceSource.Fetcher fetcher, ObjectMapper mapper, Executor worker) {
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.worker = worker;
    }

    /** Read once on the worker; on success, deliver to {@code target} on {@code deliverOn}. */
    void request(String name, Executor deliverOn, Strategy target) {
        worker.execute(() -> {
            long units;
            try {
                units = OpenPriceSource.readOnce(fetcher, mapper);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (units > 0) {
                deliverOn.execute(() -> target.onAnchor(units));
                log.info("{}: session open; anchor target from /market/ignition is {} units", name, units);
            } else {
                log.warn("{}: session open, but /market/ignition gave no open; keeping the current anchor", name);
            }
        });
    }
}
