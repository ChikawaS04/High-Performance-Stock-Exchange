package integration;

import db.RequiresTestDatabase;
import db.TestDatabase;
import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import gateway.OrderGateway;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reporter.Candles;
import reporter.Reporter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static integration.ReporterHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** The rows match the recordings, end to end through a real journal and {@code exchange_test}. */
@RequiresTestDatabase
class ReporterDayTest {

    private static final int DAY = JournaledVenue.BUSINESS_DATE;
    private static final int NEXT_DAY = DAY + 1;
    private static final long BID = 1_000_000L;     // $100.00
    private static final long ASK = 1_000_100L;     // $100.01
    private static final long MID = 1_000_050L;     // $100.005: sub-penny
    private static final long LOW = 990_000L;

    /** Timestamps are the journaled receipt stamps; a test clock makes buckets exact. */
    private static final long START = 29_000_000L * Candles.BUCKET_NANOS;

    @TempDir
    Path root;

    private final AtomicLong clock = new AtomicLong(START);

    @BeforeEach
    void emptySchema() {
        TestDatabase.cleanAndMigrate();
    }

    private OrderGateway gateway(JournaledVenue venue) {
        return venue.gateway(clock::get);
    }

    /**
     * A scripted day. Inbound seqs in the comments; the open is seq 1, outbound seq 1.
     *
     * @return the number of outbound events
     */
    private static int scriptedDay(OrderGateway g) {
        g.onFrame(limit(100L, Side.SELL, BID, 10L));              // 2  ACCEPTED (out 2)
        g.onFrame(limit(101L, Side.BUY, BID, 4L));                // 3  FILLED, passive 100 partial (out 3)
        g.onFrame(limit(102L, Side.BUY, BID, 10L));               // 4  PARTIAL 6, 100 filled (4), ACCEPTED 4 (5)
        g.onFrame(iceberg(103L, Side.SELL, ASK, 10L, 3L));        // 5  ACCEPTED (6)
        g.onFrame(limit(104L, Side.BUY, ASK, 5L));                // 6  PARTIAL 3 (7), reload, FILLED 2 (8)
        g.onFrame(peg(105L, Side.SELL, 5L, TimeInForce.GTC));     // 7  ACCEPTED at no price (9)
        g.onFrame(peg(106L, Side.BUY, 2L, TimeInForce.IOC));      // 8  FILLED at the mid (10)
        g.onFrame(limit(107L, Side.BUY, LOW, 3L, TimeInForce.IOC)); // 9  EXPIRED (11)
        g.onFrame(cancel(9_001L, 102L));                          // 10 CANCELLED (12)
        g.onFrame(limit(103L, Side.BUY, LOW, 1L));                // 11 REJECTED, a duplicate id (13)
        g.onFrame(cancel(9_002L, 9_999L));                        // 12 REJECTED, unknown (14)
        g.onFrame(limit(101L, Side.BUY, LOW, 1L));                // 13 id reused after its fill: ACCEPTED (15)
        g.onFrame(cancel(9_003L, 101L));                          // 14 CANCELLED, the reuse (16)
        g.publishSessionClose(DAY);                               // 15 SESSION_CLOSED (17)
        g.onFrame(limit(200L, Side.BUY, LOW, 1L));                // 16 REJECTED, session closed (18)
        g.onFrame(cancel(9_004L, 103L));                          // 17 CANCELLED, the iceberg, closed (19)
        return 19;
    }

    private static String orderRow(long seq) {
        return rows("SELECT order_id, business_date, symbol, side, ord_type, time_in_force, limit_price, quantity,"
                + " display_quantity, status, cum_quantity, last_outbound_seq FROM orders WHERE inbound_seq = " + seq).get(0);
    }

    private static String attribution(long outboundSeq) {
        return rows("SELECT event_type, order_inbound_seq, passive_order_inbound_seq, business_date FROM executions"
                + " WHERE outbound_seq = " + outboundSeq).get(0);
    }

