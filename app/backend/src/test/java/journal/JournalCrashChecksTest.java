package journal;

import config.ExchangeConfig;
import io.aeron.driver.exceptions.ActiveDriverException;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.CRC32C;

import static journal.JournalTestSupport.config;
import static journal.JournalTestSupport.readAll;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The journal's crash handling (P15-5): the one retry of a launch refused by a fresh heartbeat,
 * which must still refuse a live instance; the operator instructions for a torn last frame; and
 * the per-frame CRC32C that lets the Archive verify a complete frame after a crash.
 *
 * The positive retry (a crashed run's heartbeat ageing out) is in
 * {@code integration.HardKillRecoveryTest}, which kills a real process.
 */
class JournalCrashChecksTest {

    @TempDir
    Path root;

    @Test
    void theRetryStillRefusesALiveInstanceAndLeavesItUntouched() {
        ExchangeConfig config = config(root);
        try (Journal live = Journal.launch(config)) {
            RecordedPublication recorded = live.openRecordedPublication(Journal.INBOUND_STREAM_ID);
            Sequencer.open(live, recorded, new org.agrona.concurrent.BackoffIdleStrategy());

            long start = System.nanoTime();
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> Journal.launchRetryingOnce(config, 300L));
            assertTrue((System.nanoTime() - start) / 1_000_000L >= 300L, "it waited before the retry");
            assertTrue(refused.getMessage().contains("Another exchange instance is running"), refused.getMessage());
            assertTrue(Journal.isLiveHeartbeat(refused));
            assertTrue(recorded.publication().isConnected(), "the live journal is untouched");
        }
        try (Journal again = Journal.launch(config)) {
            assertEquals(1, again.findRecordings(Journal.INBOUND_STREAM_ID).size(), "still one recording");
            assertEquals(0, readAll(again).size());
        }
    }

    @Test
    void aLaunchThatSucceedsFirstTimeDoesNotWait() {
        long start = System.nanoTime();
        try (Journal journal = Journal.launchRetryingOnce(config(root), 60_000L)) {
            assertNotNull(journal.archive());
        }
        assertTrue((System.nanoTime() - start) / 1_000_000L < 30_000L);
    }

    @Test
    void onlyHeartbeatRefusalsAreRetried() {
        assertTrue(Journal.isLiveHeartbeat(new ActiveDriverException("active driver detected")));
        assertTrue(Journal.isLiveHeartbeat(new RuntimeException(new IllegalStateException("active mark file detected"))));
        assertFalse(Journal.isLiveHeartbeat(new IllegalStateException("something else")));
    }

    @Test
    void aTornLastFrameGivesExactInstructions() {
        ExchangeConfig config = config(root);
        String message = Journal.tornFrameInstructions(config,
                "Found potentially incomplete last fragment straddling page boundary in file: x\nRun `ArchiveTool verify`");
        assertTrue(message.contains(config.archiveDir().toString()), message);
        assertTrue(message.contains("io.aeron.archive.ArchiveTool"), message);
        assertTrue(message.contains(" verify"), message);
        assertTrue(message.contains("--add-exports java.base/jdk.internal.misc=ALL-UNNAMED"), message);
        assertTrue(message.contains(System.getProperty("java.home")), message);
        assertTrue(message.contains("Back up"), message);
    }

    @Test
    void theRecordChecksumIsStandardCrc32c() {
        Random random = new Random(15_5L);
        ByteBuffer direct = BufferUtil.allocateDirectAligned(4_096, 64);
        UnsafeBuffer buffer = new UnsafeBuffer(direct);
        for (int length : new int[] {0, 1, 7, 32, 96, 1_000, 4_000}) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            buffer.putBytes(16, bytes);
            CRC32C expected = new CRC32C();
            expected.update(bytes, 0, length);
            assertEquals((int) expected.getValue(), Crc32cChecksum.INSTANCE.compute(buffer.addressOffset(), 16, length),
                    "length " + length);
        }
    }
}
