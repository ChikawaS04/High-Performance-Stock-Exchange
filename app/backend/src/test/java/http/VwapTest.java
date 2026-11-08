package http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** VWAP in whole units, rounded half even, in long arithmetic (P15-12). */
class VwapTest {

    @Test
    void anExactAverageIsNotRounded() {
        assertEquals(1_502_500L, Vwap.halfEven(1_502_500L * 5, 5));
    }

    @Test
    void belowAndAboveTheHalfRoundDownAndUp() {
        assertEquals(10L, Vwap.halfEven(41L, 4));   // 10.25
        assertEquals(11L, Vwap.halfEven(43L, 4));   // 10.75
    }

    @Test
    void anExactHalfGoesToTheEvenUnit() {
        assertEquals(10L, Vwap.halfEven(21L, 2));   // 10.5 to 10
        assertEquals(12L, Vwap.halfEven(23L, 2));   // 11.5 to 12
    }

    /** A sub-penny midpoint fill (1,000,050) and a tick fill (1,000,100), one share each: average 1,000,075. */
    @Test
    void aSubPennyMidpointFillAveragesExactly() {
        assertEquals(1_000_075L, Vwap.halfEven(1_000_050L + 1_000_100L, 2));
    }

    /** Two midpoint fills at 1,000,050 and one at 1,000,000: 3,000,100 / 3 = 1,000,033.33 to 1,000,033. */
    @Test
    void aMidpointMixRoundsToTheNearestUnit() {
        assertEquals(1_000_033L, Vwap.halfEven(2 * 1_000_050L + 1_000_000L, 3));
    }

    @Test
    void valuesNearTheLongLimitDoNotOverflow() {
        assertEquals(1L, Vwap.halfEven(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE / 2 + 1, Vwap.halfEven(Long.MAX_VALUE, 2));   // odd quotient, exact half: up
    }

    @Test
    void noVolumeOrANegativeNotionalIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Vwap.halfEven(10L, 0));
        assertThrows(IllegalArgumentException.class, () -> Vwap.halfEven(-1L, 1));
    }
}