    @Test
    void theRowsOfAScriptedDayMatchTheRecordings() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = gateway(venue);
            venue.startAndOpen(gateway);
            int events = scriptedDay(gateway);

            Reporter reporter = reporter(venue);
            reporter.start();
            awaitCheckpoint(reporter, events);
            assertTrue(reporter.stop());

            // --- orders: details from the inbound stream, status from the outbound ---
            assertEquals("11", TestDatabase.queryOne("SELECT count(*) FROM orders"), "NEW commands only");
            assertEquals("100 " + DAY + " ASML SELL LIMIT GTC 1000000 10 null FILLED 10 4", orderRow(2));
            assertEquals("101 " + DAY + " ASML BUY LIMIT GTC 1000000 4 null FILLED 4 3", orderRow(3));
            assertEquals("102 " + DAY + " ASML BUY LIMIT GTC 1000000 10 null CANCELLED 6 12", orderRow(4));
            assertEquals("103 " + DAY + " ASML SELL LIMIT GTC 1000100 10 3 CANCELLED 5 19", orderRow(5));
            assertEquals("105 " + DAY + " ASML SELL PEG_MID GTC null 5 null PARTIALLY_FILLED 2 10", orderRow(7));
            assertEquals("106 " + DAY + " ASML BUY PEG_MID IOC null 2 null FILLED 2 10", orderRow(8));
            assertEquals("107 " + DAY + " ASML BUY LIMIT IOC 990000 3 null EXPIRED 0 11", orderRow(9));
            assertEquals("103 " + DAY + " ASML BUY LIMIT GTC 990000 1 null REJECTED 0 13", orderRow(11));
            assertEquals("101 " + DAY + " ASML BUY LIMIT GTC 990000 1 null CANCELLED 0 16", orderRow(13));
            assertEquals("200 null null BUY LIMIT GTC 990000 1 null REJECTED 0 18", orderRow(16));

            // --- attribution ---
            assertEquals("ORDER_ACCEPTED 2 null " + DAY, attribution(2));
            assertEquals("ORDER_FILLED 3 2 " + DAY, attribution(3));
            assertEquals("ORDER_PARTIALLY_FILLED 4 2 " + DAY, attribution(4));
            assertEquals("ORDER_PARTIALLY_FILLED 6 5 " + DAY, attribution(7));
            assertEquals("ORDER_FILLED 6 5 " + DAY, attribution(8));
            assertEquals("ORDER_FILLED 8 7 " + DAY, attribution(10));
            assertEquals("ORDER_EXPIRED 9 null " + DAY, attribution(11));
            assertEquals("ORDER_CANCELLED 4 null " + DAY, attribution(12));
            assertEquals("ORDER_REJECTED 11 null " + DAY, attribution(13), "the duplicate's own row");
            assertEquals("ORDER_REJECTED null null " + DAY, attribution(14), "an unknown cancel has no order");
            assertEquals("ORDER_CANCELLED 13 null " + DAY, attribution(16), "the reuse, not the first order");
            assertEquals("ORDER_REJECTED 16 null null", attribution(18), "closed: no business date");
            assertEquals("ORDER_CANCELLED 5 null null", attribution(19));

