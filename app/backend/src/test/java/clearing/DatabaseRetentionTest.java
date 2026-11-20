package clearing;

import db.RequiresTestDatabase;
import db.TestDatabase;
import http.ApiRequest;
import http.ApiResponse;
import http.ApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import util.ClearingStatus;
import util.DatabaseRetentionStatus;
import util.ReporterStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static clearing.RetentionSeed.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Database retention against {@code exchange_test} (P16-8): what a pass deletes and what it keeps, the
 * checks that stop it, an interrupted pass, and the index use of an orders batch.
 *
 * <p>The seed (outbound seqs in brackets): business date D1 has a resting GTC order (1) [2], a fill of
 * orders 2 and 3 [3, 4], and an order 4 accepted on D1 [5] but filled on D3 [12]; D1 closes at [6], stamped
 * 60. Between D1 and D2 an order 5 is rejected while closed (no business date, stamped 65) [7]. D2 has a
 * fill of orders 6 and 7 [9, 10], closed at [11], stamped 110. D3 has order 8 filling order 4 [12], closed
 * at [13], stamped 130. D1 to D3 are COMPLETE; the checkpoint is at inbound 9, outbound 13.
 */
@RequiresTestDatabase
class DatabaseRetentionTest {

    static final int D1 = 20261102;
    static final int D2 = 20261103;
    static final int D3 = 20261104;
    static final int D4 = 20261105;

    private static final long AWAIT_MS = 15_000L;

    private volatile String reporterState = "RUNNING";
    private volatile String clearingState = "IDLE";

    @BeforeEach
    void seed() {
        TestDatabase.cleanAndMigrate();
        // D1
        order(1, 10, D1, "OPEN", 2, 10);
        accepted(2, 1, 10, D1, 20);
        audit("IN", 1, 1L, 10, "NEW_ORDER", D1, 10);
        audit("OUT", 2, 1L, 10, "ORDER_ACCEPTED", D1, 20);
        order(2, 11, D1, "FILLED", 4, 25);
        accepted(3, 2, 11, D1, 30);
        audit("IN", 2, 2L, 11, "NEW_ORDER", D1, 25);
        audit("OUT", 3, 2L, 11, "ORDER_ACCEPTED", D1, 30);
        order(3, 12, D1, "FILLED", 4, 35);
        fill(4, 3, 2, 1, 1_000_000L, 5, D1, 40);
        audit("IN", 3, 3L, 12, "NEW_ORDER", D1, 35);
        audit("OUT", 4, 3L, 12, "ORDER_FILLED", D1, 40);
        order(4, 13, D1, "FILLED", 12, 45);
        accepted(5, 4, 13, D1, 50);
        audit("IN", 4, 4L, 13, "NEW_ORDER", D1, 45);
        audit("OUT", 5, 4L, 13, "ORDER_ACCEPTED", D1, 50);
        run(D1, "COMPLETE", 6, 60);
        // Closed between D1 and D2: a NEW rejected outside any session.
        order(5, 14, null, "REJECTED", 7, 65);
        rejected(7, 5, 14, null, 65);
        audit("IN", 5, 5L, 14, "NEW_ORDER", null, 65);
        audit("OUT", 7, 5L, 14, "ORDER_REJECTED", null, 65);
        // D2
        order(6, 15, D2, "FILLED", 10, 85);
        accepted(9, 6, 15, D2, 90);
        order(7, 16, D2, "FILLED", 10, 95);
        fill(10, 7, 6, 2, 1_000_100L, 3, D2, 100);
        audit("IN", 6, 6L, 15, "NEW_ORDER", D2, 85);
        audit("OUT", 9, 6L, 15, "ORDER_ACCEPTED", D2, 90);
        audit("IN", 7, 7L, 16, "NEW_ORDER", D2, 95);
        audit("OUT", 10, 7L, 16, "ORDER_FILLED", D2, 100);
        run(D2, "COMPLETE", 11, 110);
        // D3: order 8 fills the order accepted on D1.
        order(8, 17, D3, "FILLED", 12, 115);
        fill(12, 8, 4, 3, 1_000_200L, 5, D3, 120);
        audit("IN", 8, 8L, 17, "NEW_ORDER", D3, 115);
        audit("OUT", 12, 8L, 17, "ORDER_FILLED", D3, 120);
        run(D3, "COMPLETE", 13, 130);
        checkpoint(9, 13);
    }

    private DatabaseRetention retention(int days, int batchRows) {
        ReporterStatus reporter = new ReporterStatus() {
            @Override public String state() { return reporterState; }
            @Override public long inboundSeq() { return 9; }
            @Override public long outboundSeq() { return 13; }
        };
        ClearingStatus clearing = new ClearingStatus() {
            @Override public String state() { return clearingState; }
            @Override public int lastClearedDate() { return D3; }
            @Override public int pendingDate() { return -1; }
            @Override public String heldBy() { return null; }
        };
        return new DatabaseRetention(TestDatabase.database()::connect, () -> reporter, () -> clearing, true, days,
                batchRows, true, System::nanoTime, DatabaseRetentionTiming.ON_REQUEST);
    }

