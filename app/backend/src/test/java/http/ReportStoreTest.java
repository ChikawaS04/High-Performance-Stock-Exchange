package http;

import db.RequiresTestDatabase;
import db.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static http.ReportSeed.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every /api report against seeded data in {@code exchange_test} (P15-12), compared as exact JSON.
 *
 * The seed (seqs in order): 20261103 one fill and a close that is still PENDING; 20261105 three fills
 * (one a house self-trade at a sub-penny midpoint), an ACCEPTED, and a COMPLETE run rolled to
 * 20261106; 20261106 open, with fills whose VWAP lands exactly on a half unit, once below an even
 * unit and once below an odd one. The checkpoint is at seq 11.
 */
@RequiresTestDatabase
class ReportStoreTest {

    private static final int PENDING_DAY = 20261103;
    private static final int DAY = 20261105;
    private static final int NEXT = 20261106;
    /** 2026-11-05 00:00:00 UTC in epoch nanos. */
    private static final long B = 1_793_836_800L * 1_000_000_000L;
    private static final long MIN = 60_000_000_000L;

    private final Set<String> queryThreads = ConcurrentHashMap.newKeySet();
    private ApiService api;
    private ReportSeed seed;

    @BeforeEach
    void seed() {
        TestDatabase.cleanAndMigrate();
        api = new ApiService(TestDatabase.database()::connect, () -> queryThreads.add(Thread.currentThread().getName()),
                ApiService.THREADS, ApiService.MAX_PENDING);
        seed = new ReportSeed().brokers();
        seed.fill(PENDING_DAY, "ASML", 1_000_000L, 1, "BUY", BROKER_A, BROKER_B);           // 1
        seed.close(PENDING_DAY);                                                           // 2
        seed.fill(DAY, "ASML", 1_000_000L, 3, "BUY", BROKER_A, BROKER_B);                  // 3
        seed.accepted(DAY);                                                                // 4
        seed.fill(DAY, "ASML", 1_000_100L, 2, "SELL", BROKER_B, BROKER_A);                 // 5
        seed.fill(DAY, "NVDA", 500_050L, 1, "BUY", HOUSE, HOUSE);                          // 6, midpoint self-trade
        seed.close(DAY);                                                                   // 7
        complete(DAY, NEXT, 3, 6);
        obligation(DAY, HOUSE, "NVDA", 1, 1, 0);
        obligation(DAY, BROKER_A, "ASML", 5, 0, 5_000_200L);
        obligation(DAY, BROKER_B, "ASML", 0, 5, -5_000_200L);
        balance(BROKER_A, 20261101, 100_000_000L);
        balance(BROKER_A, NEXT, 94_999_800L);
        balance(BROKER_B, 20261101, 50_000_000L);
        balance(BROKER_B, NEXT, 55_000_200L);
        position(BROKER_A, 20261101, "ASML", 10);
        position(BROKER_A, NEXT, "ASML", 15);
        position(BROKER_B, 20261101, "ASML", 5);
        position(BROKER_B, NEXT, "ASML", 0);
        house(20261101, "NVDA", 7);
        house(NEXT, "NVDA", 7);
        seed.fill(NEXT, "ASML", 1_000_000L, 3, "BUY", BROKER_A, BROKER_B);                 // 8
        seed.fill(NEXT, "ASML", 1_000_050L, 1, "BUY", BROKER_A, BROKER_B);                 // 9: 1,000,012.5
        seed.fill(NEXT, "NVDA", 1_000_000L, 3, "BUY", HOUSE, HOUSE);                       // 10
        seed.fill(NEXT, "NVDA", 1_000_150L, 1, "BUY", HOUSE, HOUSE);                       // 11: 1,000,037.5
        candle("ASML", DAY, B, 100, 110, 90, 105, 3, 1);
        candle("ASML", DAY, B + MIN, 105, 120, 104, 118, 4, 2);
        candle("ASML", DAY, B + 5 * MIN, 118, 119, 80, 90, 5, 3);
        candle("ASML", DAY, B + 1439 * MIN, 200, 210, 190, 205, 1, 1);
        // The minute that spans midnight has a row for each date; the next date's minute 0 follows.
        candle("ASML", NEXT, B + 1439 * MIN, 205, 205, 150, 160, 2, 1);
        candle("ASML", NEXT, B + 1440 * MIN, 160, 170, 160, 170, 1, 1);
        seed.checkpoint(NEXT);
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

    private static String trade(long seq, long tradeId, String symbol, long price, long qty, String side, long aggOrder,
                                long passOrder, long aggressor, long passive) {
        return "{\"outboundSeq\":" + seq + ",\"tradeId\":" + tradeId + ",\"timestamp\":" + (1_000 + seq) + ",\"symbol\":\""
                + symbol + "\",\"price\":" + price + ",\"quantity\":" + qty + ",\"aggressorSide\":\"" + side
                + "\",\"aggressorOrderId\":" + aggOrder + ",\"passiveOrderId\":" + passOrder + ",\"aggressorParticipantId\":"
                + aggressor + ",\"passiveParticipantId\":" + passive + "}";
    }

    private static String leg(long seq, long tradeId, String symbol, String role, String side, long price, long qty) {
        return "{\"outboundSeq\":" + seq + ",\"tradeId\":" + tradeId + ",\"timestamp\":" + (1_000 + seq) + ",\"symbol\":\""
                + symbol + "\",\"role\":\"" + role + "\",\"side\":\"" + side + "\",\"price\":" + price + ",\"quantity\":"
                + qty + "}";
    }

    private static String candleJson(long ts, long o, long h, long l, long c, long v, long n) {
        return "{\"bucketStartTs\":" + ts + ",\"open\":" + o + ",\"high\":" + h + ",\"low\":" + l + ",\"close\":" + c
                + ",\"volume\":" + v + ",\"tradeCount\":" + n + "}";
    }

    // --- trades ---

    @Test
    void tradeHistoryForAClearedDay() {
        assertEquals("{\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"asOfOutboundSeq\":11,\"trades\":["
                + trade(3, 2, "ASML", 1_000_000, 3, "BUY", 4, 3, BROKER_A, BROKER_B) + ","
                + trade(5, 3, "ASML", 1_000_100, 2, "SELL", 7, 6, BROKER_B, BROKER_A) + ","
                + trade(6, 4, "NVDA", 500_050, 1, "BUY", 9, 8, HOUSE, HOUSE)
                + "],\"nextAfterSeq\":null}", json(ApiService.TRADES, "date", "20261105"));
        assertTrue(queryThreads.stream().allMatch(n -> n.startsWith("api-")), queryThreads.toString());
        assertFalse(queryThreads.isEmpty());
    }

    @Test
    void tradeHistoryForAnOpenDayIsServedAndLabelled() {
        String body = json(ApiService.TRADES, "date", "20261106", "limit", "1");
        assertEquals("{\"businessDate\":20261106,\"dayStatus\":\"OPEN\",\"asOfOutboundSeq\":11,\"trades\":["
                + trade(8, 5, "ASML", 1_000_000, 3, "BUY", 11, 10, BROKER_A, BROKER_B)
                + "],\"nextAfterSeq\":8}", body);
    }

    @Test
    void aDateWithNothingRecordedIsEmptyNotMissing() {
        assertEquals("{\"businessDate\":20261102,\"dayStatus\":\"NONE\",\"asOfOutboundSeq\":11,\"trades\":[],"
                + "\"nextAfterSeq\":null}", json(ApiService.TRADES, "date", "20261102"));
    }

    private static final Pattern SEQ = Pattern.compile("\"outboundSeq\":(\\d+)");
    private static final Pattern NEXT_SEQ = Pattern.compile("\"nextAfterSeq\":(null|\\d+)");

    /** Pages of one trade each, with a fill appended between pages: every fill once, in order, none skipped. */
    @Test
    void paginationNeverRepeatsOrSkipsARowWhileTheReporterAppends() {
        List<Long> seen = new ArrayList<>();
        String after = "0";
        int pages = 0;
        while (after != null) {
            String body = json(ApiService.TRADES, "date", "20261105", "afterSeq", after, "limit", "1");
            Matcher m = SEQ.matcher(body);
            while (m.find()) seen.add(Long.parseLong(m.group(1)));
            Matcher next = NEXT_SEQ.matcher(body);
            assertTrue(next.find());
            after = "null".equals(next.group(1)) ? null : next.group(1);
            if (++pages == 2) {
                seed.fill(DAY, "ASML", 1_000_000L, 1, "BUY", BROKER_A, BROKER_B);   // seq 12, as the Reporter appends
                seed.checkpoint(NEXT);
            }
            assertTrue(pages < 10, "pagination ends");
        }
        assertEquals(List.of(3L, 5L, 6L, 12L), seen);
    }

    // --- daily report ---

    @Test
    void dailyReportForAClearedDay() {
        assertEquals("{\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"asOfOutboundSeq\":11,"
                + "\"clearing\":{\"status\":\"COMPLETE\",\"closeOutboundSeq\":7,\"closedTs\":1007,\"completedTs\":2001,"
                + "\"nextBusinessDate\":20261106,\"fillCount\":3,\"volume\":6},"
                + "\"symbols\":[{\"symbol\":\"ASML\",\"tradeCount\":2,\"volume\":5,\"notional\":5000200,\"vwap\":1000040,"
                + "\"open\":1000000,\"high\":1000100,\"low\":1000000,\"close\":1000100,\"firstTradeTs\":1003,\"lastTradeTs\":1005},"
                + "{\"symbol\":\"NVDA\",\"tradeCount\":1,\"volume\":1,\"notional\":500050,\"vwap\":500050,"
                + "\"open\":500050,\"high\":500050,\"low\":500050,\"close\":500050,\"firstTradeTs\":1006,\"lastTradeTs\":1006}],"
                + "\"obligations\":[{\"participantId\":1,\"symbol\":\"NVDA\",\"bought\":1,\"sold\":1,\"net\":0,\"cashObligation\":0},"
                + "{\"participantId\":2,\"symbol\":\"ASML\",\"bought\":5,\"sold\":0,\"net\":5,\"cashObligation\":5000200},"
                + "{\"participantId\":3,\"symbol\":\"ASML\",\"bought\":0,\"sold\":5,\"net\":-5,\"cashObligation\":-5000200}]}",
                json(ApiService.DAILY, "date", "20261105"));
    }

    @Test
    void aClosedDayAwaitingClearingSaysSoAndShowsNoObligations() {
        assertEquals("{\"businessDate\":20261103,\"dayStatus\":\"CLOSED\",\"asOfOutboundSeq\":11,"
                + "\"clearing\":{\"status\":\"PENDING\",\"closeOutboundSeq\":2,\"closedTs\":1002,\"completedTs\":null,"
                + "\"nextBusinessDate\":null,\"fillCount\":null,\"volume\":null},"
                + "\"symbols\":[{\"symbol\":\"ASML\",\"tradeCount\":1,\"volume\":1,\"notional\":1000000,\"vwap\":1000000,"
                + "\"open\":1000000,\"high\":1000000,\"low\":1000000,\"close\":1000000,\"firstTradeTs\":1001,\"lastTradeTs\":1001}],"
                + "\"obligations\":null}", json(ApiService.DAILY, "date", "20261103"));
    }

    /** VWAP at an exact half unit, half even: 1,000,012.5 goes down to the even unit, 1,000,037.5 up to it. */
    @Test
    void anOpenDayShowsHalfEvenVwapAndNoClearing() {
        assertEquals("{\"businessDate\":20261106,\"dayStatus\":\"OPEN\",\"asOfOutboundSeq\":11,\"clearing\":null,"
                + "\"symbols\":[{\"symbol\":\"ASML\",\"tradeCount\":2,\"volume\":4,\"notional\":4000050,\"vwap\":1000012,"
                + "\"open\":1000000,\"high\":1000050,\"low\":1000000,\"close\":1000050,\"firstTradeTs\":1008,\"lastTradeTs\":1009},"
                + "{\"symbol\":\"NVDA\",\"tradeCount\":2,\"volume\":4,\"notional\":4000150,\"vwap\":1000038,"
                + "\"open\":1000000,\"high\":1000150,\"low\":1000000,\"close\":1000150,\"firstTradeTs\":1010,\"lastTradeTs\":1011}],"
                + "\"obligations\":null}", json(ApiService.DAILY, "date", "20261106"));
    }

    @Test
    void aNotionalBeyondALongIsA500NotAWrap() {
        seed.fill(20261102, "ASML", 4_611_686_018_427_387_904L, 2, "BUY", BROKER_A, BROKER_B);   // 2^62 times 2 is 2^63
        ApiResponse r = get(ApiService.DAILY, "date", "20261102");
        assertSame(ApiResponse.OVERFLOW, r);
        assertEquals(500, r.status());
    }

    // --- volume summary ---

    @Test
    void volumeSummaryOverARange() {
        assertEquals("{\"from\":20261101,\"to\":20261106,\"symbol\":null,\"asOfOutboundSeq\":11,\"days\":["
                + "{\"businessDate\":20261103,\"dayStatus\":\"CLOSED\",\"symbol\":\"ASML\",\"tradeCount\":1,\"volume\":1,\"notional\":1000000,\"vwap\":1000000},"
                + "{\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"symbol\":\"ASML\",\"tradeCount\":2,\"volume\":5,\"notional\":5000200,\"vwap\":1000040},"
                + "{\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"symbol\":\"NVDA\",\"tradeCount\":1,\"volume\":1,\"notional\":500050,\"vwap\":500050},"
                + "{\"businessDate\":20261106,\"dayStatus\":\"OPEN\",\"symbol\":\"ASML\",\"tradeCount\":2,\"volume\":4,\"notional\":4000050,\"vwap\":1000012},"
                + "{\"businessDate\":20261106,\"dayStatus\":\"OPEN\",\"symbol\":\"NVDA\",\"tradeCount\":2,\"volume\":4,\"notional\":4000150,\"vwap\":1000038}],"
                + "\"totals\":[{\"symbol\":\"ASML\",\"tradeCount\":5,\"volume\":10,\"notional\":10000250,\"vwap\":1000025},"
                + "{\"symbol\":\"NVDA\",\"tradeCount\":3,\"volume\":5,\"notional\":4500200,\"vwap\":900040}]}",
                json(ApiService.VOLUME, "from", "20261101", "to", "20261106"));
    }

    @Test
    void volumeSummaryForOneSymbol() {
        assertEquals("{\"from\":20261105,\"to\":20261106,\"symbol\":\"NVDA\",\"asOfOutboundSeq\":11,\"days\":["
                + "{\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"symbol\":\"NVDA\",\"tradeCount\":1,\"volume\":1,\"notional\":500050,\"vwap\":500050},"
                + "{\"businessDate\":20261106,\"dayStatus\":\"OPEN\",\"symbol\":\"NVDA\",\"tradeCount\":2,\"volume\":4,\"notional\":4000150,\"vwap\":1000038}],"
                + "\"totals\":[{\"symbol\":\"NVDA\",\"tradeCount\":3,\"volume\":5,\"notional\":4500200,\"vwap\":900040}]}",
                json(ApiService.VOLUME, "from", "20261105", "to", "20261106", "symbol", "NVDA"));
    }

    // --- statements ---

    @Test
    void anExternalParticipantsClearedDay() {
        assertEquals("{\"participant\":{\"participantId\":2,\"name\":\"Broker A\",\"type\":\"EXTERNAL\",\"accountId\":1},"
                + "\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"asOfOutboundSeq\":11,\"fills\":["
                + leg(3, 2, "ASML", "AGGRESSOR", "BUY", 1_000_000, 3) + ","
                + leg(5, 3, "ASML", "PASSIVE", "BUY", 1_000_100, 2)
                + "],\"nextAfterSeq\":null,"
                + "\"obligations\":[{\"symbol\":\"ASML\",\"bought\":5,\"sold\":0,\"net\":5,\"cashObligation\":5000200}],"
                + "\"cash\":{\"opening\":100000000,\"closing\":94999800},"
                + "\"positions\":[{\"symbol\":\"ASML\",\"opening\":10,\"closing\":15}]}",
                json(ApiService.STATEMENT, "participantId", "2", "date", "20261105"));
    }

    @Test
    void theHousesDayShowsBothLegsOfASelfTradeAndPositionsOnly() {
        assertEquals("{\"participant\":{\"participantId\":1,\"name\":\"House terminal\",\"type\":\"INTERNAL\",\"accountId\":null},"
                + "\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"asOfOutboundSeq\":11,\"fills\":["
                + leg(6, 4, "NVDA", "AGGRESSOR", "BUY", 500_050, 1) + ","
                + leg(6, 4, "NVDA", "PASSIVE", "SELL", 500_050, 1)
                + "],\"nextAfterSeq\":null,"
                + "\"obligations\":[{\"symbol\":\"NVDA\",\"bought\":1,\"sold\":1,\"net\":0,\"cashObligation\":0}],"
                + "\"cash\":null,\"positions\":[{\"symbol\":\"NVDA\",\"opening\":7,\"closing\":7}]}",
                json(ApiService.STATEMENT, "participantId", "1", "date", "20261105"));
    }

    @Test
    void aStatementBeforeClearingHasNoObligationsOrClosingValues() {
        assertEquals("{\"participant\":{\"participantId\":2,\"name\":\"Broker A\",\"type\":\"EXTERNAL\",\"accountId\":1},"
                + "\"businessDate\":20261103,\"dayStatus\":\"CLOSED\",\"asOfOutboundSeq\":11,\"fills\":["
                + leg(1, 1, "ASML", "AGGRESSOR", "BUY", 1_000_000, 1)
                + "],\"nextAfterSeq\":null,\"obligations\":null,"
                + "\"cash\":{\"opening\":100000000,\"closing\":null},"
                + "\"positions\":[{\"symbol\":\"ASML\",\"opening\":10,\"closing\":null}]}",
                json(ApiService.STATEMENT, "participantId", "2", "date", "20261103"));
    }

    @Test
    void statementFillsPageByTrade() {
        String body = json(ApiService.STATEMENT, "participantId", "1", "date", "20261106", "limit", "1");
        assertTrue(body.contains("\"fills\":[" + leg(10, 7, "NVDA", "AGGRESSOR", "BUY", 1_000_000, 3) + ","
                + leg(10, 7, "NVDA", "PASSIVE", "SELL", 1_000_000, 3) + "],\"nextAfterSeq\":10,"), body);
    }

    @Test
    void anUnknownParticipantOrSymbolIs404() {
        assertSame(ApiResponse.NOT_FOUND, get(ApiService.STATEMENT, "participantId", "99", "date", "20261105"));
        assertSame(ApiResponse.NOT_FOUND, get(ApiService.CANDLES, "symbol", "ZZZZ", "date", "20261105"));
        assertSame(ApiResponse.NOT_FOUND, get(ApiService.VOLUME, "from", "20261105", "to", "20261105", "symbol", "ZZZZ"));
    }

    // --- candles ---

    @Test
    void oneMinuteCandlesAreTheStoredRows() {
        assertEquals("{\"symbol\":\"ASML\",\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"intervalMinutes\":1,"
                + "\"asOfOutboundSeq\":11,\"candles\":["
                + candleJson(B, 100, 110, 90, 105, 3, 1) + "," + candleJson(B + MIN, 105, 120, 104, 118, 4, 2) + ","
                + candleJson(B + 5 * MIN, 118, 119, 80, 90, 5, 3) + "," + candleJson(B + 1439 * MIN, 200, 210, 190, 205, 1, 1)
                + "]}", json(ApiService.CANDLES, "symbol", "ASML", "date", "20261105"));
    }

    @Test
    void fiveMinuteCandles() {
        assertEquals("{\"symbol\":\"ASML\",\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"intervalMinutes\":5,"
                + "\"asOfOutboundSeq\":11,\"candles\":["
                + candleJson(B, 100, 120, 90, 118, 7, 3) + "," + candleJson(B + 5 * MIN, 118, 119, 80, 90, 5, 3) + ","
                + candleJson(B + 1435 * MIN, 200, 210, 190, 205, 1, 1)
                + "]}", json(ApiService.CANDLES, "symbol", "ASML", "date", "20261105", "interval", "5"));
    }

    /** The minute that spans midnight belongs to each date's own row: an hour candle never mixes dates. */
    @Test
    void hourCandlesStayWithinTheirBusinessDate() {
        assertEquals("{\"symbol\":\"ASML\",\"businessDate\":20261105,\"dayStatus\":\"CLEARED\",\"intervalMinutes\":60,"
                + "\"asOfOutboundSeq\":11,\"candles\":["
                + candleJson(B, 100, 120, 80, 90, 12, 6) + "," + candleJson(B + 1380 * MIN, 200, 210, 190, 205, 1, 1)
                + "]}", json(ApiService.CANDLES, "symbol", "ASML", "date", "20261105", "interval", "60"));
        assertEquals("{\"symbol\":\"ASML\",\"businessDate\":20261106,\"dayStatus\":\"OPEN\",\"intervalMinutes\":60,"
                + "\"asOfOutboundSeq\":11,\"candles\":["
                + candleJson(B + 1380 * MIN, 205, 205, 150, 160, 2, 1) + "," + candleJson(B + 1440 * MIN, 160, 170, 160, 170, 1, 1)
                + "]}", json(ApiService.CANDLES, "symbol", "ASML", "date", "20261106", "interval", "60"));
    }
}