            // --- every order event in the recording has its row, field for field ---
            List<ExecutionEvent> recorded = recordedEvents(venue.journal(), events);
            int orderEvents = 0;
            for (ExecutionEvent e : recorded) {
                if (e.eventType == ExecutionEventType.SESSION_OPENED || e.eventType == ExecutionEventType.SESSION_CLOSED) {
                    assertTrue(rows("SELECT 1 FROM executions WHERE outbound_seq = " + e.seq).isEmpty(), "session events");
                    continue;
                }
                orderEvents++;
                // aggressor_side holds the aggressor only: an ACCEPTED's side (the resting order's) is not written.
                boolean fill = e.eventType == ExecutionEventType.ORDER_FILLED
                        || e.eventType == ExecutionEventType.ORDER_PARTIALLY_FILLED;
                assertEquals(String.join(" ", e.eventType.name(), Long.toString(e.orderId), text(e.tradeId), text(e.price),
                                text(e.filledQuantity), text(e.remainingQuantity), text(e.aggressorOrderId),
                                text(e.passiveOrderId), fill ? String.valueOf(e.aggressorSide) : "null",
                                text(e.aggressorParticipantId),
                                text(e.passiveParticipantId), Long.toString(e.timestamp)),
                        rows("SELECT event_type, order_id, trade_id, price, filled_quantity, remaining_quantity,"
                                + " aggressor_order_id, passive_order_id, aggressor_side, aggressor_participant_id,"
                                + " passive_participant_id, event_ts FROM executions WHERE outbound_seq = " + e.seq).get(0));
            }
            assertEquals(Integer.toString(orderEvents), TestDatabase.queryOne("SELECT count(*) FROM executions"));

            // --- the audit trail: every order command and every order event, once ---
            assertEquals("11", TestDatabase.queryOne(
                    "SELECT count(*) FROM order_audit WHERE stream = 'IN' AND event = 'NEW_ORDER'"));
            assertEquals("4", TestDatabase.queryOne(
                    "SELECT count(*) FROM order_audit WHERE stream = 'IN' AND event = 'CANCEL_ORDER'"));
            assertEquals(Integer.toString(orderEvents), TestDatabase.queryOne(
                    "SELECT count(*) FROM order_audit WHERE stream = 'OUT'"));
            assertEquals("null 9999", rows("SELECT order_inbound_seq, order_id FROM order_audit"
                    + " WHERE stream = 'IN' AND seq = 12").get(0));

