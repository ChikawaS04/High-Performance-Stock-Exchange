package market;

/**
 * What {@code GET /market/ignition} reads (P16-3). Every method is called on the Netty event loop, so
 * each is a non-blocking read of state another thread publishes.
 */
public interface IgnitionSource {

    /** The cached response body, or null while nothing is cached. Never modified once returned. */
    byte[] body();

    /** Seconds until the next upstream call may start, 1 to 60: the {@code Retry-After} of a 503. */
    int retryAfterSeconds();

    /** Wakes the fetch thread; it still calls upstream only when its schedule allows. Never blocks. */
    void requestFetch();
}
