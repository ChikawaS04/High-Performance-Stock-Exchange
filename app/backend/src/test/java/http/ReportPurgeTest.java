package http;

import clearing.DatabaseRetention;
import clearing.DatabaseRetentionTiming;
import db.RequiresTestDatabase;
import db.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import util.DatabaseRetentionStatus;
import util.ReporterStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static http.ReportSeed.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The /api routes before and after database retention purges a date (P16-8): trades and statements answer
 * 410 PURGED; daily, volume and candles answer byte for byte as before, including a COMPLETE date with no
 * trades and a range that is partly purged.
 *
 * <p>The seed: 20261102 has four fills over two symbols and three prices (one a house self-trade at a
 * sub-penny midpoint) and is COMPLETE; 20261103 closed with no trade and is COMPLETE; 20261104 is open with
 * two fills. With 0 days kept, the first two are purged.
 */
@RequiresTestDatabase
class ReportPurgeTest {

    private static final int DAY = 20261102;
    private static final int EMPTY = 20261103;
    private static final int OPEN = 20261104;
    /** 2026-11-02 00:00:00 UTC in epoch nanos. */
    private static final long B = 1_793_577_600L * 1_000_000_000L;
    private static final long MIN = 60_000_000_000L;

    private ApiService api;

    @BeforeEach
    void seed() {
        TestDatabase.cleanAndMigrate();
        api = new ApiService(TestDatabase.database()::connect);
        ReportSeed seed = new ReportSeed().brokers();
        seed.fill(DAY, "ASML", 1_000_000L, 3, "BUY", BROKER_A, BROKER_B);
        seed.accepted(DAY);
        seed.fill(DAY, "ASML", 1_000_100L, 2, "SELL", BROKER_B, BROKER_A);
        seed.fill(DAY, "NVDA", 500_050L, 1, "BUY", HOUSE, HOUSE);
        seed.fill(DAY, "NVDA", 1_000_000L, 4, "SELL", HOUSE, HOUSE);
        seed.close(DAY);
        complete(DAY, EMPTY, 4, 10);
        obligation(DAY, HOUSE, "NVDA", 5, 5, 0);
        obligation(DAY, BROKER_A, "ASML", 3, 2, 999_800L);
        obligation(DAY, BROKER_B, "ASML", 2, 3, -999_800L);
        balance(BROKER_A, 20261101, 100_000_000L);
        balance(BROKER_A, EMPTY, 99_000_200L);
        position(BROKER_A, 20261101, "ASML", 10);
        position(BROKER_A, EMPTY, "ASML", 11);
        house(EMPTY, "NVDA", 0);
        seed.close(EMPTY);
        complete(EMPTY, OPEN, 0, 0);
        seed.fill(OPEN, "ASML", 1_000_200L, 7, "BUY", BROKER_A, BROKER_B);
        seed.fill(OPEN, "NVDA", 1_000_300L, 1, "BUY", HOUSE, HOUSE);
        candle("ASML", DAY, B, 1_000_000, 1_000_100, 1_000_000, 1_000_100, 5, 2);
        candle("NVDA", DAY, B + 5 * MIN, 500_050, 1_000_000, 500_050, 1_000_000, 5, 2);
        seed.checkpoint(OPEN);
    }

    @AfterEach
    void close() {
        api.close();
    }

