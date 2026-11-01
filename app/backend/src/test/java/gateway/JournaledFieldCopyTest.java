package gateway;

import codec.EventFields;
import codec.OrderEventCodec;
import engine.CommandHandler;
import event.OrderEvent;
import integration.FixMessages;
import integration.JournaledVenue;
import journal.Journal;
import journal.JournalReader;
import journal.RecordingDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The D7 field-copy guard end to end: every {@link OrderEvent} field, from raw FIX bytes through
 * the gateway's copy into the ring slot, the sequencer's SBE encode, the journal, and the engine
 * agent's SBE decode, reaches the engine unchanged, and reads back unchanged from a replay of the
 * recording. The expected value is what the parser produces from the same bytes plus the
 * gateway's receipt stamp, compared by reflection over every field, so a field added to
 * OrderEvent and dropped at any copy point fails here.
 *
 * Session commands do not enter through FIX until P15-8; their journal round trip is covered by
 * SequencerTest and the codec tests.
 */
class JournaledFieldCopyTest {

    private static final long FIRST_STAMP = 1_700_000_000_000_000_000L;
    private static final List<Field> FIELDS = EventFields.instanceFields(OrderEvent.class);

    @TempDir
    Path root;

    /** Every shape a FIX frame can produce: both sides, every tif, absent tif, iceberg, peg, cancel. */
    private static List<byte[]> frames() {
        return List.of(
                FixMessages.limit(1L, '1', "150.25", 100),                 // no tag 59
                FixMessages.limit(2L, '2', "151.00", 200, '1'),            // GTC
                FixMessages.limit(3L, '1', "149.00", 30, '3'),             // IOC
                FixMessages.limit(4L, '2', "152.00", 40, '4'),             // FOK
                FixMessages.iceberg(5L, '2', "153.00", 1_000, 100),         // tag 111
                FixMessages.peg(6L, '1', 25, '1'),                         // 40=P, GTC
                FixMessages.peg(7L, '2', 15, '3'),                         // 40=P, IOC
                FixMessages.cancel(8L, 2L));                               // tag 41
    }

    @Test
    void everyOrderEventFieldSurvivesGatewaySequencerJournalAndAgentAndAReplay() {
        List<byte[]> frames = frames();
        List<OrderEvent> expected = new ArrayList<>();
        FixParser parser = new FixParser();
        for (int i = 0; i < frames.size(); i++) {
            OrderEvent e = new OrderEvent();
            e.clearSessionFields();
            byte[] frame = frames.get(i);
            assertTrue(parser.parse(frame, 0, frame.length, e), "frame " + i + " parses");
            e.timestamp = FIRST_STAMP + i;
            expected.add(e);
        }

        List<OrderEvent> atEngine = Collections.synchronizedList(new ArrayList<>());
        CommandHandler tap = command -> atEngine.add(copy(command));
        AtomicLong clock = new AtomicLong(FIRST_STAMP);

        try (JournaledVenue venue = JournaledVenue.create(root, tap)) {
            OrderGateway gateway = venue.gateway(clock::getAndIncrement);
            venue.start();
            for (byte[] frame : frames) gateway.onFrame(frame);

            awaitSize(atEngine, frames.size());
            for (int i = 0; i < frames.size(); i++) {
                assertSameFields(expected.get(i), atEngine.get(i), "at the engine, command " + i);
            }

            // Replay the recording: the journal holds exactly the same commands, seq from 1.
            Journal journal = venue.journal();
            RecordingDescriptor recording = journal.findRecording(Journal.INBOUND_STREAM_ID);
            OrderEventCodec codec = new OrderEventCodec();
            List<OrderEvent> replayed = new ArrayList<>();
            List<Long> seqs = new ArrayList<>();
            new JournalReader(journal.archive()).replayAll(recording.recordingId(), (buffer, offset, length, header) -> {
                OrderEvent e = new OrderEvent();
                seqs.add(codec.decode(buffer, offset, e));
                replayed.add(e);
            });
            assertEquals(frames.size(), replayed.size());
            for (int i = 0; i < frames.size(); i++) {
                assertEquals(i + 1, seqs.get(i), "seq of command " + i);
                assertSameFields(expected.get(i), replayed.get(i), "in the replay, command " + i);
            }
        }
    }

    private static OrderEvent copy(OrderEvent src) {
        OrderEvent dst = new OrderEvent();
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
        return dst;
    }

    private static void assertSameFields(OrderEvent want, OrderEvent got, String context) {
        for (Field f : FIELDS) {
            Object a = EventFields.read(f, want);
            Object b = EventFields.read(f, got);
            assertTrue(EventFields.same(a, b), context + ": field " + f.getName()
                    + " expected " + EventFields.show(a) + " but was " + EventFields.show(b));
        }
    }

    private static void awaitSize(List<?> list, int n) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (list.size() < n) {
            if (System.currentTimeMillis() > deadline) fail("only " + list.size() + " of " + n + " commands reached the engine");
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
