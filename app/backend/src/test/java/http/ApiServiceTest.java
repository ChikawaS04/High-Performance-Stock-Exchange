package http;

import db.Database;
import db.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The /api service without a database (P15-12): routing, refusals, threading and failure mapping. */
class ApiServiceTest {

    private static final long AWAIT_S = 10L;

    private ApiService api;

    @AfterEach
    void close() {
        if (api != null) api.close();
    }

    private static ApiRequest request(String path, String... pairs) {
        Map<String, List<String>> params = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.computeIfAbsent(pairs[i], k -> new ArrayList<>()).add(pairs[i + 1]);
        }
        return new ApiRequest(path, params);
    }

    private static ApiService.ConnectionSource refusing(String sqlState) {
        return () -> {
            throw new SQLException("refused by the test", sqlState);
        };
    }

    private static ApiResponse await(CompletableFuture<ApiResponse> answer) {
        try {
            return answer.get(AWAIT_S, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("no answer within " + AWAIT_S + " s", e);
        }
    }

    @Test
    void theRoutesAreTheFixedSet() {
        api = new ApiService(refusing("08001"));
        for (String path : List.of("/api/trades", "/api/reports/daily", "/api/reports/volume", "/api/reports/statement",
                "/api/candles")) {
            assertTrue(api.isRoute(path), path);
        }
        for (String path : List.of("/api", "/api/", "/api/trades/", "/api/reports", "/api/Trades")) {
            assertFalse(api.isRoute(path), path);
        }
        assertSame(ApiResponse.NOT_FOUND, api.handle(request("/api/nope"), r -> fail("never queued")));
    }

    /** A bad parameter is answered on the caller's thread and never reaches the executor or the database. */
    @Test
    void badParametersAreRefusedAtOnceWithoutTheDatabase() {
        api = new ApiService(() -> fail("the database is never asked"), () -> fail("nothing is queued"), 1, 1);
        List<ApiRequest> bad = List.of(
                request("/api/trades"),
                request("/api/trades", "date", "20261107", "date", "20261108"),
                request("/api/trades", "date", "20261107", "extra", "1"),
                request("/api/trades", "date", "20261107", "limit", "1001"),
                request("/api/reports/daily", "date", "2026117"),
                request("/api/reports/volume", "from", "20261108", "to", "20261107"),
                request("/api/reports/volume", "from", "20250101", "to", "20261107"),
                request("/api/reports/statement", "date", "20261107"),
                request("/api/reports/statement", "participantId", "-1", "date", "20261107"),
                request("/api/candles", "date", "20261107"),
                request("/api/candles", "symbol", "ASML", "date", "20261107", "interval", "7"));
        for (ApiRequest r : bad) {
            assertSame(ApiResponse.BAD_REQUEST, api.handle(r, x -> fail("never queued")), r.toString());
        }
        assertEquals(0, api.pending());
    }

    @Test
    void queriesRunOnTheApiThreadsNeverTheCaller() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        api = new ApiService(refusing("08001"), () -> threads.add(Thread.currentThread().getName()), 2, 16);
        List<CompletableFuture<ApiResponse>> answers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            CompletableFuture<ApiResponse> answer = new CompletableFuture<>();
            assertNull(api.handle(request("/api/trades", "date", "20261107"), answer::complete));
            answers.add(answer);
        }
        answers.forEach(ApiServiceTest::await);
        assertFalse(threads.isEmpty());
        assertTrue(Set.of("api-1", "api-2").containsAll(threads), threads.toString());
    }

    @Test
    void anUnreachableDatabaseIs503Quickly() {
        Database unreachable = new Database(TestDatabase.UNREACHABLE_URL, "nobody", "nothing", Database.Timing.API);
        api = new ApiService(unreachable::connect);
        long t0 = System.nanoTime();
        CompletableFuture<ApiResponse> answer = new CompletableFuture<>();
        assertNull(api.handle(request("/api/reports/daily", "date", "20261107"), answer::complete));
        ApiResponse r = await(answer);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertSame(ApiResponse.DATABASE_UNAVAILABLE, r);
        assertEquals(503, r.status());
        assertEquals(5, r.retryAfterSeconds());
        assertTrue(ms < 5_000L, "answered in " + ms + " ms, within the 2 s connect bound plus margin");
    }

    /** One connection attempt per request: a database that stays down is never retried in a loop. */
    @Test
    void aDatabaseThatIsDownIsAskedOncePerRequest() {
        AtomicInteger attempts = new AtomicInteger();
        api = new ApiService(() -> {
            attempts.incrementAndGet();
            throw new SQLException("down", "08001");
        }, () -> { }, 1, 4);
        for (int i = 1; i <= 3; i++) {
            CompletableFuture<ApiResponse> answer = new CompletableFuture<>();
            assertNull(api.handle(request("/api/trades", "date", "20261107"), answer::complete));
            assertSame(ApiResponse.DATABASE_UNAVAILABLE, await(answer));
            assertEquals(i, attempts.get());
        }
    }

    @Test
    void aSaturatedExecutorIs503BusyAndRecovers() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bothBusy = new CountDownLatch(2);
        api = new ApiService(refusing("08001"), () -> {
            bothBusy.countDown();
            try {
                assertTrue(release.await(AWAIT_S, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 2, 4);
        List<CompletableFuture<ApiResponse>> answers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            CompletableFuture<ApiResponse> answer = new CompletableFuture<>();
            assertNull(api.handle(request("/api/trades", "date", "20261107"), answer::complete), "request " + i + " queued");
            answers.add(answer);
        }
        assertTrue(bothBusy.await(AWAIT_S, TimeUnit.SECONDS));
        ApiResponse busy = api.handle(request("/api/trades", "date", "20261107"), r -> fail("a refused request is never answered later"));
        assertSame(ApiResponse.BUSY, busy);
        assertEquals(503, busy.status());
        assertEquals(1, busy.retryAfterSeconds());
        release.countDown();
        for (CompletableFuture<ApiResponse> answer : answers) {
            assertSame(ApiResponse.DATABASE_UNAVAILABLE, await(answer));
        }
        CompletableFuture<ApiResponse> after = new CompletableFuture<>();
        assertNull(api.handle(request("/api/trades", "date", "20261107"), after::complete), "accepted again once drained");
        await(after);
    }

    @Test
    void aDatabaseThatRefusesForAnotherReasonIs500() {
        api = new ApiService(refusing("28P01"));   // bad password: waiting does not help
        CompletableFuture<ApiResponse> answer = new CompletableFuture<>();
        assertNull(api.handle(request("/api/trades", "date", "20261107"), answer::complete));
        assertSame(ApiResponse.INTERNAL, await(answer));
    }

    @Test
    void anUnexpectedFailureIsStillAnswered() {
        api = new ApiService(() -> {
            throw new IllegalStateException("expected by the test");
        });
        CompletableFuture<ApiResponse> answer = new CompletableFuture<>();
        assertNull(api.handle(request("/api/candles", "symbol", "ASML", "date", "20261107"), answer::complete));
        assertSame(ApiResponse.INTERNAL, await(answer));
    }
}
