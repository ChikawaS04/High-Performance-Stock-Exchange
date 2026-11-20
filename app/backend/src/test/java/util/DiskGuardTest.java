package util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The disk guard (P16-8): states, the hard stop's entry, exit and hysteresis, the hourly lines, the data
 * directory's size, and the real file store with the warning set high. Samples are taken by the test, never
 * by the guard's thread, so nothing here waits.
 */
class DiskGuardTest {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long HOUR = 60L * 60_000L;
    private static final DiskGuardTiming TIMING = new DiskGuardTiming(5_000L, 60_000L, HOUR, 1_000L);

    @TempDir
    Path dir;

    private final AtomicLong free = new AtomicLong(100L * GIB);
    private final AtomicLong now = new AtomicLong();
    private final List<String> lines = new ArrayList<>();
    private volatile String held;

    private DiskGuard guard(long warn, long stop, long hysteresis) {
        DiskGuard.Space space = new DiskGuard.Space() {
            @Override
            public long usableBytes() {
                return free.get();
            }

            @Override
            public long totalBytes() {
                return 200L * GIB;
            }
        };
        return new DiskGuard(dir, space, warn, stop, hysteresis, () -> held, now::get, () -> 42L, TIMING,
                (level, text) -> lines.add(level + " " + text));
    }

    @Test
    void plentyOfSpaceIsOkAndAdmitsEverything() {
        DiskGuard g = guard(15 * GIB, 5 * GIB, GIB);
        assertFalse(g.refuseNew());
        DiskStatus s = g.status();
        assertEquals(DiskStatus.OK, s.state());
        assertEquals(100L * GIB, s.freeBytes());
        assertEquals(200L * GIB, s.totalBytes());
        assertEquals(15 * GIB, s.warnBelowBytes());
        assertEquals(5 * GIB, s.stopBelowBytes());
        assertEquals(42L, s.asOfTs());
        assertNull(s.retentionHeld());
        assertTrue(lines.isEmpty(), lines.toString());
    }

