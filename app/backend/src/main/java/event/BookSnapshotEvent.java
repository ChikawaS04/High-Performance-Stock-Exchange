package event;

/**
 * Mutable, reused carrier for a bounded top-N order book depth snapshot (SRS §3.5 depth
 * feed). Framework-free — no com.lmax, no io.netty. The engine writes into a pre-allocated
 * slot on its own thread (MatchingEngine.snapshotInto); a snapshot Disruptor (P4-2) carries
 * it to the WebSocket publisher and market data service.
 *
 * <p><b>Slot-reuse contract.</b> Only indices {@code [0, bidLevelCount)} and
 * {@code [0, askLevelCount)} are valid after a fill. Entries beyond the level count are
 * stale from a previous snapshot and MUST NOT be read. The counts — not the array
 * contents — are authoritative. This is what keeps the carrier zero-allocation across reuse:
 * snapshotInto never has to clear the array tails.
 *
 * <p>Prices are long units of $0.0001 (SRS §4). Empty sides carry the {@code -1L} sentinel in
 * {@code bestBid}/{@code bestAsk} and a level count of 0.
 */
public final class BookSnapshotEvent {

    /**
     * Maximum depth levels retained per side. Sizes every array below and is the clamp in
     * {@link engine.MatchingEngine#snapshotInto}.
     *
     * <p>P9-1 raised this from 10 to 20. The 10-level prefix (P4-6) capped the wire below the
     * depth the UI can show: the ladder's Depth selector offers up to 14 levels per side, so a
     * ≤10 prefix made 14 (and even 10, on a deeper book) unreachable, and left the themed
     * depth-pane scrollbar with nothing to scroll. The value stays a fixed bound so the carrier
     * remains zero-allocation across reuse (fixed-size arrays, no per-snapshot growth); it must
     * stay strictly greater than the largest Depth option (14) for the scrolling viewport to
     * engage. 20 longs × 4 arrays is ~640 B, negligible at demo volume.
     */
    public static final int MAX_DEPTH_LEVELS = 20;

    /** Bid side, best (highest) first. Valid range: [0, bidLevelCount). */
    public final long[] bidPrices = new long[MAX_DEPTH_LEVELS];
    public final long[] bidQtys   = new long[MAX_DEPTH_LEVELS];

    /** Ask side, best (lowest) first. Valid range: [0, askLevelCount). */
    public final long[] askPrices = new long[MAX_DEPTH_LEVELS];
    public final long[] askQtys   = new long[MAX_DEPTH_LEVELS];

    /** Number of populated levels per side (0..MAX_DEPTH_LEVELS). Authoritative. */
    public int bidLevelCount;
    public int askLevelCount;

    /** Top of book in long units of $0.0001; -1L when the side is empty. Mirror level [0] when present. */
    public long bestBid;
    public long bestAsk;

    /** Snapshot time, epoch nanos (System.nanoTime), stamped when the book was read. */
    public long timestamp;
}