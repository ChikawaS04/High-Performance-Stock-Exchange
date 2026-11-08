package http;

import java.nio.charset.StandardCharsets;

/**
 * What an {@code /api} route answers: an HTTP status, a JSON body, and a {@code Retry-After} in seconds
 * (0 means none). Error bodies are fixed and never echo the request.
 */
public record ApiResponse(int status, byte[] body, int retryAfterSeconds) {

    public static final ApiResponse BAD_REQUEST = error(400, "BAD_REQUEST", 0);
    public static final ApiResponse NOT_FOUND = error(404, "NOT_FOUND", 0);
    public static final ApiResponse INTERNAL = error(500, "INTERNAL", 0);
    public static final ApiResponse OVERFLOW = error(500, "OVERFLOW", 0);
    /** The executor holds as many requests as it may; try again shortly. */
    public static final ApiResponse BUSY = error(503, "BUSY", 1);
    /** The database cannot be reached, or a query ran past its timeout. */
    public static final ApiResponse DATABASE_UNAVAILABLE = error(503, "DATABASE_UNAVAILABLE", 5);

    public static ApiResponse ok(byte[] body) {
        return new ApiResponse(200, body, 0);
    }

    private static ApiResponse error(int status, String code, int retryAfterSeconds) {
        return new ApiResponse(status, ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8), retryAfterSeconds);
    }
}
