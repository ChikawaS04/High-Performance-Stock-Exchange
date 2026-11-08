package http;

/**
 * Ends a request with a fixed answer, such as 400 for a bad parameter or 404 for an unknown
 * participant. Carries no message, so nothing from the request can reach a log or a body.
 */
final class ApiException extends RuntimeException {

    private final ApiResponse response;

    ApiException(ApiResponse response) {
        super(null, null, false, false);
        this.response = response;
    }

    ApiResponse response() {
        return response;
    }

    static ApiException badRequest() {
        return new ApiException(ApiResponse.BAD_REQUEST);
    }

    static ApiException notFound() {
        return new ApiException(ApiResponse.NOT_FOUND);
    }
}
