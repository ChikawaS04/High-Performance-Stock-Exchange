package journal;

import config.ExchangeConfig;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What Aeron 1.53.3's Archive does when usable space is low (P16-8, the greenlit check "confirmed from source
 * and proven by a test"). The low storage threshold is checked only when a recording starts or extends: an
 * active recording keeps recording below it, while a new or extended recording is refused with
 * {@code STORAGE_SPACE}. So the venue needs its own hard stop for a running journal, and the threshold is a
 * startup floor, which the journal turns into a startup refusal that names the remedy.
 */
class ArchiveLowStorageTest {

    private static final int STREAM = 1001;
    private static final int OTHER_STREAM = 1002;
    private static final long THRESHOLD = 10L * 1024 * 1024;
    private static final long AWAIT_MS = 10_000L;

    @TempDir
    Path root;

    /** A file store whose usable space the test sets. */
    private static final class FakeStore extends FileStore {
        final AtomicLong usable = new AtomicLong(Long.MAX_VALUE / 2);

        @Override public String name() { return "fake"; }
        @Override public String type() { return "fake"; }
        @Override public boolean isReadOnly() { return false; }
        @Override public long getTotalSpace() { return Long.MAX_VALUE / 2; }
        @Override public long getUsableSpace() { return usable.get(); }
        @Override public long getUnallocatedSpace() { return usable.get(); }
        @Override public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) { return false; }
        @Override public boolean supportsFileAttributeView(String name) { return false; }
        @Override public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) { return null; }
        @Override public Object getAttribute(String attribute) throws IOException {
            throw new UnsupportedOperationException(attribute);
        }
    }

    private ExchangeConfig config() {
        return ExchangeConfig.from(Map.of(
                ExchangeConfig.EXCHANGE_DATA_DIR, root.resolve("data").toString(),
                ExchangeConfig.AERON_DIR, root.resolve("aeron").toString(),
                ExchangeConfig.JOURNAL_TERM_LENGTH, Integer.toString(64 * 1024),
                ExchangeConfig.JOURNAL_SEGMENT_LENGTH, Integer.toString(64 * 1024)));
    }

    private static void await(String what, BooleanSupplier done) {
        long deadline = System.nanoTime() + AWAIT_MS * 1_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }

    @Test
    void anActiveRecordingKeepsRecordingBelowTheThresholdButNoRecordingStartsOrExtends() {
        ExchangeConfig config = config();
        FakeStore store = new FakeStore();
        Archive.Context archiveContext = Journal.archiveContext(config)
                .archiveFileStore(store)
                .lowStorageSpaceThreshold(THRESHOLD);
        ArchivingMediaDriver driver = ArchivingMediaDriver.launch(Journal.driverContext(config), archiveContext);
        Aeron aeron = null;
        AeronArchive archive = null;
        try {
            aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(config.aeronDir().toString()));
            archive = AeronArchive.connect(new AeronArchive.Context()
                    .aeron(aeron)
                    .controlRequestChannel(AeronArchive.Configuration.localControlChannel())
                    .controlRequestStreamId(AeronArchive.Configuration.localControlStreamId())
                    .controlResponseChannel(Journal.IPC_CHANNEL));
            archive.startRecording(Journal.IPC_CHANNEL, STREAM, SourceLocation.LOCAL);
            ExclusivePublication publication = aeron.addExclusivePublication(Journal.IPC_CHANNEL, STREAM);
            CountersReader counters = aeron.countersReader();
            long archiveId = archive.archiveId();
            await("the recording counter", () -> RecordingPos.findCounterIdBySession(counters, publication.sessionId(),
                    archiveId) != CountersReader.NULL_COUNTER_ID);
            int counterId = RecordingPos.findCounterIdBySession(counters, publication.sessionId(), archiveId);
            UnsafeBuffer message = new UnsafeBuffer(ByteBuffer.allocateDirect(256));

            offer(publication, message, 100);
            long before = publication.position();
            await("the first 100 messages recorded", () -> counters.getCounterValue(counterId) >= before);

            // Below the threshold: the active recording goes on, past segment boundaries (64 KiB segments).
            store.usable.set(THRESHOLD - 1);
            offer(publication, message, 1_000);
            long after = publication.position();
            assertTrue(after > before + 2 * 64 * 1024, "more than two segments offered below the threshold");
            await("every message recorded below the threshold", () -> counters.getCounterValue(counterId) >= after);
            assertTrue(RecordingPos.isActive(counters, counterId, RecordingPos.getRecordingId(counters, counterId)),
                    "still active: the Archive does not stop a recording for low storage");

            // But a new recording is refused...
            AeronArchive client = archive;
            ArchiveException refused = assertThrows(ArchiveException.class,
                    () -> client.startRecording(Journal.IPC_CHANNEL, OTHER_STREAM, SourceLocation.LOCAL));
            assertEquals(ArchiveException.STORAGE_SPACE, refused.errorCode(), refused.getMessage());

            // ...and so is extending a stopped one.
            long recordingId = RecordingPos.getRecordingId(counters, counterId);
            archive.stopRecording(Journal.IPC_CHANNEL, STREAM);
            CloseHelper.close(publication);
            ArchiveException extend = assertThrows(ArchiveException.class,
                    () -> client.extendRecording(recordingId, Journal.IPC_CHANNEL, STREAM, SourceLocation.LOCAL));
            assertEquals(ArchiveException.STORAGE_SPACE, extend.errorCode(), extend.getMessage());
        } finally {
            CloseHelper.quietCloseAll(archive, aeron, driver);
        }
    }

    private static void offer(ExclusivePublication publication, UnsafeBuffer message, int count) {
        for (int i = 0; i < count; i++) {
            message.putInt(0, i);
            long deadline = System.nanoTime() + AWAIT_MS * 1_000_000L;
            while (publication.offer(message, 0, 200) < 0) {
                if (System.nanoTime() > deadline) fail("could not offer message " + i);
                LockSupport.parkNanos(100_000L);
            }
        }
    }

    @Test
    void theJournalRefusesToStartAtOrBelowTheFloorAndNamesTheRemedy() {
        // A floor of 1 PiB is above any real free space. The stop is off so the cross-check allows it.
        ExchangeConfig config = ExchangeConfig.from(Map.of(
                ExchangeConfig.EXCHANGE_DATA_DIR, root.resolve("data").toString(),
                ExchangeConfig.AERON_DIR, root.resolve("aeron").toString(),
                ExchangeConfig.DISK_STOP_FREE_BYTES, "0",
                ExchangeConfig.JOURNAL_LOW_STORAGE_BYTES, Long.toString(1L << 50)));
        Journal journal = Journal.launch(config, 500L);
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> journal.openRecordedPublication(Journal.INBOUND_STREAM_ID));
            assertTrue(e.getMessage().contains(ExchangeConfig.JOURNAL_LOW_STORAGE_BYTES), e.getMessage());
            assertTrue(e.getMessage().contains("Nothing was written"), e.getMessage());
            assertEquals(ArchiveException.STORAGE_SPACE, ((ArchiveException) e.getCause()).errorCode());
        } finally {
            journal.close();
        }
    }
}
