package clearing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Which dates database retention may purge (P16-8): the newest N COMPLETE dates are kept. Pure. */
class RetentionWindowTest {

    @Test
    void nothingIsEligibleUntilMoreThanNDatesAreComplete() {
        assertEquals(List.of(), RetentionWindow.eligible(List.of(), 2));
        assertEquals(List.of(), RetentionWindow.eligible(List.of(20261101), 2));
        assertEquals(List.of(), RetentionWindow.eligible(List.of(20261101, 20261102), 2));
    }

    @Test
    void theWorkedExampleWithTwoDays() {
        // k = 3: the first date goes, as the journal's first day goes at its third close (P16-7).
        assertEquals(List.of(20261101), RetentionWindow.eligible(List.of(20261101, 20261102, 20261103), 2));
        // 20261105 is a holiday: no run, so it does not count; k = 4 makes 20261102 eligible.
        assertEquals(List.of(20261101, 20261102),
                RetentionWindow.eligible(List.of(20261101, 20261102, 20261103, 20261104), 2));
        assertEquals(List.of(20261101, 20261102, 20261103),
                RetentionWindow.eligible(List.of(20261101, 20261102, 20261103, 20261104, 20261106), 2));
    }

    @Test
    void fourteenDaysKeepsFourteenCompleteDates() {
        List<Integer> fourteen = List.of(20261101, 20261102, 20261103, 20261104, 20261105, 20261106, 20261107,
                20261108, 20261109, 20261110, 20261111, 20261112, 20261113, 20261114);
        assertEquals(List.of(), RetentionWindow.eligible(fourteen, 14));
        List<Integer> fifteen = new java.util.ArrayList<>(fourteen);
        fifteen.add(20261115);
        assertEquals(List.of(20261101), RetentionWindow.eligible(fifteen, 14));
    }

    @Test
    void zeroPurgesEveryCompleteDate() {
        assertEquals(List.of(20261101, 20261102), RetentionWindow.eligible(List.of(20261101, 20261102), 0));
    }

    @Test
    void rejectsUnorderedInputAndNegativeDays() {
        assertThrows(IllegalArgumentException.class, () -> RetentionWindow.eligible(List.of(20261102, 20261101), 1));
        assertThrows(IllegalArgumentException.class, () -> RetentionWindow.eligible(List.of(20261101, 20261101), 1));
        assertThrows(IllegalArgumentException.class, () -> RetentionWindow.eligible(List.of(20261101), -1));
    }
}
