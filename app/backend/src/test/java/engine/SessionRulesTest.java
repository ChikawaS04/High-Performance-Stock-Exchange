package engine;

import model.Prices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The pure rules both the engine and the session controller apply (P15-8). */
class SessionRulesTest {

    private static final byte[] ASML = Sessions.symbol();

    @Test
    void businessDatesAreRealCalendarDates() {
        assertTrue(SessionRules.isValidDate(20261102));
        assertTrue(SessionRules.isValidDate(19700101));
        assertTrue(SessionRules.isValidDate(99991231));
        assertTrue(SessionRules.isValidDate(20240229), "leap year");
        assertTrue(SessionRules.isValidDate(20000229), "divisible by 400");
        assertFalse(SessionRules.isValidDate(21000229), "divisible by 100 only");
        assertFalse(SessionRules.isValidDate(20260229));
        assertFalse(SessionRules.isValidDate(20261131), "November has 30 days");
        assertFalse(SessionRules.isValidDate(20261300));
        assertFalse(SessionRules.isValidDate(20261200));
        assertFalse(SessionRules.isValidDate(20261100));
        assertFalse(SessionRules.isValidDate(19691231), "before 1970");
        assertFalse(SessionRules.isValidDate(-1));
        assertFalse(SessionRules.isValidDate(0));
    }

    @Test
    void symbolsAreOneToEightAllowedCharactersThenNul() {
        assertTrue(SessionRules.isValidSymbol(ASML));
        assertTrue(SessionRules.isValidSymbol(Sessions.symbol("BRK.B")));
        assertTrue(SessionRules.isValidSymbol(Sessions.symbol("ABCDEFG8")));
        assertFalse(SessionRules.isValidSymbol(new byte[8]), "empty");
        assertFalse(SessionRules.isValidSymbol(Sessions.symbol("asml")), "lower case");
        assertFalse(SessionRules.isValidSymbol(Sessions.symbol("AS ML")), "space");
        byte[] gap = Sessions.symbol("AS");
        gap[3] = 'X';
        assertFalse(SessionRules.isValidSymbol(gap), "a character after the NUL padding");
        assertFalse(SessionRules.isValidSymbol(new byte[7]), "wrong length");
        assertFalse(SessionRules.isValidSymbol(null));
    }

    @Test
    void referenceDataMustBeWhatTheEngineHonours() {
        assertEquals(SessionRules.OK, SessionRules.checkReference(ASML, Prices.TICK, 1L, Prices.SCALE));
        assertEquals(SessionRules.BAD_SYMBOL, SessionRules.checkReference(new byte[8], Prices.TICK, 1L, Prices.SCALE));
        assertEquals(SessionRules.BAD_TICK_SIZE, SessionRules.checkReference(ASML, 1L, 1L, Prices.SCALE));
        assertEquals(SessionRules.BAD_LOT_SIZE, SessionRules.checkReference(ASML, Prices.TICK, 100L, Prices.SCALE));
        assertEquals(SessionRules.BAD_PRICE_SCALE, SessionRules.checkReference(ASML, Prices.TICK, 1L, 100L));
    }

    @Test
    void anOpenNeedsAClosedSessionAValidDateAfterTheLastClose() {
        assertEquals(SessionRules.OK, SessionRules.checkOpen(false, -1, 20261102, ASML, Prices.TICK, 1L, Prices.SCALE));
        assertEquals(SessionRules.OK, SessionRules.checkOpen(false, 20261030, 20261102, ASML, Prices.TICK, 1L, Prices.SCALE));
        assertEquals(SessionRules.ALREADY_OPEN,
                SessionRules.checkOpen(true, -1, 20261102, ASML, Prices.TICK, 1L, Prices.SCALE));
        assertEquals(SessionRules.INVALID_DATE,
                SessionRules.checkOpen(false, -1, 20261132, ASML, Prices.TICK, 1L, Prices.SCALE));
        assertEquals(SessionRules.DATE_NOT_AFTER_CLOSE,
                SessionRules.checkOpen(false, 20261102, 20261102, ASML, Prices.TICK, 1L, Prices.SCALE));
        assertEquals(SessionRules.DATE_NOT_AFTER_CLOSE,
                SessionRules.checkOpen(false, 20261102, 20261030, ASML, Prices.TICK, 1L, Prices.SCALE));
        assertEquals(SessionRules.BAD_TICK_SIZE,
                SessionRules.checkOpen(false, -1, 20261102, ASML, 5L, 1L, Prices.SCALE));
    }

    @Test
    void aCloseNeedsTheOpenSessionsDate() {
        assertEquals(SessionRules.OK, SessionRules.checkClose(true, 20261102, 20261102));
        assertEquals(SessionRules.NOT_OPEN, SessionRules.checkClose(false, -1, 20261102));
        assertEquals(SessionRules.WRONG_DATE, SessionRules.checkClose(true, 20261102, 20261103));
    }

    @Test
    void everyReasonHasADescription() {
        for (int code = SessionRules.OK; code <= SessionRules.BAD_PRICE_SCALE; code++) {
            assertFalse(SessionRules.describe(code).startsWith("unknown"), "code " + code);
        }
        assertTrue(SessionRules.describe(99).startsWith("unknown"));
    }
}