    /** Starts {@code r}, asks for one pass, waits for it, stops. */
    private static DatabaseRetentionStatus pass(DatabaseRetention r) {
        r.start();
        try {
            long before = r.completedRuns();
            r.requestRun();
            await("a retention pass", () -> r.completedRuns() > before);
            return r.status();
        } finally {
            assertTrue(r.stop());
        }
    }

    private static void await(String what, BooleanSupplier done) {
        long deadline = System.nanoTime() + AWAIT_MS * 1_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
        }
    }

    private static List<String> rows(String sql) {
        return TestDatabase.rows(sql);
    }

    private static List<String> remaining() {
        List<String> all = new ArrayList<>();
        rows("SELECT inbound_seq FROM orders ORDER BY 1").forEach(r -> all.add("order " + r));
        rows("SELECT outbound_seq FROM executions ORDER BY 1").forEach(r -> all.add("exec " + r));
        rows("SELECT stream || seq FROM order_audit ORDER BY stream, seq").forEach(r -> all.add("audit " + r));
        return all;
    }

    @Test
    void oneDayKeptPurgesD1AndD2AndKeepsWhatIsStillNeeded() {
        DatabaseRetentionStatus s = pass(retention(1, 5_000));
        assertEquals(DatabaseRetentionStatus.PURGED, s.outcome(), String.valueOf(s.skipReason()));
        assertEquals(D2, s.purgedThrough());
        assertTrue(s.lastPurgeTs() > 0);
        // Kept: the resting GTC order (1), the order D3's fill still references (4), and all of D3.
        assertEquals(List.of("order 1", "order 4", "order 8", "exec 12", "audit IN8", "audit OUT12"), remaining());
        assertEquals(26L, s.rowsDeleted(), "5 orders, 7 executions and 14 audit rows of D1, the gap and D2: " + s);
        assertEquals(List.of(D1 + " t t", D2 + " t t", D3 + " f f"),
                rows("SELECT business_date, purged_ts IS NOT NULL, purge_completed_ts IS NOT NULL FROM clearing_run"
                        + " ORDER BY 1"));
        assertEquals(List.of(D1 + " ASML 1 5 5000000 1000000 1000000 1000000 1000000 40 40",
                        D2 + " ASML 1 3 3000300 1000100 1000100 1000100 1000100 100 100"),
                rows("SELECT * FROM daily_summary ORDER BY business_date"));
    }

    @Test
    void theUndatedRejectGoesWithTheFirstDateClosedAfterIt() {
        // Two COMPLETE dates newer than D1 only: with 2 kept, only D1 goes; the reject (stamped 65, after D1's
        // close at 60) stays until D2 goes.
        DatabaseRetentionStatus s = pass(retention(2, 5_000));
        assertEquals(DatabaseRetentionStatus.PURGED, s.outcome(), String.valueOf(s.skipReason()));
        assertEquals(List.of("order 1", "order 4", "order 5", "order 6", "order 7", "order 8", "exec 7", "exec 9",
                "exec 10", "exec 12", "audit IN5", "audit IN6", "audit IN7", "audit IN8", "audit OUT7", "audit OUT9",
                "audit OUT10", "audit OUT12"), remaining());
    }

    @Test
    void aLaterPassTakesTheLeftoverOnceItsLastReferenceIsGone() {
        pass(retention(1, 5_000));
        // D3 now goes too: its fill, then order 4 (its last reference) and order 8. The resting order 1 stays.
        DatabaseRetentionStatus s = pass(retention(0, 5_000));
        assertEquals(DatabaseRetentionStatus.PURGED, s.outcome(), String.valueOf(s.skipReason()));
        assertEquals(List.of("order 1"), remaining());
        assertEquals("OPEN", TestDatabase.queryOne("SELECT status FROM orders WHERE inbound_seq = 1"));
    }

    @Test
    void anOrderAtOrAboveTheCheckpointIsNeverDeleted() {
        checkpoint(8, 13);   // the Reporter's batch ended mid-command at inbound 8
        pass(retention(0, 5_000));
        assertEquals(List.of("order 1", "order 8"), remaining());
    }

    @Test
    void aPendingDateIsNeverPurged() {
        order(9, 18, D4, "FILLED", 15, 140);
        fill(15, 9, 1, 4, 1_000_000L, 1, D4, 150);
        run(D4, "PENDING", 16, 160);
        checkpoint(9, 16);
        pass(retention(0, 5_000));
        assertEquals(List.of("order 1", "order 9", "exec 15"), remaining());
        assertEquals("PENDING null", rows("SELECT status, purged_ts FROM clearing_run WHERE business_date = " + D4).get(0));
    }

    @Test
    void nothingIsDeletedWhileTheWindowHoldsEveryDate() {
        List<String> before = remaining();
        DatabaseRetentionStatus s = pass(retention(14, 5_000));
        assertEquals(DatabaseRetentionStatus.NOTHING_TO_PURGE, s.outcome());
        assertEquals(before, remaining());
        assertEquals(-1, s.purgedThrough());
    }

    @Test
    void aCheckpointBehindTheCloseSkipsWithNothingDeleted() {
        checkpoint(3, 5);
        List<String> before = remaining();
        DatabaseRetentionStatus s = pass(retention(1, 5_000));
        assertEquals(DatabaseRetentionStatus.SKIPPED, s.outcome());
        assertTrue(s.skipReason().startsWith("CHECKPOINT_BEHIND"), s.skipReason());
        assertEquals(before, remaining());
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM clearing_run WHERE purged_ts IS NOT NULL"));
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM daily_summary"));
    }

    @Test
    void anEarlierPendingDateSkipsWithNothingDeleted() {
        run(20261101, "PENDING", 1, 5);
        List<String> before = remaining();
        DatabaseRetentionStatus s = pass(retention(1, 5_000));
        assertEquals(DatabaseRetentionStatus.SKIPPED, s.outcome());
        assertTrue(s.skipReason().startsWith("PENDING_EARLIER"), s.skipReason());
        assertEquals(before, remaining());
    }

    @Test
    void aReporterThatIsNotRunningOrAFailedClearingSkips() {
        List<String> before = remaining();
        reporterState = "FAILED";
        DatabaseRetentionStatus s = pass(retention(1, 5_000));
        assertEquals("REPORTER_NOT_RUNNING: FAILED", s.skipReason());
        reporterState = "RUNNING";
        clearingState = "FAILED";
        s = pass(retention(1, 5_000));
        assertTrue(s.skipReason().startsWith("CLEARING_FAILED"), s.skipReason());
        assertEquals(before, remaining());
    }

    @Test
    void disabledDeletesNothing() {
        DatabaseRetention r = new DatabaseRetention(TestDatabase.database()::connect, () -> null, () -> null, false, 0,
                5_000, true, System::nanoTime, DatabaseRetentionTiming.ON_REQUEST);
        List<String> before = remaining();
        assertEquals(DatabaseRetentionStatus.DISABLED, r.status().outcome());
        assertEquals(DatabaseRetentionStatus.DISABLED, pass(r).outcome());
        assertEquals(before, remaining());
    }

    /**
     * A pass stopped after a few one-row batches: the database is consistent (the foreign keys hold, every
     * batch committed or rolled back whole), the started date answers 410 and is not double counted, and the
     * next pass finishes with the same result as an uninterrupted one.
     */
    @Test
    void aPassInterruptedByShutdownLeavesAConsistentDatabaseAndTheNextPassFinishes() throws Exception {
        DatabaseRetention first = retention(1, 1);
        AtomicInteger batches = new AtomicInteger();
        first.afterBatch(() -> {
            if (batches.incrementAndGet() == 3) first.stopRequested();
        });
        DatabaseRetentionStatus s = pass(first);
        assertEquals(DatabaseRetentionStatus.INTERRUPTED, s.outcome());
        assertEquals(3, batches.get(), "no batch after the stop");
        assertEquals(List.of(D1 + " t f", D2 + " f f", D3 + " f f"),
                rows("SELECT business_date, purged_ts IS NOT NULL, purge_completed_ts IS NOT NULL FROM clearing_run"
                        + " ORDER BY 1"));
        assertEquals("5", TestDatabase.queryOne("SELECT count(*) FROM order_audit WHERE business_date = " + D1),
                "8 audit rows on D1, 3 one-row batches committed");

        ApiService api = new ApiService(TestDatabase.database()::connect);
        try {
            assertEquals(410, get(api, "/api/trades", "date", Integer.toString(D1)).status());
            String volume = body(get(api, "/api/reports/volume", "from", Integer.toString(D1), "to", Integer.toString(D3)));
            assertTrue(volume.contains("{\"businessDate\":" + D1 + ",\"dayStatus\":\"CLEARED\",\"symbol\":\"ASML\","
                    + "\"tradeCount\":1,\"volume\":5,\"notional\":5000000,\"vwap\":1000000}"), volume);
            assertEquals(1, volume.split("\"businessDate\":" + D1, -1).length - 1, "D1 once, from the summary: " + volume);
        } finally {
            api.close();
        }

        DatabaseRetentionStatus next = pass(retention(1, 1));
        assertEquals(DatabaseRetentionStatus.PURGED, next.outcome(), String.valueOf(next.skipReason()));
        assertEquals(List.of("order 1", "order 4", "order 8", "exec 12", "audit IN8", "audit OUT12"), remaining());
        assertEquals("2", TestDatabase.queryOne("SELECT count(*) FROM daily_summary"), "the summary is written once");
    }

    /**
     * Amendment 1: every column referencing orders is indexed, and an orders batch over a large table looks
     * each candidate's references up by index (for the NOT EXISTS checks; the deferred foreign key checks use
     * the same indexes). The plan is printed for the as-built report.
     */
    @Test
    void anOrdersBatchUsesTheReferenceIndexes() {
        // As at the orders step of a real pass: the purged date's 60,000 orders, whose own audit and execution
        // rows are already gone, beside a kept date's 60,000 orders with 60,000 executions and audit rows that
        // reference them (and, on the passive side, a few of the purged date's).
        TestDatabase.execute("INSERT INTO orders (inbound_seq, order_id, participant_id, business_date, symbol, side,"
                + " ord_type, time_in_force, limit_price, quantity, received_ts, status, cum_quantity, last_outbound_seq)"
                + " SELECT 100 + g, g, 1, CASE WHEN g <= 60000 THEN " + D1 + " ELSE " + D2 + " END, 'ASML', 'BUY',"
                + " 'LIMIT', 'GTC', 1000000, 5, g, 'FILLED', 5, 1000 + g FROM generate_series(1, 120000) g");
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id,"
                + " passive_order_inbound_seq, remaining_quantity, business_date, event_ts)"
                + " SELECT 200000 + g, 'ORDER_ACCEPTED', 60100 + g, g,"
                + " CASE WHEN g % 1000 = 0 THEN 100 + g ELSE 60100 + g END, 5, " + D2 + ", g"
                + " FROM generate_series(1, 60000) g");
        TestDatabase.execute("INSERT INTO order_audit (stream, seq, order_inbound_seq, order_id, event, business_date,"
                + " event_ts) SELECT 'IN', 1000 + g, 60100 + g, g, 'NEW_ORDER', " + D2 + ", g"
                + " FROM generate_series(1, 60000) g");
        TestDatabase.execute("ANALYZE orders");
        TestDatabase.execute("ANALYZE executions");
        TestDatabase.execute("ANALYZE order_audit");
        assertEquals(List.of("executions_order_inbound_seq", "executions_passive_order_inbound_seq",
                        "order_audit_order_inbound_seq"),
                rows("SELECT indexname FROM pg_indexes WHERE indexname IN ('executions_order_inbound_seq',"
                        + " 'executions_passive_order_inbound_seq', 'order_audit_order_inbound_seq') ORDER BY 1"));
        String sql = RetentionStore.Step.ORDERS.sql
                .replaceFirst("\\?", Integer.toString(D1)).replaceFirst("\\?", "1000000")
                .replaceFirst("\\?", "1000000").replaceFirst("\\?", "5000");
        List<String> plan = rows("EXPLAIN " + sql);
        String text = String.join("\n", plan);
        System.out.println("P16-8 EXPLAIN of one orders batch (120,000 orders, 60,000 executions and audit rows):\n"
                + text);
        assertTrue(text.contains("executions_order_inbound_seq"), text);
        assertTrue(text.contains("executions_passive_order_inbound_seq"), text);
        assertTrue(text.contains("order_audit_order_inbound_seq"), text);
        assertFalse(text.contains("Seq Scan on executions"), text);
        assertFalse(text.contains("Seq Scan on order_audit"), text);
        assertFalse(text.contains("Seq Scan on orders"), "the delete itself goes by primary key: " + text);

        // An audit batch deletes by row address (a Tid Scan), never by scanning for its keys.
        String audit = String.join("\n", rows("EXPLAIN " + RetentionStore.Step.AUDIT.sql
                .replaceFirst("\\?", Integer.toString(D2)).replaceFirst("\\?", "5000")
                .replaceFirst("\\?", Integer.toString(D2))));
        System.out.println("P16-8 EXPLAIN of one order_audit batch:\n" + audit);
        assertTrue(audit.contains("Tid Scan on order_audit"), audit);
    }

    private static ApiResponse get(ApiService api, String path, String... pairs) throws Exception {
        Map<String, List<String>> params = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) params.put(pairs[i], List.of(pairs[i + 1]));
        CompletableFuture<ApiResponse> later = new CompletableFuture<>();
        ApiResponse now = api.handle(new ApiRequest(path, params), later::complete);
        return now != null ? now : later.get(10, TimeUnit.SECONDS);
    }

    private static String body(ApiResponse r) {
        String text = new String(r.body(), StandardCharsets.UTF_8);
        assertEquals(200, r.status(), text);
        return text;
    }
}