            // --- the checkpoint passed the session events, and no session is open ---
            assertEquals(List.of("17 19 null null"), rows("SELECT last_inbound_seq, last_outbound_seq,"
                    + " session_business_date, session_symbol FROM reporter_checkpoint"));
        }
    }

    private static String text(long value) {
        return value == -1L ? "null" : Long.toString(value);
    }

    /** Two resting pegs cross when a lit order creates a mid, under a command that is neither of them. */
    @Test
    void aPegCrossIsAttributedToTheTwoRestingOrders() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = gateway(venue);
            venue.startAndOpen(gateway);
            gateway.onFrame(limit(1L, Side.BUY, BID, 1L));                  // 2  lit bid only: no mid (out 2)
            clock.addAndGet(1_000L);
            gateway.onFrame(peg(2L, Side.BUY, 5L, TimeInForce.GTC));        // 3  rests (3)
            clock.addAndGet(1_000L);
            gateway.onFrame(peg(3L, Side.SELL, 5L, TimeInForce.GTC));       // 4  rests, no mid (4)
            clock.addAndGet(1_000L);
            gateway.onFrame(limit(4L, Side.SELL, ASK, 1L));                 // 5  rests (5), creates the mid: cross (6)

            Reporter reporter = reporter(venue);
            reporter.start();
            awaitCheckpoint(reporter, 6L);
            assertTrue(reporter.stop());

            assertEquals(List.of("ORDER_FILLED 3 3 4 3 " + MID + " 5"), rows("SELECT event_type, order_id,"
                    + " aggressor_order_id, order_inbound_seq, passive_order_inbound_seq, price, filled_quantity"
                    + " FROM executions WHERE outbound_seq = 6"));
        }
    }

    @Test
    void reApplyingTheWholeJournalAfterAFullResetChangesNothing() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = gateway(venue);
            venue.startAndOpen(gateway);
            int events = scriptedDay(gateway);

            Reporter first = reporter(venue);
            first.start();
            awaitCheckpoint(first, events);
            assertTrue(first.stop());
            List<String> before = dump();
            assertFalse(before.isEmpty());

            TestDatabase.execute("UPDATE reporter_checkpoint SET last_inbound_seq = 0, last_outbound_seq = 0,"
                    + " inbound_position = 0, outbound_position = 0, session_business_date = NULL, session_symbol = NULL,"
                    + " inbound_recording_id = NULL, inbound_recording_ts = NULL, outbound_recording_id = NULL,"
                    + " outbound_recording_ts = NULL");

            // One big batch, so no resume happens mid-way: the second run is one re-application.
            Reporter second = new Reporter(TestDatabase.database()::connect, () -> { }, venue.journal(),
                    venue.pipeline().inboundRecording(), venue.pipeline().outboundRecording(),
                    new reporter.ReporterTiming(1_000, 20L, 10L, 100L, 1_000L, 5_000L, 5_000L), null);
            second.start();
            awaitCheckpoint(second, events);
            assertTrue(second.stop());
            assertEquals(before, dump());
        }
    }

    /** A restart reloads the open orders, so a fill and a cancel of an order from before it attribute exactly. */
    @Test
    void aRestartOfTheVenueResumesWithItsOpenOrdersAndSession() {
        var config = JournaledVenue.config(root);
        try (JournaledVenue venue = JournaledVenue.create(config, journal.JournaledPipeline.Seams.NONE)) {
            OrderGateway gateway = gateway(venue);
            venue.startAndOpen(gateway);
            gateway.onFrame(limit(1L, Side.SELL, BID, 10L));    // 2  (out 2)
            gateway.onFrame(limit(2L, Side.SELL, ASK, 10L));    // 3  (out 3)
            gateway.onFrame(limit(3L, Side.BUY, BID, 4L));      // 4  (out 4)
            Reporter reporter = reporter(venue);
            reporter.start();
            awaitCheckpoint(reporter, 4L);
            assertTrue(reporter.stop());
        }
        List<String> positionsBefore = rows("SELECT inbound_position, outbound_position FROM reporter_checkpoint");
        assertNotEquals(List.of("0 0"), positionsBefore);

        try (JournaledVenue again = JournaledVenue.create(config, journal.JournaledPipeline.Seams.NONE)) {
            OrderGateway gateway = gateway(again);
            again.startAndOpen(gateway);
            gateway.onFrame(limit(4L, Side.BUY, BID, 6L));      // 5  fills the rest of 1 (out 5)
            gateway.onFrame(cancel(5L, 2L));                    // 6  (out 6)
            Reporter reporter = reporter(again);
            reporter.start();
            awaitCheckpoint(reporter, 6L);
            assertTrue(reporter.stop());

            assertEquals(List.of("ORDER_FILLED 5 2 " + DAY), rows("SELECT event_type, order_inbound_seq,"
                    + " passive_order_inbound_seq, business_date FROM executions WHERE outbound_seq = 5"));
            assertEquals(List.of("ORDER_CANCELLED 3"), rows("SELECT event_type, order_inbound_seq FROM executions"
                    + " WHERE outbound_seq = 6"));
            assertEquals(List.of("FILLED 10"), rows("SELECT status, cum_quantity FROM orders WHERE inbound_seq = 2"));
        }
    }

    // --- candles ---

    private static byte[] asml() {
        return java.util.Arrays.copyOf("ASML".getBytes(StandardCharsets.US_ASCII), OrderEvent.SYMBOL_LENGTH);
    }

    private void trade(OrderGateway g, long id, long price, long qty) {
        g.onFrame(limit(id, Side.SELL, price, qty));
        g.onFrame(limit(id + 1, Side.BUY, price, qty));
    }

    @Test
    void candlesMatchTheExecutionsAcrossABucketBoundary() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = gateway(venue);
            venue.startAndOpen(gateway);

            clock.set(START + 1_000L);
            trade(gateway, 10L, 1_000_000L, 2L);    // opens the first bucket
            clock.set(START + 2_000L);
            trade(gateway, 20L, 1_000_500L, 3L);    // its high
            clock.set(START + 3_000L);
            trade(gateway, 30L, 999_500L, 4L);      // its low
            clock.set(START + Candles.BUCKET_NANOS - 1);
            trade(gateway, 40L, 1_000_100L, 5L);    // the last nano: still the first bucket, its close
            clock.set(START + Candles.BUCKET_NANOS);
            trade(gateway, 50L, 1_000_200L, 7L);    // exactly the boundary: the next bucket

            Reporter reporter = reporter(venue);
            reporter.start();
            awaitCheckpoint(reporter, 11L);
            assertTrue(reporter.stop());

            assertEquals(List.of(
                            "ASML " + START + " " + DAY + " 1000000 1000500 999500 1000100 14 4 9",
                            "ASML " + (START + Candles.BUCKET_NANOS) + " " + DAY + " 1000200 1000200 1000200 1000200 7 1 11"),
                    rows("SELECT symbol, bucket_start_ts, business_date, open, high, low, close, volume, trade_count,"
                            + " last_outbound_seq FROM candle ORDER BY bucket_start_ts"));

            // Recomputed from executions alone: the same volume, count, high and low in each bucket.
            assertEquals(rows("SELECT bucket_start_ts, volume, trade_count, high, low FROM candle ORDER BY bucket_start_ts"),
                    rows("SELECT event_ts - mod(event_ts, " + Candles.BUCKET_NANOS + "), sum(filled_quantity), count(*),"
                            + " max(price), min(price) FROM executions WHERE trade_id IS NOT NULL GROUP BY 1 ORDER BY 1"));
        }
    }

    @Test
    void aMinuteSpanningTwoBusinessDatesKeepsOneCandlePerDate() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = gateway(venue);
            venue.startAndOpen(gateway);
            clock.set(START + 1_000L);
            trade(gateway, 10L, 1_000_000L, 2L);                // day 1
            gateway.publishSessionClose(DAY);
            gateway.publishSessionOpen(NEXT_DAY, asml(), 100L, 1L, 10_000L);
            clock.set(START + 2_000L);
            trade(gateway, 20L, 1_000_500L, 3L);                // day 2, the same minute

            Reporter reporter = reporter(venue);
            reporter.start();
            awaitCheckpoint(reporter, 7L);
            assertTrue(reporter.stop());

            assertEquals(List.of(DAY + " 1000000 2 1", NEXT_DAY + " 1000500 3 1"),
                    rows("SELECT business_date, close, volume, trade_count FROM candle ORDER BY business_date"));
            assertEquals(List.of(DAY + " 2", NEXT_DAY + " 3"), rows("SELECT business_date, sum(filled_quantity)"
                    + " FROM executions WHERE trade_id IS NOT NULL GROUP BY 1 ORDER BY 1"));
        }
    }

    @Test
    void eachClosedDayIsRecordedForClearingWithItsCloseSeq() {
        try (JournaledVenue venue = JournaledVenue.create(root)) {
            OrderGateway gateway = gateway(venue);
            venue.startAndOpen(gateway);
            trade(gateway, 10L, 1_000_000L, 2L);
            gateway.publishSessionClose(DAY);
            gateway.publishSessionOpen(NEXT_DAY, asml(), 100L, 1L, 10_000L);
            trade(gateway, 20L, 1_000_500L, 3L);

            List<ExecutionEvent> events = recordedEvents(venue.journal(), 7);
            ExecutionEvent closed = events.get(3);
            assertEquals(ExecutionEventType.SESSION_CLOSED, closed.eventType);

            Reporter reporter = reporter(venue);
            reporter.start();
            awaitCheckpoint(reporter, 7L);
            assertTrue(reporter.stop());

            // One run, for the closed day only; the open day has none yet.
            assertEquals(List.of(DAY + " PENDING " + closed.seq + " " + closed.timestamp),
                    rows("SELECT business_date, status, close_outbound_seq, closed_ts FROM clearing_run"));
        }
    }
}
