package market;

import net.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /market/ignition} over real sockets (P16-3): the router on the server's one port, the fetch
 * thread and a local Alpaca stub. A server built with no gateway or status serves only HTTP here.
 */
class IgnitionEndpointTest {

    private static final long AWAIT_S = 10L;
    private static final String MARKER = "UPSTREAM-SECRET-MARKER";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(AWAIT_S)).build();
    private AlpacaStub stub;
    private WebSocketServer server;

    @BeforeEach
    void setUp() throws Exception {
        stub = new AlpacaStub();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        stub.release();
        if (server != null) server.stop();   // closes the ignition service
        stub.close();
    }

    private IgnitionService serve() throws InterruptedException {
        IgnitionService service = new IgnitionService("ASML", AlpacaStub.KEY_ID, AlpacaStub.SECRET, stub.baseUrl(),
                new MutableClock(Instant.parse("2026-11-18T15:00:00Z")));
        server = new WebSocketServer(0, null, System::nanoTime, null, null, null, service);
        server.start();
        service.start();
        return service;
    }

    private HttpResponse<String> send(String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.boundPort() + IgnitionService.PATH))
                .timeout(Duration.ofSeconds(AWAIT_S))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theRouteAnswersAt503WhileTheUpstreamCallIsInFlightThen200() throws Exception {
        stub.holdRequests();
        stub.answer(200, AlpacaStub.snapshot("149.8", "2026-11-18T05:00:00Z"));
        IgnitionService service = serve();
        IgnitionServiceTest.await(() -> stub.calls() == 1, "the upstream call to start");

        // The fetch thread is blocked upstream; the event loop answers anyway.
        HttpResponse<String> waiting = send("GET");
        assertEquals(503, waiting.statusCode());
        assertEquals("{\"error\":\"UPSTREAM_UNAVAILABLE\"}", waiting.body());
        String retryAfter = waiting.headers().firstValue("Retry-After").orElse(null);
        assertNotNull(retryAfter);
        int seconds = Integer.parseInt(retryAfter);
        assertTrue(seconds >= 1 && seconds <= 60, retryAfter);
        assertEquals("no-store", waiting.headers().firstValue("Cache-Control").orElse(null));

        stub.release();
        IgnitionServiceTest.await(() -> service.body() != null, "the value");
        HttpResponse<String> ready = send("GET");
        assertEquals(200, ready.statusCode());
        assertEquals("{\"symbol\":\"ASML\",\"openPx\":1498000,\"date\":20261118}", ready.body());
        assertEquals("application/json", ready.headers().firstValue("Content-Type").orElse(null));
        assertEquals("no-store", ready.headers().firstValue("Cache-Control").orElse(null));
        assertEquals(1, stub.calls());
    }

    @Test
    void anUpstreamErrorBodyNeverReachesTheClient() throws Exception {
        stub.answer(401, "{\"message\":\"" + MARKER + "\"}");
        IgnitionService service = serve();
        IgnitionServiceTest.await(() -> service.attempts() == 1 && service.evaluations() >= 1, "the failed attempt");
        HttpResponse<String> response = send("GET");
        assertEquals(503, response.statusCode());
        assertEquals("{\"error\":\"UPSTREAM_UNAVAILABLE\"}", response.body());
        assertFalse(response.headers().map().toString().contains(MARKER));
    }

    @Test
    void malformedUpstreamJsonIsA503NotA500() throws Exception {
        stub.answer(200, "<html>" + MARKER + "</html>");
        IgnitionService service = serve();
        IgnitionServiceTest.await(() -> service.attempts() == 1 && service.evaluations() >= 1, "the attempt");
        HttpResponse<String> response = send("GET");
        assertEquals(503, response.statusCode());
        assertFalse(response.body().contains(MARKER));
    }

    @Test
    void anotherMethodIs405() throws Exception {
        stub.answer(200, AlpacaStub.snapshot("149.8", "2026-11-18T05:00:00Z"));
        serve();
        HttpResponse<String> response = send("POST");
        assertEquals(405, response.statusCode());
        assertEquals("GET", response.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void withoutAServiceTheRouteIs404() throws Exception {
        server = new WebSocketServer(0, null, System::nanoTime, null, null, null, null);
        server.start();
        HttpResponse<String> response = send("GET");
        assertEquals(404, response.statusCode());
        assertEquals("{\"error\":\"NOT_FOUND\"}", response.body());
    }
}