    @Test
    void belowTheWarningWarnsAtMostHourlyAndRecovers() {
        DiskGuard g = guard(15 * GIB, 5 * GIB, GIB);
        free.set(10 * GIB);
        g.sample();
        assertEquals(DiskStatus.LOW, g.status().state());
        assertFalse(g.refuseNew(), "LOW still admits NEW");
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("WARN Disk space low"), lines.get(0));
        now.set(HOUR - 1);
        g.sample();
        assertEquals(1, lines.size(), "no second WARN within the hour");
        now.set(HOUR);
        g.sample();
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("WARN"));
        free.set(16 * GIB);
        g.sample();
        assertEquals(DiskStatus.OK, g.status().state());
        assertTrue(lines.get(2).startsWith("INFO Disk space back above"), lines.get(2));
    }

    @Test
    void theHardStopEntersOnceLeavesWithHysteresisAndDoesNotFlap() {
        AtomicLong refused = new AtomicLong();
        DiskGuard g = guard(15 * GIB, 5 * GIB, GIB);
        g.countRefusalsFrom(refused::get);
        free.set(5 * GIB - 1);
        g.sample();
        assertTrue(g.refuseNew());
        assertEquals(DiskStatus.STOP, g.status().state());
        assertEquals(1, count("ERROR"), lines.toString());
        refused.set(7);

        // Back above the stop but not above stop + hysteresis: still refusing, nothing logged.
        free.set(5 * GIB + GIB / 2);
        g.sample();
        free.set(5 * GIB - 10);
        g.sample();
        free.set(6 * GIB - 1);
        g.sample();
        assertTrue(g.refuseNew());
        assertEquals(1, count("ERROR"), "one ERROR on entry, no flapping: " + lines);
        assertEquals(7L, g.status().newOrdersRefused());

        // Hourly repeat while it lasts.
        now.set(HOUR);
        g.sample();
        assertEquals(2, count("ERROR"));
        assertTrue(lines.get(lines.size() - 1).contains("7 refused"), lines.get(lines.size() - 1));

        // At stop + hysteresis: accepted again, one INFO, then LOW (still below the warning) with its WARN.
        free.set(6 * GIB);
        g.sample();
        assertFalse(g.refuseNew());
        assertEquals(DiskStatus.LOW, g.status().state());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("INFO Disk space recovered")), lines.toString());
        assertTrue(lines.get(lines.size() - 1).startsWith("WARN Disk space low"), lines.toString());
    }

    @Test
    void aStopOfZeroNeverRefuses() {
        DiskGuard g = guard(15 * GIB, 0L, GIB);
        free.set(0L);
        g.sample();
        assertFalse(g.refuseNew());
        assertEquals(DiskStatus.LOW, g.status().state());
    }

    @Test
    void theFirstSampleIsInTheConstructor() {
        free.set(GIB);
        DiskGuard g = guard(15 * GIB, 5 * GIB, GIB);
        assertTrue(g.refuseNew(), "refusing before any thread runs, so before the port binds");
    }

    @Test
    void whatHoldsRetentionIsShownAndLogged() {
        held = "journal retention skipped: REPORTER_NOT_RUNNING: FAILED";
        DiskGuard g = guard(15 * GIB, 5 * GIB, GIB);
        assertEquals(held, g.status().retentionHeld());
        free.set(GIB);
        g.sample();
        assertTrue(lines.get(0).contains("retention is held: journal retention skipped: REPORTER_NOT_RUNNING: FAILED"),
                lines.get(0));
    }

    @Test
    void retentionHeldNamesEitherSkippedRetention() {
        JournalStatus.Recording r = new JournalStatus.Recording(0, 0, 0, 0);
        JournalStatus journalSkipped = new JournalStatus(r, r, true, 2, 0, 1L, JournalStatus.SKIPPED,
                "REPORTER_NOT_RUNNING: FAILED", -1L, null, 1L);
        JournalStatus journalOk = new JournalStatus(r, r, true, 2, 0, 1L, JournalStatus.NOTHING_TO_PURGE, null, -1L,
                null, 1L);
        DatabaseRetentionStatus dbSkipped = new DatabaseRetentionStatus(true, 14, 1L, DatabaseRetentionStatus.SKIPPED,
                "CLEARING_FAILED: clearing is FAILED", -1, -1L, 0L, 1L);
        assertNull(DiskGuard.retentionHeld(journalOk, null));
        assertEquals("journal retention skipped: REPORTER_NOT_RUNNING: FAILED",
                DiskGuard.retentionHeld(journalSkipped, null));
        assertEquals("journal retention skipped: REPORTER_NOT_RUNNING: FAILED; database retention skipped:"
                + " CLEARING_FAILED: clearing is FAILED", DiskGuard.retentionHeld(journalSkipped, dbSkipped));
    }

    @Test
    void aFailedReadKeepsTheLastStateAndWarnsOnce() {
        DiskGuard.Space broken = new DiskGuard.Space() {
            @Override
            public long usableBytes() throws IOException {
                throw new IOException("gone");
            }

            @Override
            public long totalBytes() throws IOException {
                throw new IOException("gone");
            }
        };
        DiskGuard g = new DiskGuard(dir, broken, 15 * GIB, 5 * GIB, GIB, () -> null, now::get, () -> 1L, TIMING,
                (level, text) -> lines.add(level + " " + text));
        g.sample();
        assertFalse(g.refuseNew(), "doubt never changes the stop");
        assertEquals(-1L, g.status().freeBytes());
        assertEquals(1, count("WARN"), lines.toString());
    }

    @Test
    void dataBytesIsTheDataDirectoryWalkedAtMostEveryMinute() throws IOException {
        Files.write(dir.resolve("a.dat"), new byte[1000]);
        Files.createDirectories(dir.resolve("archive"));
        Files.write(dir.resolve("archive").resolve("0-0.rec"), new byte[24]);
        DiskGuard g = guard(15 * GIB, 5 * GIB, GIB);
        assertEquals(1024L, g.status().dataBytes());
        Files.write(dir.resolve("b.dat"), new byte[1000]);
        now.set(59_999L);
        g.sample();
        assertEquals(1024L, g.status().dataBytes(), "not walked again within the minute");
        now.set(60_000L);
        g.sample();
        assertEquals(2024L, g.status().dataBytes());
    }

    @Test
    void theRealFileStoreWithTheWarningSetHighIsLow() throws IOException {
        List<String> real = new ArrayList<>();
        DiskGuard g = DiskGuard.forDataDir(dir, Long.MAX_VALUE, 0L, GIB, () -> null, () -> 7L);
        assertTrue(g.status().freeBytes() > 0, "a real figure: " + g.status());
        assertTrue(g.status().totalBytes() >= g.status().freeBytes());
        assertEquals(DiskStatus.LOW, g.status().state());
        assertFalse(g.refuseNew());
        // The same store, through the test sink: the WARN names the threshold.
        java.nio.file.FileStore store = Files.getFileStore(dir);
        DiskGuard.Space space = new DiskGuard.Space() {
            @Override
            public long usableBytes() throws IOException {
                return store.getUsableSpace();
            }

            @Override
            public long totalBytes() throws IOException {
                return store.getTotalSpace();
            }
        };
        new DiskGuard(dir, space, Long.MAX_VALUE, 0L, GIB, () -> null, now::get, () -> 1L, TIMING,
                (level, text) -> real.add(level + " " + text));
        assertEquals(1, real.size(), real.toString());
        assertTrue(real.get(0).startsWith("WARN Disk space low"), real.get(0));
        assertTrue(real.get(0).contains(Long.toString(Long.MAX_VALUE)), real.get(0));
    }

    @Test
    void theThreadStartsAndCloses() {
        DiskGuard g = guard(15 * GIB, 5 * GIB, GIB);
        g.start();
        g.close();
    }

    private long count(String level) {
        return lines.stream().filter(l -> l.startsWith(level + " ")).count();
    }
}
