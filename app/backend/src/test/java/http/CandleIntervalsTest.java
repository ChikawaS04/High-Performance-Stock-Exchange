package http;

import http.CandleIntervals.Candle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static http.CandleIntervals.MINUTE_NANOS;
import static org.junit.jupiter.api.Assertions.*;

/** Aggregating stored one-minute candles into longer intervals (P15-12). */
class CandleIntervalsTest {

    /** 2026-11-06 00:00:00 UTC in epoch nanos: a multiple of every interval. */
    private static final long T0 = 1_793_923_200L * 1_000_000_000L;

    private static Candle minute(int index, long open, long high, long low, long close, long volume, long count) {
        return new Candle(T0 + index * MINUTE_NANOS, open, high, low, close, volume, count);
    }

    @Test
    void oneMinuteIsTheStoredCandles() {
        List<Candle> minutes = List.of(minute(0, 100, 110, 90, 105, 3, 1), minute(2, 105, 105, 100, 101, 2, 2));
        assertEquals(minutes, CandleIntervals.aggregate(minutes, 1));
    }

    @Test
    void openFromTheFirstMinuteCloseFromTheLastAndTheRestOverAll() {
        List<Candle> minutes = List.of(
                minute(0, 100, 110, 95, 105, 3, 1),
                minute(1, 105, 120, 104, 118, 4, 2),
                minute(4, 118, 119, 80, 90, 5, 3));
        assertEquals(List.of(new Candle(T0, 100, 120, 80, 90, 12, 6)), CandleIntervals.aggregate(minutes, 5));
    }

    @Test
    void bucketsAreEpochAlignedAndHalfOpen() {
        // Minutes 4 and 5: the 5-minute boundary sits between them, so they never share a bucket.
        List<Candle> minutes = List.of(minute(4, 100, 100, 100, 100, 1, 1), minute(5, 200, 200, 200, 200, 1, 1));
        assertEquals(List.of(new Candle(T0, 100, 100, 100, 100, 1, 1),
                new Candle(T0 + 5 * MINUTE_NANOS, 200, 200, 200, 200, 1, 1)), CandleIntervals.aggregate(minutes, 5));
    }

    @Test
    void emptyBucketsAreNotProduced() {
        List<Candle> minutes = List.of(minute(0, 1, 1, 1, 1, 1, 1), minute(45, 2, 2, 2, 2, 1, 1));
        List<Candle> out = CandleIntervals.aggregate(minutes, 15);
        assertEquals(2, out.size());
        assertEquals(T0 + 45 * MINUTE_NANOS, out.get(1).bucketStartTs());
    }

    @Test
    void anHourStartsOnTheHourWhateverTheFirstMinute() {
        List<Candle> minutes = List.of(minute(61, 7, 8, 6, 7, 1, 1), minute(119, 7, 9, 7, 9, 2, 1));
        assertEquals(List.of(new Candle(T0 + 60 * MINUTE_NANOS, 7, 9, 6, 9, 3, 2)), CandleIntervals.aggregate(minutes, 60));
    }

    @Test
    void noMinutesNoCandles() {
        assertEquals(List.of(), CandleIntervals.aggregate(List.of(), 30));
    }

    @Test
    void anOverflowingVolumeIsRefusedNotWrapped() {
        List<Candle> minutes = List.of(minute(0, 1, 1, 1, 1, Long.MAX_VALUE, 1), minute(1, 1, 1, 1, 1, 1, 1));
        assertThrows(ArithmeticException.class, () -> CandleIntervals.aggregate(minutes, 5));
    }

    @Test
    void minutesOutOfOrderAreRefused() {
        List<Candle> minutes = List.of(minute(1, 1, 1, 1, 1, 1, 1), minute(0, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> CandleIntervals.aggregate(minutes, 5));
    }
}
