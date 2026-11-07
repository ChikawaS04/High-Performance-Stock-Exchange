package reporter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The bucket boundary rule (pure logic). */
class CandlesTest {

    private static final long MINUTE = Candles.BUCKET_NANOS;
    private static final long START = 29_000_000L * MINUTE;

    @Test
    void theFirstNanoOfAMinuteOpensItsBucket() {
        assertEquals(START, Candles.bucketStart(START));
    }

    @Test
    void theLastNanoOfAMinuteStaysInIt() {
        assertEquals(START, Candles.bucketStart(START + MINUTE - 1));
    }

    @Test
    void aFillExactlyOnTheBoundaryIsInTheNewBucket() {
        assertEquals(START + MINUTE, Candles.bucketStart(START + MINUTE));
    }

    @Test
    void bucketsAreAlignedToTheEpoch() {
        assertEquals(0L, Candles.bucketStart(1L));
        assertEquals(0L, Candles.bucketStart(MINUTE - 1));
    }
}
