package journal;

import config.ExchangeConfig;
import integration.JournaledVenue;
import io.aeron.Aeron;
import io.aeron.AeronCounters;
import io.aeron.archive.client.ArchiveException;
import org.agrona.concurrent.status.CountersReader;
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
 * millisecond here; the Archive reads the property when its context is built (a field initializer of
 * {@code Archive.Context}, not a static). The Archive's default 5 s send timeout stays: at 1 ms pings
 * the keep-alive drains only a few pings per poll, and the driver frees window space in steps, so a
 * ping gets through every few seconds, which a shorter timeout would not allow.
 *
 * How fast the window fills depends on how often the Archive's thread runs, so neither test idles for
 * a fixed time and hopes (P15-12 item 0). They watch the Archive's own count of control sessions,
 * read through a second Aeron client on the same driver, so the journal's client is never polled.
 */
class JournalKeepAliveTest {

    private static final String PING_INTERVAL = "aeron.archive.session.liveness.check.interval";

    /** Bound on the wait for a session to die without the keep-alive. */
    private static final long DEATH_BOUND_MS = 60_000L;
    /** How long the keep-alive must hold the session: over twice what killing it takes when idle (about 6 s). */
    private static final long HOLD_MS = 15_000L;

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

    @Test
    void anIdleClientKeepsItsSessionAndTheJournalClosesCleanly() {
        Journal journal = Journal.launch(config);
        RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
        try (Aeron observer = observer()) {
            CountersReader counters = observer.countersReader();
            assertEquals(1L, controlSessions(counters));
            long deadline = System.nanoTime() + HOLD_MS * 1_000_000L;
            while (System.nanoTime() - deadline < 0) {
                assertEquals(1L, controlSessions(counters), "the keep-alive lost the session");
                LockSupport.parkNanos(50_000_000L);
            }
        }
        assertEquals(0L, journal.archive().getRecordingPosition(recorded.recordingId()));
        assertNotNull(journal.findRecording(Journal.OUTBOUND_STREAM_ID));
        assertDoesNotThrow(journal::close, "stopping a recording needs the session");
        assertFalse(keepAliveAlive(), "the thread stops with the journal");
    }

    /** What the keep-alive prevents: RESEND, the Reporter's reopen and shutdown all use this client. */
    @Test
    void withoutItAnIdleClientLosesItsSessionForGood() {
        Journal journal = Journal.launch(config, 0L);
        try (Aeron observer = observer()) {
            RecordedPublication recorded = journal.openRecordedPublication(Journal.OUTBOUND_STREAM_ID);
            CountersReader counters = observer.countersReader();
            assertEquals(1L, controlSessions(counters));
            long deadline = System.nanoTime() + DEATH_BOUND_MS * 1_000_000L;
            while (controlSessions(counters) != 0L) {
                assertTrue(System.nanoTime() - deadline < 0,
                        "the idle client still had its session after " + DEATH_BOUND_MS + " ms");
                LockSupport.parkNanos(10_000_000L);
            }
            assertThrows(ArchiveException.class, () -> journal.archive().getRecordingPosition(recorded.recordingId()));
        } finally {
            try {
                journal.close();
            } catch (RuntimeException expected) {
                // The dead client cannot stop the recording: that is the point of this test.
            }
        }
    }

    /** A second client on the journal's driver, for its counters only. */
    private Aeron observer() {
        return Aeron.connect(new Aeron.Context().aeronDirectoryName(config.aeronDir().toString()));
    }

    /** The Archive's count of open control sessions; the journal's client holds the only one. */
    private static long controlSessions(CountersReader counters) {
        long[] value = {-1L};
        counters.forEach((id, typeId, key, label) -> {
            if (typeId == AeronCounters.ARCHIVE_CONTROL_SESSIONS_TYPE_ID) {
                value[0] = counters.getCounterValue(id);
            }
        });
        assertNotEquals(-1L, value[0], "the Archive's control session counter is missing");
        return value[0];
    }

    private static boolean keepAliveAlive() {
        Thread[] threads = new Thread[Thread.activeCount() * 2];
        int count = Thread.enumerate(threads);
        return Arrays.stream(threads, 0, count).anyMatch(t -> Journal.KEEP_ALIVE_THREAD_NAME.equals(t.getName()));
    }
}
