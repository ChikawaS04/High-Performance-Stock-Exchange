package http;

/**
 * Volume-weighted average price in whole units of $0.0001. The exact notional and volume are
 * reported beside it, so the rounding loses nothing a client cannot recover. Rounding is half even
 * (to the even unit on an exact half), in long arithmetic: no floating point, no BigDecimal.
 */
final class Vwap {

    private Vwap() {
    }

    /**
     * @param notional sum of price times quantity, in units; never negative
     * @param volume   sum of quantity; positive
     * @return notional / volume rounded half even
     */
    static long halfEven(long notional, long volume) {
        if (notional < 0 || volume <= 0) {
            throw new IllegalArgumentException("notional " + notional + ", volume " + volume);
        }
        long quotient = notional / volume;
        long remainder = notional % volume;
        long rest = volume - remainder;          // no overflow: 0 <= remainder < volume
        if (remainder > rest || (remainder == rest && (quotient & 1L) == 1L)) {
            return quotient + 1;
        }
        return quotient;
    }
}
