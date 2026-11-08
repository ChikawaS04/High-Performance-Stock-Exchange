package http;

import java.util.List;
import java.util.Map;

/**
 * One {@code /api} GET request, copied off the Netty event loop's request object before it is released:
 * the path without its query string, and the decoded query parameters in the order they arrived.
 * Holds no Netty type, so this package never imports {@code io.netty}.
 */
public record ApiRequest(String path, Map<String, List<String>> parameters) {

    public ApiRequest {
        parameters = Map.copyOf(parameters);
    }
}
