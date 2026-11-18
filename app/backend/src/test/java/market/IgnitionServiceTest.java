package market;

import config.ExchangeConfig;
import io.netty.util.concurrent.FastThreadLocalThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IgnitionService} against a local Alpaca stub with a controlled clock (P16-3). Every wait is
 * bounded; "no further call" is asserted only after the fetch thread has provably re-evaluated its
 * schedule ({@link IgnitionService#evaluations()}).
 */
class IgnitionServiceTest {

    private static final long AWAIT_MS = 10_000L;
    /** 10:00 New York on 2026-11-18. */
    private static final Instant MORNING = Instant.parse("2026-11-18T15:00:00Z");
    private static final String TODAY_BAR = "2026-11-18T05:00:00Z";

    private AlpacaStub stub;
    private MutableClock clock;
    private IgnitionService service;

    @BeforeEach
    void setUp() throws Exception {
        stub = new AlpacaStub();
        clock = new MutableClock(MORNING);
    }

    @AfterEach
    void tearDown() {
        stub.release();
        if (service != null) service.close();
        stub.close();
    }

    private IgnitionService start() {
        service = new IgnitionService("ASML", AlpacaStub.KEY_ID, AlpacaStub.SECRET, stub.baseUrl(), clock);
        service.start();
        return service;
    }

    static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + AWAIT_MS * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }

    /** Wakes the thread and waits until it has looked at its schedule again. */
    private void wakeAndSettle() {
        long seen = service.evaluations();
        service.requestFetch();
        await(() -> service.evaluations() > seen, "the fetch thread to re-evaluate");
    }

    private String body() {
        byte[] b = service.body();
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void oneUpstreamCallServesManyReadsOnTheSameDate() {
        stub.answer(200, AlpacaStub.snapshot("149.8", TODAY_BAR));
        start();
        await(() -> service.body() != null, "the first value");
        assertEquals("{\"symbol\":\"ASML\",\"openPx\":1498000,\"date\":20261118}", body());
        for (int i = 0; i < 100; i++) {
            assertNotNull(service.body());
        }
        clock.advanceMillis(3L * 60L * 60_000L);   // later the same day
        wakeAndSettle();
        wakeAndSettle();
        assertEquals(1, stub.calls());
        assertEquals(1L, service.attempts());
    }

    @Test
    void aNewNewYorkDateFetchesAgain() {
        stub.answer(200, AlpacaStub.snapshot("149.8", TODAY_BAR));
        start();
        await(() -> service.body() != null, "the first value");
        stub.answer(200, AlpacaStub.snapshot("151.25", "2026-11-19T05:00:00Z"));
        clock.set(Instant.parse("2026-11-19T05:00:30Z"));   // 00:00:30 New York on the 19th
        service.requestFetch();
        await(() -> body().contains("20261119"), "the next day's value");
        assertEquals("{\"symbol\":\"ASML\",\"openPx\":1512500,\"date\":20261119}", body());
        assertEquals(2, stub.calls());
    }

    @Test
    void aStaleBarIsRefetchedEveryFifteenMinutesUntilTodaysArrives() {
        stub.answer(200, AlpacaStub.snapshot("149.8", "2026-11-17T05:00:00Z"));
        start();
        await(() -> service.body() != null, "yesterday's value");
        assertTrue(body().contains("20261117"), "served while today's bar is not out yet");
        clock.advanceMillis(14L * 60_000L);
        wakeAndSettle();
        assertEquals(1, stub.calls(), "not before 15 minutes");
        stub.answer(200, AlpacaStub.snapshot("150.1", TODAY_BAR));
        clock.advanceMillis(60_000L);
        service.requestFetch();
        await(() -> body().contains("20261118"), "today's value");
        assertEquals(2, stub.calls());
    }

    @Test
    void anUpstreamFailureIsRetriedNoSoonerThanSixtySeconds() {
        stub.answer(500, "{\"message\":\"UPSTREAM-SECRET-MARKER\"}");
        start();
        await(() -> service.attempts() == 1 && service.evaluations() >= 1, "the first attempt");
        assertNull(service.body());
        assertEquals(60, service.retryAfterSeconds());
        for (int i = 0; i < 5; i++) {
            wakeAndSettle();   // as five browsers hitting an empty cache would
        }
        clock.advanceMillis(59_000L);
        wakeAndSettle();
        assertEquals(1, stub.calls(), "no second call within 60 s");
        assertEquals(1, service.retryAfterSeconds());
        clock.advanceMillis(1_000L);
        service.requestFetch();
        await(() -> stub.calls() == 2, "the retry at 60 s");
    }

    @Test
    void malformedUpstreamJsonLeavesTheCacheEmpty() {
        stub.answer(200, "{\"dailyBar\":{\"o\":");
        start();
        await(() -> service.attempts() == 1 && service.evaluations() >= 1, "the attempt");
        assertNull(service.body());
    }

    @Test
    void anOversizedBodyIsAFailure() {
        stub.answer(200, "{\"pad\":\"" + "x".repeat(IgnitionService.MAX_BODY_BYTES) + "\"}");
        start();
        await(() -> service.attempts() == 1 && service.evaluations() >= 1, "the attempt");
        assertNull(service.body());
    }

    @Test
    void aFailedRefetchKeepsTheOldValue() {
        stub.answer(200, AlpacaStub.snapshot("149.8", "2026-11-17T05:00:00Z"));
        start();
        await(() -> service.body() != null, "the first value");
        String before = body();
        stub.answer(503, "{}");
        clock.advanceMillis(15L * 60_000L);
        service.requestFetch();
        await(() -> stub.calls() == 2 && service.evaluations() >= 2, "the failed refetch");
        assertEquals(before, body());
    }

    @Test
    void theRequestCarriesTheKeysToTheSnapshotPath() {
        stub.answer(200, AlpacaStub.snapshot("149.8", TODAY_BAR));
        start();
        await(() -> service.body() != null, "the value");
        assertEquals(AlpacaStub.KEY_ID, stub.lastKeyId);
        assertEquals(AlpacaStub.SECRET, stub.lastSecret);
        assertEquals("/v2/stocks/ASML/snapshot", stub.lastPath);
        assertEquals("feed=iex", stub.lastQuery);
    }

    @Test
    void theFetchRunsOnItsOwnThreadNeverAnEventLoop() {
        stub.answer(200, AlpacaStub.snapshot("149.8", TODAY_BAR));
        start();
        await(() -> service.body() != null, "the value");
        Thread fetcher = service.lastFetchThread();
        assertEquals(IgnitionService.THREAD_NAME, fetcher.getName());
        assertFalse(fetcher instanceof FastThreadLocalThread, "Netty event loops run on FastThreadLocalThreads");
        assertTrue(fetcher.isDaemon());
        service.close();
        assertFalse(fetcher.isAlive(), "close stops the thread");
    }

    @Test
    void closeInterruptsACallInProgress() {
        stub.holdRequests();
        stub.answer(200, AlpacaStub.snapshot("149.8", TODAY_BAR));
        start();
        await(() -> stub.calls() == 1, "the call to start");
        Thread fetcher = service.lastFetchThread();
        service.close();
        assertFalse(fetcher.isAlive(), "a blocked upstream call does not hold close");
    }

    @Test
    void forVenueIsNullWithoutKeys() {
        assertNull(IgnitionService.forVenue(ExchangeConfig.from(Map.of())));
        IgnitionService withKeys = IgnitionService.forVenue(ExchangeConfig.from(Map.of(
                "ALPACA_KEY_ID", AlpacaStub.KEY_ID, "ALPACA_SECRET_KEY", AlpacaStub.SECRET)));
        assertNotNull(withKeys);
        withKeys.close();   // never started: close returns at once
    }
}
