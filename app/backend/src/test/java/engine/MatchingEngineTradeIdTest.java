package engine;

import engine.CapturingExecutionListener.Kind;
import engine.CapturingExecutionListener.Observed;
import model.Order;
import model.Side;
import model.TimeInForce;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P15-2 D2 and D4 at the engine seam: the engine owns its trade id counter (no static), the
 * counter restores for P15-5, and every lit fill names the aggressor's side and both
 * participants. Participant ids are distinct per order so a transposition fails.
 */
class MatchingEngineTradeIdTest {

    private static final long P100 = 1_000_000L;   // $100.00
    private static final long P101 = 1_010_000L;   // $101.00

    private static long tsSeq = 1L;

    private static Order limit(long id, Side side, int qty, long price, long participantId) {
        return new Order(id, tsSeq++, side, qty, price, participantId, TimeInForce.GTC);
    }

    // =============================================================== D2: trade id counter

    @Test
    void freshEngine_firstTradeIdIsOne_andIdsAreConsecutive() {
        MatchingEngine engine = new MatchingEngine();
        engine.addOrder(limit(1, Side.SELL, 10, P100, 7L));
        engine.addOrder(limit(2, Side.SELL, 10, P101, 7L));
        CapturingExecutionListener cap = new CapturingExecutionListener();
        engine.setExecutionListener(cap);

        engine.addOrder(limit(3, Side.BUY, 20, P101, 9L));   // walks both levels

        assertEquals(2, cap.count(Kind.FILL));
        assertEquals(1L, cap.get(0).tradeId());
        assertEquals(2L, cap.get(1).tradeId());
        assertEquals(3L, engine.getNextTradeId());
    }

    @Test
    void restoredCounter_nextFillsContinueFromTheSetValue() {
        MatchingEngine engine = new MatchingEngine();
        engine.restoreNextTradeId(1_000L);
        engine.addOrder(limit(1, Side.BUY, 10, P100, 7L));
        engine.addOrder(limit(2, Side.BUY, 10, P100, 7L));
        CapturingExecutionListener cap = new CapturingExecutionListener();
        engine.setExecutionListener(cap);

        engine.addOrder(limit(3, Side.SELL, 20, P100, 9L));

        assertEquals(1_000L, cap.get(0).tradeId());
        assertEquals(1_001L, cap.get(1).tradeId());
        assertEquals(1_002L, engine.getNextTradeId());
    }

    @Test
    void restoreBelowOne_isRejected() {
        MatchingEngine engine = new MatchingEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.restoreNextTradeId(0L));
        assertThrows(IllegalArgumentException.class, () -> engine.restoreNextTradeId(-1L));
        assertEquals(1L, engine.getNextTradeId());
    }

    @Test
    void twoEngines_doNotShareACounter() {
        MatchingEngine a = new MatchingEngine();
        a.addOrder(limit(1, Side.SELL, 10, P100, 7L));
        a.addOrder(limit(2, Side.BUY, 10, P100, 9L));
        a.addOrder(limit(3, Side.SELL, 10, P100, 7L));
        a.addOrder(limit(4, Side.BUY, 10, P100, 9L));
        assertEquals(3L, a.getNextTradeId());

        MatchingEngine b = new MatchingEngine();
        CapturingExecutionListener cap = new CapturingExecutionListener();
        b.setExecutionListener(cap);
        b.addOrder(limit(1, Side.SELL, 10, P100, 7L));
        b.addOrder(limit(2, Side.BUY, 10, P100, 9L));

        Observed fill = cap.all().stream().filter(o -> o.kind() == Kind.FILL).findFirst().orElseThrow();
        assertEquals(1L, fill.tradeId());   // engine b starts at 1 whatever engine a assigned
    }

    // =============================================================== D4: lit fill attribution

    @Test
    void litBuyAggressor_fillNamesBuyAndEachPassiveParticipant() {
        MatchingEngine engine = new MatchingEngine();
        engine.addOrder(limit(1, Side.SELL, 10, P100, 7L));
        engine.addOrder(limit(2, Side.SELL, 10, P101, 8L));
        CapturingExecutionListener cap = new CapturingExecutionListener();
        engine.setExecutionListener(cap);

        engine.addOrder(limit(3, Side.BUY, 20, P101, 9L));

        Observed first = cap.get(0);
        assertEquals(Side.BUY, first.aggressorSide());
        assertEquals(9L, first.aggressorParticipantId());
        assertEquals(7L, first.passiveParticipantId());

        Observed second = cap.get(1);
        assertEquals(Side.BUY, second.aggressorSide());
        assertEquals(9L, second.aggressorParticipantId());
        assertEquals(8L, second.passiveParticipantId());
    }

    @Test
    void litSellAggressor_fillNamesSellAndBothParticipants() {
        MatchingEngine engine = new MatchingEngine();
        engine.addOrder(limit(1, Side.BUY, 10, P100, 7L));
        CapturingExecutionListener cap = new CapturingExecutionListener();
        engine.setExecutionListener(cap);

        engine.addOrder(limit(2, Side.SELL, 15, P100, 9L));   // fills 10, rests 5

        Observed fill = cap.get(0);
        assertEquals(Kind.FILL, fill.kind());
        assertEquals(Side.SELL, fill.aggressorSide());
        assertEquals(9L, fill.aggressorParticipantId());
        assertEquals(7L, fill.passiveParticipantId());

        Observed accepted = cap.get(1);
        assertEquals(Kind.ACCEPTED, accepted.kind());
        assertEquals(Side.SELL, accepted.aggressorSide(), "the rested remainder's side");
        assertEquals(-1L, accepted.aggressorParticipantId());
        assertEquals(-1L, accepted.passiveParticipantId());
    }
}
