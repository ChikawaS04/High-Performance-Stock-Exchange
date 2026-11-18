package market;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for Alpaca's snapshot endpoint (P16-3 tests): answers every request with the
 * configured status and body, records what it was sent, and can hold a request open until released.
 * Test credentials only; no real key ever reaches it.
 */
final class AlpacaStub implements AutoCloseable {

    static final String KEY_ID = "test-key-id";
    static final String SECRET = "test-secret";

    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile byte[] body = new byte[0];
    private volatile CountDownLatch hold;
    volatile String lastKeyId;
    volatile String lastSecret;
    volatile String lastPath;
    volatile String lastQuery;

    AlpacaStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** A snapshot whose daily bar opens at {@code open} dollars and starts at the instant {@code t}. */
    static String snapshot(String open, String t) {
        return "{\"symbol\":\"ASML\",\"dailyBar\":{\"t\":\"" + t + "\",\"o\":" + open + ",\"c\":1.0}}";
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    void answer(int status, String body) {
        this.body = body.getBytes(StandardCharsets.UTF_8);
        this.status = status;
    }

    /** Requests from now on wait until {@link #release()}. */
    void holdRequests() {
        hold = new CountDownLatch(1);
    }

    void release() {
        CountDownLatch h = hold;
        if (h != null) h.countDown();
    }

    int calls() {
        return calls.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastKeyId = exchange.getRequestHeaders().getFirst("APCA-API-KEY-ID");
        lastSecret = exchange.getRequestHeaders().getFirst("APCA-API-SECRET-KEY");
        lastPath = exchange.getRequestURI().getPath();
        lastQuery = exchange.getRequestURI().getQuery();
        calls.incrementAndGet();
        CountDownLatch h = hold;
        if (h != null) {
            try {
                if (!h.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("stub was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] b = body;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(b);
        }
    }

    @Override
    public void close() {
        release();
        server.stop(0);
    }
}
