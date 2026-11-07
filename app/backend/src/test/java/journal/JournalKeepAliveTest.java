package journal;

import config.ExchangeConfig;
import integration.JournaledVenue;
import io.aeron.archive.client.ArchiveException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Archive pings every control session, and a client that reads nothing fills its response window
 * and loses the session for good. At the real 1 s interval that takes minutes, so the pings run every
 * millisecond here; the Archive reads the property when its context is built.
 */
class JournalKeepAliveTest {

    private static final String PING_INTERVAL = "aeron.archive.session.liveness.check.interval";

    /** Long enough for the response window to fill and the Archive's connect timeout to pass. */
    private static final long IDLE_MS = 12_000L;

    @TempDir
    Path root;

    private ExchangeConfig config;

    @BeforeEach
    void pingEveryMillisecond() {
        System.setProperty(PING_INTERVAL, "1ms");
        config = JournaledVenue.config(root);
    }

    @AfterEach
    void restorePingInterval() {
        System.clearProperty(PING_INTERVAL);
    }

    private static void idle() {
        long deadline = System.nanoTime() + IDLE_MS * 1_000_000L;
        while (System.nanoTime() - deadline < 0) {
            LockSupport.parkNanos(50_000_000L);
        }
    }

    @Test
    void anIdleClientKeepsItsSessionAndTheJournalClosesCleanly() {
        Journal journal = Journal.launch(config);
        RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
        idle();
        assertEquals(0L, journal.archive().getRecordingPosition(recorded.recordingId()));
        assertNotNull(journal.findRecording(Journal.OUTBOUND_STREAM_ID));
        assertDoesNotThrow(journal::close, "stopping a recording needs the session");
        assertFalse(keepAliveAlive(), "the thread stops with the journal");
    }

    /** What the keep-alive prevents: RESEND, the Reporter's reopen and shutdown all use this client. */
    @Test
    void withoutItAnIdleClientLosesItsSessionForGood() {
        Journal journal = Journal.launch(config, 0L);
        try {
            RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
            idle();
            assertThrows(ArchiveException.class, () -> journal.archive().getRecordingPosition(recorded.recordingId()));
        } finally {
            try {
                journal.close();
            } catch (RuntimeException expected) {
                // The dead client cannot stop the recording: that is the point of this test.
            }
        }
    }

    private static boolean keepAliveAlive() {
        Thread[] threads = new Thread[Thread.activeCount() * 2];
        int count = Thread.enumerate(threads);
        return Arrays.stream(threads, 0, count).anyMatch(t -> Journal.KEEP_ALIVE_THREAD_NAME.equals(t.getName()));
    }
}
