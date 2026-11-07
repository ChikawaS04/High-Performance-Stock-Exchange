package integration;

import codec.ExecutionEventCodec;
import db.TestDatabase;
import event.ExecutionEvent;
import journal.Journal;
import journal.JournalReader;
import journal.RecordingDescriptor;
import model.OrdType;
import model.Side;
import model.TimeInForce;
import net.JsonToFix;
import reporter.Reporter;
import reporter.ReporterTiming;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/** What the Reporter's integration tests share: FIX frames, bounded awaits, recording and row reads. */
final class ReporterHarness {

    /** Small batches, so a scripted day crosses batch boundaries mid-command, and short delays. */
    static final ReporterTiming FAST = new ReporterTiming(3, 20L, 10L, 100L, 1_000L, 5_000L, 5_000L);

    private static final long AWAIT_MS = 15_000L;

    private ReporterHarness() {
    }

    static Reporter reporter(JournaledVenue venue, Reporter.ConnectionSource connections, Runnable beforeCommit) {
        return new Reporter(connections, () -> { }, venue.journal(), venue.pipeline().inboundRecording(),
                venue.pipeline().outboundRecording(), FAST, beforeCommit);
    }

    static Reporter reporter(JournaledVenue venue) {
        return reporter(venue, TestDatabase.database()::connect, null);
    }

    static byte[] limit(long id, Side side, long price, long qty) {
        return JsonToFix.newOrderSingle(id, side, OrdType.LIMIT, price, qty, "ASML", TimeInForce.GTC, 0L);
    }

    static byte[] limit(long id, Side side, long price, long qty, TimeInForce tif) {
        return JsonToFix.newOrderSingle(id, side, OrdType.LIMIT, price, qty, "ASML", tif, 0L);
    }

    static byte[] iceberg(long id, Side side, long price, long qty, long peak) {
        return JsonToFix.newOrderSingle(id, side, OrdType.LIMIT, price, qty, "ASML", TimeInForce.GTC, peak);
    }

    static byte[] peg(long id, Side side, long qty, TimeInForce tif) {
        return JsonToFix.newOrderSingle(id, side, OrdType.PEG_MID, -1L, qty, "ASML", tif, 0L);
    }

    static byte[] cancel(long id, long original) {
        return JsonToFix.orderCancelRequest(id, original);
    }

    static void await(String what, BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for " + what);
            }
            LockSupport.parkNanos(1_000_000L);
        }
    }

    static void awaitCheckpoint(Reporter reporter, long outboundSeq) {
        await("the Reporter to reach outbound seq " + outboundSeq + " (at " + reporter.outboundSeq() + ", state "
                + reporter.state() + ", failure " + reporter.failure() + ")", () -> {
            if ("FAILED".equals(reporter.state())) {
                fail("The Reporter failed: " + reporter.failure());
            }
            return reporter.outboundSeq() >= outboundSeq;
        });
    }

    static void awaitState(Reporter reporter, String state) {
        await("the Reporter to be " + state + " (is " + reporter.state() + ")", () -> state.equals(reporter.state()));
    }

    /** Every event in the outbound recording, decoded fresh, with its command seq. */
    static List<ExecutionEvent> recordedEvents(Journal journal) {
        RecordingDescriptor recording = journal.findRecording(Journal.OUTBOUND_STREAM_ID);
        List<ExecutionEvent> events = new ArrayList<>();
        ExecutionEventCodec codec = new ExecutionEventCodec();
        new JournalReader(journal.archive()).replayAll(recording.recordingId(), (buffer, offset, length, header) -> {
            ExecutionEvent e = new ExecutionEvent();
            codec.decode(buffer, offset, e);
            events.add(e);
        });
        return events;
    }

    /** Reads until the outbound recording holds {@code expected} events: the Archive may lag the offer. */
    static List<ExecutionEvent> recordedEvents(Journal journal, int expected) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        List<ExecutionEvent> events;
        while ((events = recordedEvents(journal)).size() < expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("The outbound recording holds " + events.size() + " events, expected " + expected);
            }
            LockSupport.parkNanos(1_000_000L);
        }
        return events;
    }

    /** Every row of {@code sql}, its columns joined by a space, NULL as {@code null}. */
    static List<String> rows(String sql) {
        List<String> rows = new ArrayList<>();
        try (Connection connection = TestDatabase.database().connect();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            int columns = row.getMetaData().getColumnCount();
            while (row.next()) {
                StringBuilder text = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) text.append(' ');
                    text.append(row.getString(i));
                }
                rows.add(text.toString());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
        return rows;
    }

    /** Every row of every table the Reporter writes, for comparing two states. */
    static List<String> dump() {
        List<String> all = new ArrayList<>();
        all.addAll(rows("SELECT o::text FROM orders o ORDER BY inbound_seq"));
        all.addAll(rows("SELECT e::text FROM executions e ORDER BY outbound_seq"));
        all.addAll(rows("SELECT a::text FROM order_audit a ORDER BY stream, seq"));
        all.addAll(rows("SELECT c::text FROM candle c ORDER BY business_date, bucket_start_ts"));
        return all;
    }
}
