package event;

import com.lmax.disruptor.EventHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reusable snapshot-side test subscriber. Mirrors CapturingExecutionHandler.
 *
 * Slots are reused AND carry mutable arrays, so onEvent must deep-copy — and it
 * copies only the valid [0, levelCount) prefix, per BookSnapshotEvent's contract
 * that the counts are authoritative and array tails may be stale. A shallow copy
 * here would alias the live slot and read garbage after the ring wraps.
 */
public final class CapturingSnapshotHandler implements EventHandler<BookSnapshotEvent> {

    public record Observed(
            long[] bidPrices,
            long[] bidQtys,
            long[] askPrices,
            long[] askQtys,
            int bidLevelCount,
            int askLevelCount,
            long bestBid,
            long bestAsk,
            long lastSeq,
            long timestamp
    ) { }

    private final List<Observed> observed = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onEvent(BookSnapshotEvent event, long sequence, boolean endOfBatch) {
        observed.add(new Observed(
                Arrays.copyOf(event.bidPrices, event.bidLevelCount),
                Arrays.copyOf(event.bidQtys, event.bidLevelCount),
                Arrays.copyOf(event.askPrices, event.askLevelCount),
                Arrays.copyOf(event.askQtys, event.askLevelCount),
                event.bidLevelCount,
                event.askLevelCount,
                event.bestBid,
                event.bestAsk,
                event.lastSeq,
                event.timestamp
        ));
    }

    /** Busy-wait (bounded) until at least n snapshots land, then return a copy. */
    public List<Observed> awaitAtLeast(int n, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (observed) {
                if (observed.size() >= n) return new ArrayList<>(observed);
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (observed) {
            return new ArrayList<>(observed);
        }
    }

    public List<Observed> snapshot() {
        synchronized (observed) {
            return new ArrayList<>(observed);
        }
    }
}