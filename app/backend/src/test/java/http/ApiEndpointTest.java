package http;

import db.RequiresTestDatabase;
import db.TestDatabase;
import io.netty.util.concurrent.FastThreadLocalThread;
import net.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /api over real sockets on the server's one port (P15-12): the router, the executor and the answers
 * coming back through the event loop. A server built with no gateway or status serves only HTTP here.
 */
class ApiEndpointTest {

    private static final long AWAIT_S = 10L;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(AWAIT_S)).build();
    private final Set<Thread> queryThreads = ConcurrentHashMap.newKeySet();
    private WebSocketServer server;

    @AfterEach
    void stop() throws InterruptedException {
        if (server != null) server.stop();
    }

    private int start(ApiHandler api) throws InterruptedException {
        server = new WebSocketServer(0, null, System::nanoTime, null, null, api);
        server.start();
        return server.boundPort();
    }

    private HttpResponse<String> send(int port, String method, String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(AWAIT_S))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static ApiService.ConnectionSource down() {
        return () -> {
            throw new SQLException("down", "08001");
        };
    }

    /** Records the thread of every query and refuses it as a database that is down. */
    private ApiService recordingThreads() {
        return new ApiService(down(), () -> queryThreads.add(Thread.currentThread()), ApiService.THREADS,
                ApiService.MAX_PENDING);
    }

    @Test
    void errorsUnderApiOverHttp() throws Exception {
        int port = start(recordingThreads());
        HttpResponse<String> notFound = send(port, "GET", "/api/trades/");
        assertEquals(404, notFound.statusCode());
        assertEquals("{\"error\":\"NOT_FOUND\"}", notFound.body());
        HttpResponse<String> notAllowed = send(port, "POST", "/api/trades?date=20261107");
        assertEquals(405, notAllowed.statusCode());
        assertEquals("GET", notAllowed.headers().firstValue("Allow").orElse(null));
        HttpResponse<String> bad = send(port, "GET", "/api/trades?date=2026-11-07");
        assertEquals(400, bad.statusCode());
        assertEquals("{\"error\":\"BAD_REQUEST\"}", bad.body(), "the bad value is never echoed");
        HttpResponse<String> down = send(port, "GET", "/api/trades?date=20261107");
        assertEquals(503, down.statusCode());
        assertEquals("{\"error\":\"DATABASE_UNAVAILABLE\"}", down.body());
        assertEquals("5", down.headers().firstValue("Retry-After").orElse(null));
        assertEquals("no-store", down.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void noQueryEverRunsOnAnEventLoop() throws Exception {
        int port = start(recordingThreads());
        for (String path : List.of("/api/trades?date=20261107", "/api/reports/daily?date=20261107",
                "/api/reports/volume?from=20261101&to=20261107", "/api/reports/statement?participantId=1&date=20261107",
                "/api/candles?symbol=ASML&date=20261107&interval=5")) {
            assertEquals(503, send(port, "GET", path).statusCode(), path);
        }
        assertFalse(queryThreads.isEmpty());
        for (Thread t : queryThreads) {
            assertFalse(t instanceof FastThreadLocalThread, t.getName() + " is a Netty thread");
            assertTrue(t.getName().startsWith("api-"), t.getName());
        }
    }

    @Test
    void withoutAnApiEveryPathUnderItIs404() throws Exception {
        int port = start(null);
        assertEquals(404, send(port, "GET", "/api/trades?date=20261107").statusCode());
        assertEquals(404, send(port, "POST", "/api/trades?date=20261107").statusCode());
    }

    /**
     * Two requests in one write on one keep-alive connection: the /api answer is held back on the
     * executor, and the second request (answered at once by the router) must still come second.
     */
    @Test
    void pipelinedRequestsAreAnsweredInOrder() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ApiService api = new ApiService(down(), () -> {
            started.countDown();
            try {
                assertTrue(release.await(AWAIT_S, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 1, 4);
        int port = start(api);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(AWAIT_S));
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/trades?date=20261107 HTTP/1.1\r\nHost: x\r\n\r\n"
                    + "GET /nope HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertTrue(started.await(AWAIT_S, TimeUnit.SECONDS), "the /api query started");
            InputStream in = socket.getInputStream();
            socket.setSoTimeout(300);
            assertThrows(SocketTimeoutException.class, in::read, "nothing is answered while /api is outstanding");
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(AWAIT_S));
            release.countDown();
            String first = readResponse(in);
            String second = readResponse(in);
            assertTrue(first.startsWith("HTTP/1.1 503"), first);
            assertTrue(first.endsWith("{\"error\":\"DATABASE_UNAVAILABLE\"}"), first);
            assertTrue(second.startsWith("HTTP/1.1 404"), second);
        }
    }

    /** Reads one response: headers, then exactly Content-Length bytes of body. */
    private static String readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("connection closed mid-response: " + head);
            head.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String headers = head.toString(StandardCharsets.US_ASCII);
        int length = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        byte[] body = in.readNBytes(length);
        assertEquals(length, body.length);
        return headers + new String(body, StandardCharsets.UTF_8);
    }

    @Test
    @RequiresTestDatabase
    void aRouteReadsTheDatabaseOverHttp() throws Exception {
        TestDatabase.cleanAndMigrate();
        int port = start(new ApiService(TestDatabase.database()::connect, () -> queryThreads.add(Thread.currentThread()),
                ApiService.THREADS, ApiService.MAX_PENDING));
        HttpResponse<String> r = send(port, "GET", "/api/trades?date=20261107");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("application/json", r.headers().firstValue("Content-Type").orElse(null));
        assertEquals("{\"businessDate\":20261107,\"dayStatus\":\"NONE\",\"asOfOutboundSeq\":0,\"trades\":[],"
                + "\"nextAfterSeq\":null}", r.body());
        assertTrue(queryThreads.stream().noneMatch(t -> t instanceof FastThreadLocalThread));
    }
}
