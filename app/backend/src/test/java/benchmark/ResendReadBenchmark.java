package benchmark;

import event.ExecutionEvent;
import event.ExecutionHistory;
import gateway.FixConstants;
import gateway.OrderGateway;
import integration.JournaledVenue;
import journal.JournaledPipeline;
import model.Side;
import net.JsonToFix;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P16-12 (P16-9 finding 2): the cost of a reconnecting bot's RESEND once the outbound recording holds a
 * large retained window. Every RESEND reads the outbound recording from its start (the P15-6 linear read),
 * so a bot asking only from its last seq + 1 still reads everything; this measures that read.
 *
 * <p>One journaled venue on production journal defaults is filled with rest and cross pairs, unpaced,
 * until the outbound recording reaches each target size; at each size the history is read
 * {@code resend.reads} times from the last seq + 1 (the bot case: nothing is sent, everything is read),
 * through the venue's own {@link ExecutionHistory} on the Archive's replay. The page cache is warm (the
 * data was just written), so these are warm-cache figures.
 *
 * <p>Not run by a plain {@code mvn test}. From {@code app/backend}, on JDK 21:
 * <pre>{@code
 *   mvn -q test -Dtest=ResendReadBenchmark -Dresend.read=true -Dresend.mib=100,400
 * }</pre>
 * Properties: {@code resend.mib} (target sizes in MiB, ascending, default 100,400), {@code resend.reads}
 * (default 5), {@code e2e.idle} (engine idle, default busy-spin), {@code bench.results} and
 * {@code resend.tag} for the result file, and the {@link BenchSupport} settings.
 */
final class ResendReadBenchmark {

    private static final long PRICE_UNITS = 10_000_000L;
    private static final long QTY = 100L;
    private static final int PAIRS = 4_096;
    private static final long FIRST_ID = 1_000_000L;

    @Test
    void fullWindowResendRead() throws Exception {
        assumeTrue(Boolean.getBoolean("resend.read"), "Set -Dresend.read=true to run the RESEND read benchmark");
        final long[] targetsMiB = Arrays.stream(System.getProperty("resend.mib", "100,400").split(","))
                .map(String::trim).mapToLong(Long::parseLong).sorted().toArray();
        final int reads = Integer.getInteger("resend.reads", 5);
        final String idle = System.getProperty("e2e.idle", "busy-spin");

        final byte[][] rest = new byte[PAIRS][];
        final byte[][] cross = new byte[PAIRS][];
        for (int i = 0; i < PAIRS; i++) {
            rest[i] = JsonToFix.newOrderSingle(FIRST_ID + 2L * i, Side.BUY, PRICE_UNITS, QTY, FixConstants.SYMBOL_DISPLAY);
            cross[i] = JsonToFix.newOrderSingle(FIRST_ID + 2L * i + 1, Side.SELL, PRICE_UNITS, QTY,
                    FixConstants.SYMBOL_DISPLAY);
        }

        StringBuilder out = new StringBuilder(1024);
        out.append("=== P16-12 full-window RESEND read (linear read of the outbound recording) ===\n");
        out.append("config     : idle=").append(idle).append(' ').append(BenchSupport.settingsLabel()).append('\n');
        out.append("jvm        : ").append(System.getProperty("java.vm.version")).append('\n');

        Path root = BenchSupport.newRoot("resend");
        JournaledVenue venue = JournaledVenue.create(BenchSupport.productionConfig(root, idle), JournaledPipeline.Seams.NONE);
        try {
            venue.outbound().handleEventsWith((event, sequence, endOfBatch) -> { });
            venue.snapshots().handleEventsWith(new BenchSupport.NoOpSnapshots());
            OrderGateway gateway = venue.gateway();
            venue.startAndOpen(gateway);
            ExecutionHistory history = venue.pipeline().outboundHistory();
            long outboundId = venue.pipeline().outboundRecording().recordingId();
            long k = 0;
            for (long targetMiB : targetsMiB) {
                long targetBytes = targetMiB << 20;
                long fillStart = System.nanoTime();
                while (venue.journal().archive().getRecordingPosition(outboundId) < targetBytes) {
                    for (int i = 0; i < 1_000; i++, k++) {
                        int pair = (int) ((k >>> 1) % PAIRS);
                        gateway.onFrame((k & 1) == 0 ? rest[pair] : cross[pair]);
                    }
                }
                long published = settle(venue, outboundId, history);
                long recorded = venue.journal().archive().getRecordingPosition(outboundId);
                out.append(String.format("size       : %,d MiB target; recording %,d bytes, %,d events (filled in %.1f s)%n",
                        targetMiB, recorded, published, (System.nanoTime() - fillStart) / 1e9));
                long[] millis = new long[reads];
                for (int r = 0; r < reads; r++) {
                    Counting visitor = new Counting();
                    long t0 = System.nanoTime();
                    ExecutionHistory.Result result = history.replay(published + 1, published, visitor);
                    long ns = System.nanoTime() - t0;
                    millis[r] = ns / 1_000_000L;
                    if (visitor.sent != 0 || result.count() != 0) {
                        throw new AssertionError("a catch-up from the tail sent " + visitor.sent + " events");
                    }
                    out.append(String.format("  read %d   : %,d ms, %,d events, %,d bytes, %.0f MiB/s%n", r + 1, millis[r],
                            result.scanned(), result.bytesRead(), (result.bytesRead() / 1048576.0) / (ns / 1e9)));
                }
                long[] sorted = millis.clone();
                Arrays.sort(sorted);
                out.append(String.format("  summary  : min %,d ms, median %,d ms, max %,d ms%n", sorted[0],
                        sorted[sorted.length / 2], sorted[sorted.length - 1]));
            }
            out.append("archive    : ").append(BenchSupport.archiveStats(venue.config().archiveDir())).append('\n');
        } finally {
            venue.close();
            BenchSupport.deleteTree(root);
        }
        String text = out.toString();
        System.out.print(text);
        Path dir = Path.of(System.getProperty("bench.results", "../../bench-results/p16-12"));
        try {
            Files.createDirectories(dir);
            Path p = dir.resolve("resend-" + System.getProperty("resend.tag", "run") + ".txt");
            Files.writeString(p, text);
            System.out.println("(written to " + p.toAbsolutePath().normalize() + ")");
        } catch (IOException e) {
            System.out.println("(could not write results file: " + e.getMessage() + ")");
        }
    }

    /** Waits until the outbound recording stops growing and holds every published event; returns that seq. */
    private static long settle(JournaledVenue venue, long outboundId, ExecutionHistory history) {
        long deadline = System.currentTimeMillis() + BenchSupport.TIMEOUT_MS;
        long last = -1;
        int stable = 0;
        while (stable < 20) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("the outbound recording did not settle");
            long position = venue.journal().archive().getRecordingPosition(outboundId);
            stable = position == last ? stable + 1 : 0;
            last = position;
            LockSupport.parkNanos(10_000_000L);
        }
        return history.publishedSeq();
    }

    private static final class Counting implements ExecutionHistory.Visitor {
        long sent;

        @Override
        public boolean onEvent(ExecutionEvent event) {
            sent++;
            return true;
        }
    }
}
