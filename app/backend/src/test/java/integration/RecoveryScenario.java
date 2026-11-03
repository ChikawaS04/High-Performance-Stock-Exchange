package integration;

import event.CapturingSnapshotHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared input and helpers for the recovery tests (P15-5): a FIX scenario that leaves a book
 * with every kind of resting state (multi-level queues, an iceberg mid-slice and below its peak,
 * plain partial fills, both peg pools at different times), a deterministic receipt clock, and
 * directory and comparison helpers.
 */
public final class RecoveryScenario {

    private RecoveryScenario() { }

    /**
     * Twenty commands. Ends with a two-sided lit book (iceberg 111 mid-slice and below its peak at
     * the best bid, iceberg 107 reloaded and mid-slice at the best ask), queues of several orders,
     * peg 116 in the bid pool, and partial fills.
     */
    public static List<byte[]> frames() {
        return List.of(
                FixMessages.limit(100, '2', "150.00", 100),          // 1  SELL rests
                FixMessages.limit(101, '2', "150.00", 60),           // 2  SELL queues behind 100
                FixMessages.peg(102, '1', 25, '1'),                  // 3  peg BUY rests inactive (no bid, no mid)
                FixMessages.peg(103, '2', 15, '1'),                  // 4  peg SELL rests inactive: both pools hold orders
                FixMessages.limit(104, '1', "149.00", 40),           // 5  BUY rests, creates a mid: pools cross
                FixMessages.limit(105, '1', "149.00", 30),           // 6  BUY queues behind 104
                FixMessages.limit(106, '1', "148.00", 20),           // 7  deeper bid
                FixMessages.iceberg(107, '2', "151.00", 1_000, 100), // 8  iceberg SELL
                FixMessages.limit(108, '2', "151.00", 35),           // 9  SELL behind the iceberg
                FixMessages.limit(109, '1', "150.00", 30),           // 10 partial: 100 left with 70
                FixMessages.limit(110, '1', "151.00", 270),          // 11 sweeps 150.00, iceberg tip, reload, 108, new tip
                FixMessages.iceberg(111, '1', "149.50", 120, 100),   // 12 iceberg BUY at the best bid
                FixMessages.limit(112, '2', "149.50", 100),          // 13 crosses the mid: peg 102 first, then 90 of 111's tip
                FixMessages.limit(113, '1', "149.00", 5, '3'),       // 14 IOC, no cross: expires
                FixMessages.limit(114, '1', "160.00", 9_999, '4'),   // 15 FOK, not enough: expires
                FixMessages.cancel(115, 106),                        // 16 cancel the 148.00 bid
                FixMessages.limit(104, '1', "147.00", 1),            // 17 duplicate of resting 104: rejected
                FixMessages.peg(116, '1', 12, '1'),                  // 18 peg BUY rests (no peg asks left)
                FixMessages.limit(117, '2', "152.00", 44),           // 19 SELL rests deeper
                FixMessages.limit(118, '1', "149.00", 15));          // 20 BUY queues third at 149.00
    }

    /** Inbound commands in a scenario run: the session open (P15-8), then every frame. */
    public static int commands() {
        return (int) JournaledVenue.OPEN_SEQ + frames().size();
    }

    /** A receipt clock that counts up from {@code start}, so two runs of the same input journal the same timestamps. */
    public static LongSupplier countingClock(long start) {
        long[] next = {start};
        return () -> next[0]++;
    }

    /** Copies a directory tree. */
    public static void copyTree(Path from, Path to) {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes a directory tree if present. */
    public static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(paths.toList());
            java.util.Collections.reverse(all);
            for (Path p : all) Files.delete(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every field of a BOOK observation, arrays by content up to their level counts. */
    public static String book(CapturingSnapshotHandler.Observed b) {
        return "bids=" + Arrays.toString(Arrays.copyOf(b.bidPrices(), b.bidLevelCount()))
                + "x" + Arrays.toString(Arrays.copyOf(b.bidQtys(), b.bidLevelCount()))
                + " asks=" + Arrays.toString(Arrays.copyOf(b.askPrices(), b.askLevelCount()))
                + "x" + Arrays.toString(Arrays.copyOf(b.askQtys(), b.askLevelCount()))
                + " best=" + b.bestBid() + "/" + b.bestAsk() + " lastSeq=" + b.lastSeq() + " ts=" + b.timestamp();
    }

    public static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
