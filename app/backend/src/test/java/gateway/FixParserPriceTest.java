package gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixParserPriceTest {

    private static final long REJECT = -1L;

    /**
     * Embeds `price` inside a wider buffer with surrounding bytes, then parses
     * only the [offset, offset+len) window. Proves parsePrice honors bounds
     * and never reads the padding.
     */
    private static long parse(String price) {
        byte[] priceBytes = price.getBytes();
        byte[] buf = new byte[priceBytes.length + 6];
        // leading junk
        buf[0] = 'X'; buf[1] = 'X'; buf[2] = '9';
        System.arraycopy(priceBytes, 0, buf, 3, priceBytes.length);
        // trailing junk (incl. a SOH-like byte the parser must not consume)
        buf[3 + priceBytes.length]     = 0x01;
        buf[3 + priceBytes.length + 1] = '4';
        buf[3 + priceBytes.length + 2] = '4';
        return FixParser.parsePrice(buf, 3, 3 + priceBytes.length);
    }

    @Nested
    @DisplayName("valid prices")
    class Valid {

        @Test
        @DisplayName("integer only -> scaled to units")
        void integerOnly() {
            assertEquals(1_500_000L, parse("150"));
        }

        @Test
        @DisplayName("one decimal digit -> *1000")
        void oneDecimal() {
            assertEquals(1_502_000L, parse("150.2"));
        }

        @Test
        @DisplayName("two decimal digits -> *100")
        void twoDecimals() {
            assertEquals(1_502_500L, parse("150.25"));
        }

        @Test
        @DisplayName("one-cent price scales to a single tick")
        void oneCent() {
            assertEquals(100L, parse("0.01"));
        }

        @Test
        @DisplayName("trailing-zero fraction is a valid one-digit case")
        void oneDecimalZero() {
            assertEquals(150_000L, parse("15.0"));   // scaling-path proof
        }

        @Test
        @DisplayName("zero integer part with positive fraction accepts")
        void zeroIntegerPositiveFraction() {
            assertEquals(5_000L, parse("0.50"));     // > 0 check must not over-reject
        }

        @Test
        @DisplayName("leading zeros in integer part tolerated")
        void leadingZeros() {
            assertEquals(75_000L, parse("007.50"));
        }

        @Test
        @DisplayName("high-value price (ASML territory) parses cleanly")
        void highValue() {
            assertEquals(9_000_000L, parse("900"));
        }
    }

    @Nested
    @DisplayName("rejected prices")
    class Rejected {

        @Test
        @DisplayName("more than two decimals rejects")
        void tooManyDecimals() {
            assertEquals(REJECT, parse("150.256"));
        }

        @Test
        @DisplayName("plain zero rejects (policy applied in parsePrice)")
        void zero() {
            assertEquals(REJECT, parse("0"));
        }

        @Test
        @DisplayName("zero with zero fraction rejects")
        void zeroPointZeroZero() {
            assertEquals(REJECT, parse("0.00"));
        }

        @Test
        @DisplayName("negative rejects (minus is not a digit)")
        void negative() {
            assertEquals(REJECT, parse("-5"));
        }

        @Test
        @DisplayName("bare dot rejects")
        void bareDot() {
            assertEquals(REJECT, parse("."));
        }

        @Test
        @DisplayName("empty integer part rejects")
        void emptyIntegerPart() {
            assertEquals(REJECT, parse(".25"));
        }

        @Test
        @DisplayName("trailing dot with no fraction rejects")
        void trailingDot() {
            assertEquals(REJECT, parse("150."));
        }

        @Test
        @DisplayName("trailing junk after digits rejects")
        void trailingJunk() {
            assertEquals(REJECT, parse("150.2x"));
        }

        @Test
        @DisplayName("multiple dots reject")
        void multipleDots() {
            assertEquals(REJECT, parse("1.2.3"));
        }

        @Test
        @DisplayName("non-digit integer part rejects")
        void nonDigitInteger() {
            assertEquals(REJECT, parse("1a0.25"));
        }

        @Test
        @DisplayName("empty range rejects")
        void emptyRange() {
            byte[] buf = "XXXX".getBytes();
            assertEquals(REJECT, FixParser.parsePrice(buf, 2, 2));  // start == end
        }
    }
}