    private ApiResponse get(String path, String... pairs) {
        Map<String, List<String>> params = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.computeIfAbsent(pairs[i], k -> new ArrayList<>()).add(pairs[i + 1]);
        }
        CompletableFuture<ApiResponse> later = new CompletableFuture<>();
        ApiResponse now = api.handle(new ApiRequest(path, params), later::complete);
        if (now != null) return now;
        try {
            return later.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("no answer within 10 s for " + path, e);
        }
    }

    private String json(String path, String... pairs) {
        ApiResponse r = get(path, pairs);
        String body = new String(r.body(), StandardCharsets.UTF_8);
        assertEquals(200, r.status(), body);
        return body;
    }

    /** Every kept route's body for the purged dates and around them. */
    private List<String> keptBodies() {
        List<String> bodies = new ArrayList<>();
        bodies.add(json(ApiService.DAILY, "date", Integer.toString(DAY)));
        bodies.add(json(ApiService.DAILY, "date", Integer.toString(EMPTY)));
        bodies.add(json(ApiService.DAILY, "date", Integer.toString(OPEN)));
        bodies.add(json(ApiService.VOLUME, "from", "20261101", "to", Integer.toString(OPEN)));   // partly purged
        bodies.add(json(ApiService.VOLUME, "from", Integer.toString(DAY), "to", Integer.toString(EMPTY)));
        bodies.add(json(ApiService.VOLUME, "from", Integer.toString(DAY), "to", Integer.toString(OPEN), "symbol", "NVDA"));
        bodies.add(json(ApiService.CANDLES, "symbol", "ASML", "date", Integer.toString(DAY)));
        bodies.add(json(ApiService.CANDLES, "symbol", "NVDA", "date", Integer.toString(DAY), "interval", "15"));
        bodies.add(json(ApiService.TRADES, "date", Integer.toString(OPEN)));
        return bodies;
    }

    private static void purge() {
        ReporterStatus running = new ReporterStatus() {
            @Override public String state() { return "RUNNING"; }
            @Override public long inboundSeq() { return 0; }
            @Override public long outboundSeq() { return 0; }
        };
        DatabaseRetention retention = new DatabaseRetention(TestDatabase.database()::connect, () -> running, () -> null,
                true, 0, 5_000, true, System::nanoTime, DatabaseRetentionTiming.ON_REQUEST);
        retention.start();
        try {
            retention.requestRun();
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (retention.completedRuns() == 0) {
                if (System.nanoTime() > deadline) fail("no retention pass within 15 s");
                LockSupport.parkNanos(5_000_000L);
            }
            assertEquals(DatabaseRetentionStatus.PURGED, retention.status().outcome(),
                    String.valueOf(retention.status().skipReason()));
            assertEquals(EMPTY, retention.status().purgedThrough());
        } finally {
            assertTrue(retention.stop());
        }
    }

    @Test
    void keptRoutesAnswerTheSameBytesBeforeAndAfterAPurge() {
        List<String> before = keptBodies();
        // The daily report of the purged day carries real figures, so the comparison is not of empty lists.
        assertTrue(before.get(0).contains("\"symbol\":\"NVDA\",\"tradeCount\":2,\"volume\":5"), before.get(0));
        assertTrue(before.get(1).contains("\"symbols\":[]"), before.get(1));
        purge();
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM executions WHERE business_date IN (" + DAY + ", "
                + EMPTY + ")"), "the detail rows really are gone");
        List<String> after = keptBodies();
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i), after.get(i), "body " + i);
        }
    }

    @Test
    void tradesAndStatementsAnswer410ForAPurgedDate() {
        assertEquals(200, get(ApiService.TRADES, "date", Integer.toString(DAY)).status());
        assertEquals(200, get(ApiService.STATEMENT, "participantId", "2", "date", Integer.toString(DAY)).status());
        purge();
        for (String date : List.of(Integer.toString(DAY), Integer.toString(EMPTY))) {
            ApiResponse trades = get(ApiService.TRADES, "date", date, "afterSeq", "1");
            assertEquals(410, trades.status());
            assertEquals("{\"error\":\"PURGED\"}", new String(trades.body(), StandardCharsets.UTF_8));
            ApiResponse statement = get(ApiService.STATEMENT, "participantId", "2", "date", date);
            assertEquals(410, statement.status());
        }
        assertEquals(404, get(ApiService.STATEMENT, "participantId", "99", "date", Integer.toString(DAY)).status(),
                "an unknown participant is still 404");
        assertEquals(200, get(ApiService.STATEMENT, "participantId", "2", "date", Integer.toString(OPEN)).status());
    }
}
