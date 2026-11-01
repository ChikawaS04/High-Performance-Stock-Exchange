package journal;

import codec.EventFields;
import codec.OrderEventCodec;
import com.lmax.disruptor.EventTranslatorOneArg;
import config.ExchangeConfig;
import event.OrderEvent;
import event.OrderEventType;
import model.OrdType;
import model.Side;
import model.TimeInForce;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared helpers for the journal tests: a config over a temporary directory, command
 * builders whose unused fields hold exactly the reset values the decoder writes, a
 * reflective copy translator (so a field added to OrderEvent is copied here without anyone
 * remembering), a full-recording reader, and a bounded await.
 */
final class JournalTestSupport {

    static final long TIMEOUT_MS = 10_000;
    static final int SMALL = 64 * 1024;

    private static final List<Field> FIELDS = EventFields.instanceFields(OrderEvent.class);

    private JournalTestSupport() { }

    /** Data and driver directories under {@code root}; 64 KiB terms and segments. */
    static ExchangeConfig config(Path root) {
        return config(root.resolve("data"), root.resolve("aeron"), SMALL, SMALL);
    }

    static ExchangeConfig config(Path dataDir, Path aeronDir, int termLength, int segmentLength) {
        return ExchangeConfig.from(Map.of(
                ExchangeConfig.EXCHANGE_DATA_DIR, dataDir.toString(),
                ExchangeConfig.AERON_DIR, aeronDir.toString(),
                ExchangeConfig.JOURNAL_TERM_LENGTH, Integer.toString(termLength),
                ExchangeConfig.JOURNAL_SEGMENT_LENGTH, Integer.toString(segmentLength)));
    }

    /** Copies every instance field, the symbol bytes included. */
    static void copy(OrderEvent src, OrderEvent dst) {
        try {
            for (Field f : FIELDS) {
                if (f.getType() == byte[].class) {
                    byte[] from = (byte[]) f.get(src);
                    System.arraycopy(from, 0, (byte[]) f.get(dst), 0, from.length);
                } else {
                    f.set(dst, f.get(src));
                }
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    static OrderEvent copyOf(OrderEvent src) {
        OrderEvent dst = new OrderEvent();
        copy(src, dst);
        return dst;
    }

    static final EventTranslatorOneArg<OrderEvent, OrderEvent> COPY_INTO_SLOT =
            (slot, sequence, src) -> copy(src, slot);

    // --- command builders: unused fields carry the decoder's reset values ---

    static OrderEvent newOrder(long orderId, Side side, OrdType ordType, TimeInForce tif,
                               long price, long quantity, long maxFloor, long timestamp) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.NEW_ORDER;
        e.orderId = orderId;
        e.side = side;
        e.ordType = ordType;
        e.tif = tif;
        e.price = price;
        e.quantity = quantity;
        e.maxFloor = maxFloor;
        e.timestamp = timestamp;
        e.originalOrderId = -1L;
        e.clearSessionFields();
        return e;
    }

    static OrderEvent limit(long orderId, long timestamp) {
        return newOrder(orderId, Side.BUY, OrdType.LIMIT, TimeInForce.GTC, 1_000_000L, 10L, 0L, timestamp);
    }

    static OrderEvent cancel(long orderId, long originalOrderId, long timestamp) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.CANCEL_ORDER;
        e.orderId = orderId;
        e.originalOrderId = originalOrderId;
        e.timestamp = timestamp;
        clearOrderFields(e);
        e.clearSessionFields();
        return e;
    }

    static OrderEvent sessionOpen(int businessDate, String symbol, long timestamp) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.SESSION_OPEN;
        e.orderId = -1L;
        e.originalOrderId = -1L;
        e.timestamp = timestamp;
        clearOrderFields(e);
        e.clearSessionFields();
        e.businessDate = businessDate;
        byte[] ascii = symbol.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, e.symbol, 0, ascii.length);
        e.tickSize = 100L;
        e.lotSize = 1L;
        e.priceScale = 10_000L;
        return e;
    }

    static OrderEvent sessionClose(int businessDate, long timestamp) {
        OrderEvent e = new OrderEvent();
        e.eventType = OrderEventType.SESSION_CLOSE;
        e.orderId = -1L;
        e.originalOrderId = -1L;
        e.timestamp = timestamp;
        clearOrderFields(e);
        e.clearSessionFields();
        e.businessDate = businessDate;
        return e;
    }

    private static void clearOrderFields(OrderEvent e) {
        e.side = null;
        e.ordType = null;
        e.tif = null;
        e.price = -1L;
        e.quantity = -1L;
        e.maxFloor = 0L;
    }

    // --- reading the journal back ---

    record Journaled(long seq, OrderEvent event) { }

    /** Every command in the inbound recording, decoded in journal order. */
    static List<Journaled> readAll(Journal journal) {
        RecordingDescriptor recording = journal.findRecording(Journal.INBOUND_STREAM_ID);
        List<Journaled> out = new ArrayList<>();
        if (recording == null) return out;
        OrderEventCodec codec = new OrderEventCodec();
        new JournalReader(journal.archive()).replayAll(recording.recordingId(), (buffer, offset, length, header) -> {
            OrderEvent e = new OrderEvent();
            long seq = codec.decode(buffer, offset, e);
            out.add(new Journaled(seq, e));
        });
        return out;
    }

    /** Reads the recording until it holds {@code expected} commands (the Archive may lag the offer). */
    static List<Journaled> readAll(Journal journal, int expected) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        List<Journaled> all;
        while ((all = readAll(journal)).size() < expected) {
            if (System.currentTimeMillis() > deadline) {
                RecordingDescriptor r = journal.findRecording(Journal.INBOUND_STREAM_ID);
                fail("recording holds " + all.size() + " commands, expected " + expected + "; recording="
                        + (r == null ? "none" : r.recordingId() + " start=" + journal.archive().getStartPosition(r.recordingId())
                        + " max=" + journal.archive().getMaxRecordedPosition(r.recordingId())));
            }
            LockSupport.parkNanos(1_000_000L);
        }
        assertEquals(expected, all.size(), "recording holds more commands than were sequenced");
        return all;
    }

    /** Asserts every field of {@code actual} equals {@code expected}. */
    static void assertSameFields(OrderEvent expected, OrderEvent actual, String context) {
        for (Field f : FIELDS) {
            Object want = EventFields.read(f, expected);
            Object got = EventFields.read(f, actual);
            assertTrue(EventFields.same(want, got), context + ": field " + f.getName()
                    + " expected " + EventFields.show(want) + " but was " + EventFields.show(got));
        }
    }

    /** Bounded wait for {@code condition}. */
    static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
