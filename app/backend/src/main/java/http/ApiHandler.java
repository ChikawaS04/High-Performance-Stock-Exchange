package http;

import java.util.function.Consumer;

/**
 * What the HTTP router calls for {@code /api} (SRS 3.6). The router owns the Netty side; this side
 * sees only plain requests and byte bodies.
 */
public interface ApiHandler extends AutoCloseable {

    /** True if {@code path} (no query string) is one of the routes; anything else under /api is a 404. */
    boolean isRoute(String path);

    /**
     * Starts a GET of a route. Called on the event loop; never blocks and never touches the database.
     *
     * @param request the copied request
     * @param done    receives the answer later, on an executor thread, when this returns null
     * @return the answer now (a bad parameter, or a full executor), or null if {@code done} will get it
     */
    ApiResponse handle(ApiRequest request, Consumer<ApiResponse> done);

    /** Stops the executor, bounded. Requests not yet answered never are. */
    @Override
    void close();
}
