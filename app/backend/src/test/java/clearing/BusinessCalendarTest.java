package clearing;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The next business date from calendar exceptions, without a database. */
class BusinessCalendarTest {

    @Test
    void withNoExceptionsTheNextDayIsTheNextBusinessDate() {
        assertEquals(20261108, BusinessCalendar.next(20261107, Set.of()));
    }

    @Test
    void nonTradingDaysAreSkipped() {
        assertEquals(20261109, BusinessCalendar.next(20261106, Set.of(20261107, 20261108)));
    }

    @Test
    void aTradingDayRowIsNotAnException() {
        // Only non-trading rows reach next(); a row saying "trading" is the same as no row.
        assertEquals(20261108, BusinessCalendar.next(20261107, Set.of(20261110)));
    }

    @Test
    void monthAndYearEndsRoll() {
        assertEquals(20261201, BusinessCalendar.next(20261130, Set.of()));
        assertEquals(20270101, BusinessCalendar.next(20261231, Set.of()));
    }

    @Test
    void leapYearsAreRespected() {
        assertEquals(20280229, BusinessCalendar.next(20280228, Set.of()));
        assertEquals(20270301, BusinessCalendar.next(20270228, Set.of()));
    }

    @Test
    void theSchemasLastDateHasNoNextDate() {
        assertThrows(ClearingInvariantException.class, () -> BusinessCalendar.next(99991231, Set.of()));
        assertThrows(ClearingInvariantException.class, () -> BusinessCalendar.next(99991230, Set.of(99991231)));
    }

    @Test
    void tooManyNonTradingDaysInARowIsABrokenCalendar() {
        Set<Integer> all = new HashSet<>();
        java.time.LocalDate d = java.time.LocalDate.of(2026, 11, 8);
        for (int i = 0; i <= BusinessCalendar.MAX_NON_TRADING_RUN; i++) {
            all.add(BusinessCalendar.toInt(d));
            d = d.plusDays(1);
        }
        assertThrows(ClearingInvariantException.class, () -> BusinessCalendar.next(20261107, all));
    }
}
