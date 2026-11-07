package clearing;

import model.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The netting rule, without a database. */
class NettingTest {

    private static final long HOUSE = 1L;
    private static final long A = 2L;
    private static final long B = 3L;

    @Test
    void anAggressiveBuyerBuysFromThePassiveSeller() {
        Netting n = new Netting();
        n.add(1L, "ASML", 1_500_000L, 10L, Side.BUY, A, B);
        assertEquals(List.of(
                new Obligation(A, "ASML", 10L, 0L, 10L, 15_000_000L),
                new Obligation(B, "ASML", 0L, 10L, -10L, -15_000_000L)), n.obligations());
    }

    @Test
    void anAggressiveSellerSellsToThePassiveBuyer() {
        Netting n = new Netting();
        n.add(1L, "ASML", 1_500_000L, 4L, Side.SELL, A, B);
        assertEquals(List.of(
                new Obligation(A, "ASML", 0L, 4L, -4L, -6_000_000L),
                new Obligation(B, "ASML", 4L, 0L, 4L, 6_000_000L)), n.obligations());
    }

    @Test
    void aSelfTradeNetsToZeroQuantityAndZeroCash() {
        Netting n = new Netting();
        n.add(1L, "ASML", 1_500_000L, 7L, Side.BUY, HOUSE, HOUSE);
        n.add(2L, "ASML", 1_500_100L, 3L, Side.SELL, HOUSE, HOUSE);
        assertEquals(List.of(new Obligation(HOUSE, "ASML", 10L, 10L, 0L, 0L)), n.obligations());
        assertEquals(2L, n.fills());
        assertEquals(10L, n.volume());
    }

    @Test
    void aSubPennyMidpointPriceIsExactInUnits() {
        Netting n = new Netting();
        n.add(1L, "ASML", 1_500_050L, 3L, Side.BUY, A, B);
        assertEquals(4_500_150L, n.obligations().get(0).cashObligation());
    }

    @Test
    void manyParticipantsSumToZeroAndAreOrderedByParticipantThenSymbol() {
        Netting n = new Netting();
        n.add(1L, "ASML", 1_000_000L, 5L, Side.BUY, B, A);
        n.add(2L, "ASML", 1_000_100L, 2L, Side.SELL, B, HOUSE);
        n.add(3L, "AAPL", 2_000_000L, 1L, Side.BUY, A, HOUSE);
        List<Obligation> o = n.obligations();
        assertEquals(List.of(
                new Obligation(HOUSE, "AAPL", 0L, 1L, -1L, -2_000_000L),
                new Obligation(HOUSE, "ASML", 2L, 0L, 2L, 2_000_200L),
                new Obligation(A, "AAPL", 1L, 0L, 1L, 2_000_000L),
                new Obligation(A, "ASML", 0L, 5L, -5L, -5_000_000L),
                new Obligation(B, "ASML", 5L, 2L, 3L, 2_999_800L)), o);
        assertEquals(0L, o.stream().mapToLong(Obligation::cashObligation).sum());
        assertEquals(0L, o.stream().mapToLong(Obligation::netQuantity).sum());
    }

    @Test
    void noFillsGiveNoObligations() {
        Netting n = new Netting();
        assertTrue(n.obligations().isEmpty());
        assertEquals(0L, n.fills());
        assertEquals(0L, n.volume());
    }

    @Test
    void aProductThatOverflowsIsAnInvariantFailureNotAWrap() {
        Netting n = new Netting();
        ClearingInvariantException e = assertThrows(ClearingInvariantException.class,
                () -> n.add(9L, "ASML", Long.MAX_VALUE / 2, 3L, Side.BUY, A, B));
        assertTrue(e.getMessage().contains("outbound seq 9"), e.getMessage());
    }

    @Test
    void aSumThatOverflowsIsAnInvariantFailureNotAWrap() {
        Netting n = new Netting();
        n.add(1L, "ASML", Long.MAX_VALUE / 4, 3L, Side.BUY, A, B);
        assertThrows(ClearingInvariantException.class, () -> n.add(2L, "ASML", Long.MAX_VALUE / 4, 3L, Side.BUY, A, B));
    }

    @Test
    void aNonPositivePriceOrQuantityIsAnInvariantFailure() {
        Netting n = new Netting();
        assertThrows(ClearingInvariantException.class, () -> n.add(1L, "ASML", 0L, 1L, Side.BUY, A, B));
        assertThrows(ClearingInvariantException.class, () -> n.add(2L, "ASML", 100L, 0L, Side.BUY, A, B));
    }
}
