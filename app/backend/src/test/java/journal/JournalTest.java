package journal;

import config.ExchangeConfig;
import io.aeron.CncFileDescriptor;
import io.aeron.ExclusivePublication;
import io.aeron.driver.exceptions.ActiveDriverException;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static journal.JournalTestSupport.await;
import static journal.JournalTestSupport.config;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle of the embedded media driver and Archive (SRS §3.9, D10): one recording per
 * stream, extended across restarts; the driver directory recreated, the Archive directory
 * kept; and a second exchange on the same directories refused without harming the first.
 */
class JournalTest {

    @TempDir
    Path root;

    private static void offerBytes(ExclusivePublication publication, int count) {
        UnsafeBuffer bytes = new UnsafeBuffer(new byte[64]);
        for (int i = 0; i < count; i++) {
            bytes.putInt(0, i);
            final int n = i;
            await(() -> publication.offer(bytes, 0, 64) > 0, "offer " + n);
        }
    }

    @Test
    void firstLaunchStartsOneRecording() {
        try (Journal journal = Journal.launch(config(root))) {
            assertNull(journal.findRecording(Journal.INBOUND_STREAM_ID));

            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);

            assertFalse(recorded.extended());
            assertEquals(recorded.startPosition(), recorded.joinPosition());
            assertEquals(1, journal.findRecordings(Journal.INBOUND_STREAM_ID).size());
            assertEquals(recorded.recordingId(), journal.findRecording(Journal.INBOUND_STREAM_ID).recordingId());
            assertTrue(recorded.publication().isConnected());
        }
    }

    @Test
    void relaunchExtendsTheSameRecording() throws IOException {
        ExchangeConfig cfg = config(root);
        long recordingId;
        long startPosition;
        try (Journal journal = Journal.launch(cfg)) {
            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            recordingId = recorded.recordingId();
            startPosition = recorded.startPosition();
            offerBytes(recorded.publication(), 2_000);   // about 3 terms, several 64 KiB segments
        }
        assertTrue(hasFiles(cfg.archiveDir()), "archive directory must survive a clean stop");

        long firstStop;
        try (Journal journal = Journal.launch(cfg)) {
            RecordingDescriptor before = journal.findRecording(Journal.INBOUND_STREAM_ID);
            assertEquals(recordingId, before.recordingId());
            firstStop = before.stopPosition();
            assertTrue(firstStop > startPosition);

            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            assertTrue(recorded.extended());
            assertEquals(recordingId, recorded.recordingId());
            assertEquals(startPosition, recorded.startPosition());
            assertEquals(firstStop, recorded.joinPosition());
            offerBytes(recorded.publication(), 10);
        }

        try (Journal journal = Journal.launch(cfg)) {
            assertEquals(1, journal.findRecordings(Journal.INBOUND_STREAM_ID).size());
            RecordingDescriptor after = journal.findRecording(Journal.INBOUND_STREAM_ID);
            assertEquals(recordingId, after.recordingId());
            assertEquals(startPosition, after.startPosition());
            assertTrue(after.stopPosition() > firstStop);
        }
    }

    @Test
    void staleDriverDirectoryIsDeletedOnLaunch() throws IOException {
        ExchangeConfig cfg = config(root);
        Path stale = cfg.aeronDir().resolve("stale-file");
        Files.createDirectories(cfg.aeronDir());
        Files.writeString(stale, "left behind by a dead process");

        try (Journal journal = Journal.launch(cfg)) {
            assertFalse(Files.exists(stale), "a stale driver directory is recreated");
            assertTrue(Files.exists(cfg.aeronDir().resolve(CncFileDescriptor.CNC_FILE)));
            journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
        }
        assertFalse(Files.exists(cfg.aeronDir()), "the driver directory is deleted on clean shutdown");
    }

    @Test
    void secondLaunchOnTheSameDriverDirectoryFailsAndLeavesTheFirstWorking() {
        ExchangeConfig first = config(root.resolve("a"));
        ExchangeConfig second = config(root.resolve("b").resolve("data"), first.aeronDir(),
                JournalTestSupport.SMALL, JournalTestSupport.SMALL);

        try (Journal journal = Journal.launch(first)) {
            assertThrows(ActiveDriverException.class, () -> Journal.launch(second));

            assertTrue(Files.exists(first.aeronDir().resolve(CncFileDescriptor.CNC_FILE)),
                    "the refused launch must not touch the live driver's directory");
            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            offerBytes(recorded.publication(), 100);
        }
        try (Journal journal = Journal.launch(first)) {
            assertNotNull(journal.findRecording(Journal.INBOUND_STREAM_ID));
        }
    }

    @Test
    void secondLaunchOnTheSameArchiveDirectoryFailsAndLeavesTheFirstWorking() {
        ExchangeConfig first = config(root.resolve("a"));
        ExchangeConfig second = config(first.dataDir(), root.resolve("b").resolve("aeron"),
                JournalTestSupport.SMALL, JournalTestSupport.SMALL);

        try (Journal journal = Journal.launch(first)) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> Journal.launch(second));
            assertTrue(refused.getMessage().contains("active mark file"), refused.getMessage());

            RecordedPublication recorded = journal.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            offerBytes(recorded.publication(), 100);
        }
        try (Journal journal = Journal.launch(first)) {
            assertEquals(1, journal.findRecordings(Journal.INBOUND_STREAM_ID).size());
        }
    }

    private static boolean hasFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.findAny().isPresent();
        }
    }
}
