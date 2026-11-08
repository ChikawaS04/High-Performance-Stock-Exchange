package http;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates the stored one-minute candles of one symbol and business date into longer intervals.
 * Buckets follow the Reporter's rule (P15-10): aligned to the epoch and half open, so an
 * {@code n}-minute bucket starts at {@code ts - floorMod(ts, n minutes)}. Open comes from the first
 * minute in the bucket and close from the last; high, low, volume and trade count cover them all.
 * Empty buckets are not produced. The input is already one business date's rows, so a bucket that
 * spans midnight holds only that date's minutes.
 */
final class CandleIntervals {

    static final long MINUTE_NANOS = 60_000_000_000L;

    /** One stored or aggregated candle; prices in units, timestamps in epoch nanos. */
    record Candle(long bucketStartTs, long open, long high, long low, long close, long volume, long tradeCount) {
    }

    private CandleIntervals() {
    }

    /**
     * @param minutes one-minute candles in ascending {@code bucketStartTs}
     * @param intervalMinutes the bucket length
     * @throws ArithmeticException if a bucket's volume or trade count overflows a long
     */
    static List<Candle> aggregate(List<Candle> minutes, int intervalMinutes) {
        long interval = intervalMinutes * MINUTE_NANOS;
        List<Candle> out = new ArrayList<>();
        long start = 0;
        long open = 0, high = 0, low = 0, close = 0, volume = 0, count = 0;
        boolean any = false;
        long previous = Long.MIN_VALUE;
        for (Candle m : minutes) {
            if (m.bucketStartTs() <= previous) {
                throw new IllegalArgumentException("one-minute candles out of order at " + m.bucketStartTs());
            }
            previous = m.bucketStartTs();
            long bucket = m.bucketStartTs() - Math.floorMod(m.bucketStartTs(), interval);
            if (any && bucket != start) {
                out.add(new Candle(start, open, high, low, close, volume, count));
                any = false;
            }
            if (!any) {
                start = bucket;
                open = m.open();
                high = m.high();
                low = m.low();
                volume = 0;
                count = 0;
                any = true;
            }
            high = Math.max(high, m.high());
            low = Math.min(low, m.low());
            close = m.close();
            volume = Math.addExact(volume, m.volume());
            count = Math.addExact(count, m.tradeCount());
        }
        if (any) {
            out.add(new Candle(start, open, high, low, close, volume, count));
        }
        return out;
    }
}
