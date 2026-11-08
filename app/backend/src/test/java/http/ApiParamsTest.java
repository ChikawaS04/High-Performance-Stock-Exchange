package http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Strict query parameters for the /api routes (P15-12): anything malformed or out of range is a 400. */
class ApiParamsTest {

    private static final Set<String> ALL = Set.of("date", "afterSeq", "limit", "symbol", "interval", "participantId");

    private static ApiParams params(String... pairs) {
        Map<String, List<String>> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.computeIfAbsent(pairs[i], k -> new java.util.ArrayList<>()).add(pairs[i + 1]);
        }
        return new ApiParams(map, ALL);
    }

    private static void assertBadRequest(org.junit.jupiter.api.function.Executable e) {
        ApiException thrown = assertThrows(ApiException.class, e);
        assertSame(ApiResponse.BAD_REQUEST, thrown.response());
        assertNull(thrown.getMessage(), "nothing from the request is carried");
    }

    @Test
    void aValidDateParses() {
        assertEquals(20261107, params("date", "20261107").date("date"));
        assertEquals(20280229, params("date", "20280229").date("date"));
        assertEquals(19700101, params("date", "19700101").date("date"));
        assertEquals(99991231, params("date", "99991231").date("date"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026110", "202611070", "2026-11-07", "+2026110", " 20261107", "20261301", "20261100",
            "20270229", "19691231", "abcdefgh", ""})
    void aMalformedOrImpossibleDateIsRefused(String date) {
        assertBadRequest(() -> params("date", date).date("date"));
    }

    @Test
    void aMissingRequiredParameterIsRefused() {
        assertBadRequest(() -> params().date("date"));
        assertBadRequest(() -> params().symbol("symbol", true));
        assertBadRequest(() -> params().requiredLong("participantId", 1, Long.MAX_VALUE));
    }

    @Test
    void anUnknownOrRepeatedParameterIsRefused() {
        assertBadRequest(() -> params("date", "20261107", "dat", "x"));
        assertBadRequest(() -> params("date", "20261107", "date", "20261107"));
    }

    @Test
    void pagingDefaultsAndBounds() {
        ApiParams none = params();
        assertEquals(0L, none.afterSeq());
        assertEquals(ApiParams.DEFAULT_LIMIT, none.limit());
        assertEquals(1, params("limit", "1").limit());
        assertEquals(ApiParams.MAX_LIMIT, params("limit", "1000").limit());
        assertEquals(Long.MAX_VALUE, params("afterSeq", "9223372036854775807").afterSeq());
        assertBadRequest(() -> params("limit", "0").limit());
        assertBadRequest(() -> params("limit", "1001").limit());
        assertBadRequest(() -> params("afterSeq", "9223372036854775808").afterSeq());
        assertBadRequest(() -> params("afterSeq", "12345678901234567890").afterSeq());
        assertBadRequest(() -> params("afterSeq", "-1").afterSeq());
        assertBadRequest(() -> params("afterSeq", "1e3").afterSeq());
    }

    @Test
    void aParticipantIdIsPositive() {
        assertEquals(2L, params("participantId", "2").requiredLong("participantId", 1, Long.MAX_VALUE));
        assertBadRequest(() -> params("participantId", "0").requiredLong("participantId", 1, Long.MAX_VALUE));
    }

    @Test
    void aSymbolMatchesTheInstrumentRule() {
        assertEquals("ASML", params("symbol", "ASML").symbol("symbol", true));
        assertEquals("BRK.B", params("symbol", "BRK.B").symbol("symbol", true));
        assertNull(params().symbol("symbol", false));
        assertBadRequest(() -> params("symbol", "asml").symbol("symbol", true));
        assertBadRequest(() -> params("symbol", "ABCDEFGHI").symbol("symbol", true));
        assertBadRequest(() -> params("symbol", "").symbol("symbol", false));
        assertBadRequest(() -> params("symbol", "AS ML").symbol("symbol", true));
    }

    @Test
    void anIntervalIsOneOfTheFixedSet() {
        assertEquals(1, params().intervalMinutes());
        for (int minutes : new int[] {1, 5, 15, 30, 60}) {
            assertEquals(minutes, params("interval", Integer.toString(minutes)).intervalMinutes());
        }
        for (String bad : new String[] {"0", "2", "7", "61", "120", "05x"}) {
            assertBadRequest(() -> params("interval", bad).intervalMinutes());
        }
    }

    @Test
    void aRangeRunsForwardAndHoldsAtMost366Dates() {
        ApiParams.checkRange(20261107, 20261107);
        ApiParams.checkRange(20260101, 20261231);           // 365 dates
        ApiParams.checkRange(20280101, 20281231);           // 366 dates, a leap year
        assertBadRequest(() -> ApiParams.checkRange(20261108, 20261107));
        assertBadRequest(() -> ApiParams.checkRange(20260101, 20270102));
    }
}
