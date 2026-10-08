package market;

import com.lmax.disruptor.EventHandler;
import event.BookSnapshotEvent;

/**
 * Market data subscriber (SRS §3.5): an independent consumer on the snapshot ring that
 * derives best bid/ask, midpoint, and spread from each {@link BookSnapshotEvent}.
 *
 * <p>Rebuilt for Phase 4. The Phase 1 form pulled best bid/ask from a {@code BookView} on
 * demand; the engine is now the single source of truth (guide decision 3) and hands out
 * bounded depth snapshots, so this is a push consumer instead. Metrics are derived <b>inside
 * {@code onEvent}</b> — the snapshot slot is reused and must not be retained past the call.
 *
 * <p><b>Threading.</b> Written by the snapshot Disruptor's consumer thread; read by any
 * thread. Each snapshot swaps a single immutable {@link Quote} through one {@code volatile}
 * store, so a reader always observes a self-consistent tuple (never bestBid from one snapshot
 * mixed with spread from the next). {@link #getQuote()} is the coherent multi-field read;
 * the individual getters are convenience views over the same volatile load.
 *
 * <p><b>Units.</b> Prices are long units of $0.0001 (SRS §4). Because every lit price is on the
 * one-cent tick (a multiple of 100 units), the sum of best bid and best ask is a multiple of 100,
 * so the midpoint is always an exact multiple of 50 units and is stored exactly. Empty sides carry
 * the {@code -1L} sentinel.
 */
public final class MarketDataService implements EventHandler<BookSnapshotEvent> {

    /** Consistent snapshot of the derived metrics. All fields carry -1L when unavailable. */
    public record Quote(
            long bestBid,     // units of $0.0001; -1L if bid side empty
            long bestAsk,     // units of $0.0001; -1L if ask side empty
            long spread,      // units (bestAsk - bestBid); -1L if either side empty
            long midpoint,    // exact midpoint (bestBid + bestAsk) / 2; -1L if either side empty
            long timestamp    // epoch nanos of the snapshot this quote was derived from
    ) {
        static final Quote EMPTY = new Quote(-1L, -1L, -1L, -1L, -1L);
    }

    private volatile Quote quote = Quote.EMPTY;

    @Override
    public void onEvent(BookSnapshotEvent event, long sequence, boolean endOfBatch) {
        long bestBid = event.bestBid;
        long bestAsk = event.bestAsk;

        long spread;
        long midpoint;
        if (bestBid == -1L || bestAsk == -1L) {
            spread = -1L;
            midpoint = -1L;
        } else {
            spread = bestAsk - bestBid;             // resting book is non-crossed => >= 0
            midpoint = (bestBid + bestAsk) / 2;     // exact: the sum is a multiple of 100 units
        }

        // One volatile publish of a coherent tuple. Nothing beyond this line touches the slot.
        quote = new Quote(bestBid, bestAsk, spread, midpoint, event.timestamp);
    }

    // ---- reads: any thread; one volatile load per call ----

    /** The full, self-consistent latest quote. Prefer this for multi-field reads. */
    public Quote getQuote() {
        return quote;
    }

    public long getBestBid() {
        return quote.bestBid();
    }

    public long getBestAsk() {
        return quote.bestAsk();
    }

    /** Spread in units of $0.0001, or -1L if either side is empty. */
    public long getSpread() {
        return quote.spread();
    }

    /**
     * Exact midpoint in units of $0.0001, or -1L if either side is empty. Because both lit
     * prices are on the one-cent tick, the midpoint is always an exact multiple of 50 units
     * and is never rounded.
     */
    public long getMidpoint() {
        return quote.midpoint();
    }
}