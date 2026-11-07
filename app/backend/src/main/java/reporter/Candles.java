package reporter;

/**
 * The one-minute candle bucket rule (SRS 3.11). Buckets are aligned to the epoch and half open,
 * {@code [start, start + 60 s)}: a fill stamped exactly on a minute boundary opens the new bucket.
 * The input is the event's timestamp, the journaled receipt stamp of its command, so a replay
 * produces the same buckets.
 */
public final class Candles {

    public static final long BUCKET_NANOS = 60_000_000_000L;

    private Candles() {
    }

    /** The start of the bucket holding {@code timestampNanos}. */
    public static long bucketStart(long timestampNanos) {
        return timestampNanos - Math.floorMod(timestampNanos, BUCKET_NANOS);
    }
}
