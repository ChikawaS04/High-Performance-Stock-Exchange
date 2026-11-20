package util;

import gateway.NewOrderAdmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The disk guard (P16-8): one daemon thread named {@value #THREAD_NAME} that watches the free space of the
 * filesystem holding the data directory, publishes it for {@code /health} ({@link DiskStatus}), and holds the
 * venue's hard stop.
 *
 * <p><b>Why the venue needs its own stop.</b> In Aeron 1.53.3 the Archive's low storage threshold is checked
 * only when a recording starts or extends; an active recording writes until a write fails, and then the
 * recording stops and the engine fail-stops at the D11 gate. So below {@code DISK_STOP_FREE_BYTES} the
 * gateway refuses NEW orders ({@link NewOrderAdmission}), before sequencing, so nothing refused is ever
 * journaled; cancels, RESEND and session commands go on, as do both retentions, which may free space.
 * NEW is accepted again once free space reaches the stop value plus {@code DISK_STOP_HYSTERESIS_BYTES}, so
 * the state does not flap.
 *
 * <p><b>Logging.</b> ERROR once on entering the stop, repeated at most once an hour while it holds, INFO on
 * leaving it. While free space is below {@code DISK_WARN_FREE_BYTES} (and the stop does not hold), a WARN at
 * most once an hour, INFO once it rises above. Every line names what holds retention back, if anything, so a
 * FAILED Reporter that keeps the journal from being purged shows as the cause (P16-7 finding 7).
 *
 * <p>The first sample is taken in the constructor, so the stop is right before the port binds. Never on an
 * event loop or the engine thread; the gateway reads one volatile per NEW.
 */
public final class DiskGuard implements NewOrderAdmission, AutoCloseable {

    public static final String THREAD_NAME = "disk-guard";

    /** The filesystem's figures; a seam for tests. */
    public interface Space {
        long usableBytes() throws IOException;

        long totalBytes() throws IOException;
    }

    /** Where log lines go; a seam for tests. */
    @FunctionalInterface
    public interface Sink {
        void log(String level, String text);
    }

    private static final Logger log = LoggerFactory.getLogger(DiskGuard.class);

    /** Logs through slf4j at the named level. */
    public static final Sink SLF4J = (level, text) -> {
        switch (level) {
            case "ERROR" -> log.error(text);
            case "WARN" -> log.warn(text);
            default -> log.info(text);
        }
    };

    private final Path dataDir;
    private final Space space;
    private final long warnBelow;
    private final long stopBelow;
    private final long hysteresis;
    private final Supplier<String> retentionHeld;
    private final LongSupplier nowMs;
    private final LongSupplier epochNanos;
    private final DiskGuardTiming timing;
    private final Sink sink;
    private final Thread thread;

    private volatile boolean stopped;
    private volatile DiskStatus status;
    private volatile LongSupplier refusedCount = () -> 0L;
    private volatile boolean closing;

    // Guard thread only (and the constructor, before the thread starts).
    private long free = -1L;
    private long total = -1L;
    private long dataBytes = -1L;
    private boolean low;
    private long lastRepeatMs;
    private long lastDataWalkMs = Long.MIN_VALUE;
    private boolean readFailed;
    private boolean started;

    /**
     * @param retentionHeld what holds journal or database retention back, or null; read on the guard thread
     * @param nowMs         the clock for the hourly repeats and the walks
     */
    public DiskGuard(Path dataDir, Space space, long warnBelow, long stopBelow, long hysteresis,
                     Supplier<String> retentionHeld, LongSupplier nowMs, LongSupplier epochNanos,
                     DiskGuardTiming timing, Sink sink) {
        this.dataDir = dataDir;
        this.space = space;
        this.warnBelow = warnBelow;
        this.stopBelow = stopBelow;
        this.hysteresis = hysteresis;
        this.retentionHeld = retentionHeld;
        this.nowMs = nowMs;
        this.epochNanos = epochNanos;
        this.timing = timing;
        this.sink = sink;
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
        sample();
    }

    /** The guard of {@code dataDir}'s filesystem with the production timing. The directory must exist. */
    public static DiskGuard forDataDir(Path dataDir, long warnBelow, long stopBelow, long hysteresis,
                                       Supplier<String> retentionHeld, LongSupplier epochNanos) throws IOException {
        FileStore store = Files.getFileStore(dataDir);
        Space space = new Space() {
            @Override
            public long usableBytes() throws IOException {
                return store.getUsableSpace();
            }

            @Override
            public long totalBytes() throws IOException {
                return store.getTotalSpace();
            }
        };
        return new DiskGuard(dataDir, space, warnBelow, stopBelow, hysteresis, retentionHeld,
                System::currentTimeMillis, epochNanos, DiskGuardTiming.PRODUCTION, SLF4J);
    }

    /** What holds journal or database retention back, from their statuses; null if nothing does. */
    public static String retentionHeld(JournalStatus journal, DatabaseRetentionStatus database) {
        StringBuilder held = new StringBuilder();
        if (journal != null && JournalStatus.SKIPPED.equals(journal.outcome())) {
            held.append("journal retention skipped: ").append(journal.skipReason());
        }
        if (database != null && DatabaseRetentionStatus.SKIPPED.equals(database.outcome())) {
            if (held.length() > 0) held.append("; ");
            held.append("database retention skipped: ").append(database.skipReason());
        }
        return held.length() == 0 ? null : held.toString();
    }

    /** Where the refused-NEW count comes from: the gateway, which is built after the guard. */
    public void countRefusalsFrom(LongSupplier refused) {
        this.refusedCount = refused;
    }

    public void start() {
        started = true;
        thread.start();
    }

    @Override
    public boolean refuseNew() {
        return stopped;
    }

    /** What {@code /health} reports. Any thread: one volatile read. */
    public DiskStatus status() {
        return status;
    }

    @Override
    public void close() {
        closing = true;
        if (!started) return;
        LockSupport.unpark(thread);
        try {
            thread.join(timing.stopTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (!closing) {
            LockSupport.parkNanos(this, timing.sampleMs() * 1_000_000L);
            if (closing) return;
            sample();
        }
    }

    /** One sample; never throws. The guard thread, or a test, or the constructor. */
    void sample() {
        long now = nowMs.getAsLong();
        try {
            free = space.usableBytes();
            total = space.totalBytes();
            if (readFailed) {
                sink.log("INFO", "Disk guard reads the free space of " + dataDir + " again");
                readFailed = false;
            }
        } catch (IOException | RuntimeException e) {
            // On doubt nothing changes: the stop keeps its state, and the last figures stay.
            if (!readFailed) {
                sink.log("WARN", "Disk guard cannot read the free space of " + dataDir + "; keeping the last state: " + e);
                readFailed = true;
            }
            publish();
            return;
        }
        if (now - lastDataWalkMs >= timing.dataBytesMs() || lastDataWalkMs == Long.MIN_VALUE) {
            dataBytes = sizeOf(dataDir);
            lastDataWalkMs = now;
        }
        String held = retentionHeld == null ? null : retentionHeld.get();
        String heldText = held == null ? "" : "; retention is held: " + held;

        if (stopBelow > 0 && !stopped && free < stopBelow) {
            stopped = true;
            lastRepeatMs = now;
            sink.log("ERROR", "Disk space low: " + free + " bytes free on the filesystem of " + dataDir + ", below "
                    + stopBelow + " (DISK_STOP_FREE_BYTES). NEW orders are refused until " + (stopBelow + hysteresis)
                    + " bytes are free; cancels are accepted" + heldText);
        } else if (stopped && (stopBelow <= 0 || free >= stopBelow + hysteresis)) {
            stopped = false;
            sink.log("INFO", "Disk space recovered: " + free + " bytes free; NEW orders are accepted again ("
                    + refusedCount.getAsLong() + " refused since start)");
            low = false;   // re-evaluated below, so a LOW warning follows at once if still below the warn value
        } else if (stopped && now - lastRepeatMs >= timing.repeatMs()) {
            lastRepeatMs = now;
            sink.log("ERROR", "Disk space still low: " + free + " bytes free, below " + (stopBelow + hysteresis)
                    + "; NEW orders are refused (" + refusedCount.getAsLong() + " refused since start)" + heldText);
        }

        if (!stopped) {
            boolean below = free < warnBelow;
            if (below && (!low || now - lastRepeatMs >= timing.repeatMs())) {
                lastRepeatMs = now;
                sink.log("WARN", "Disk space low: " + free + " bytes free on the filesystem of " + dataDir
                        + ", below " + warnBelow + " (DISK_WARN_FREE_BYTES)" + heldText);
            } else if (!below && low) {
                sink.log("INFO", "Disk space back above " + warnBelow + " bytes: " + free + " bytes free");
            }
            low = below;
        }
        publish();
    }

    private void publish() {
        String state = stopped ? DiskStatus.STOP : (free >= 0 && free < warnBelow) ? DiskStatus.LOW : DiskStatus.OK;
        String held = retentionHeld == null ? null : retentionHeld.get();
        status = new DiskStatus(free, total, dataBytes, warnBelow, stopBelow, state, refusedCount.getAsLong(), held,
                epochNanos.getAsLong());
    }

    /** Total size of the regular files under {@code dir}; files that vanish during the walk are skipped. */
    static long sizeOf(Path dir) {
        long[] sum = new long[1];
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) sum[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return -1L;
        }
        return sum[0];
    }
}
