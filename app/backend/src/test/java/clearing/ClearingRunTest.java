package clearing;

import db.RequiresTestDatabase;
import db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static clearing.ClearingSeed.*;
import static org.junit.jupiter.api.Assertions.*;

/** One business date's clearing run against {@code exchange_test}: netting, roll forward, idempotency, invariants. */
@RequiresTestDatabase
class ClearingRunTest {

    private static final int DAY = 20261106;        // no calendar row, so the next business date is the 7th
    private static final int NEXT = 20261107;
    private static final long PX = 1_000_000L;      // $100.00
    private static final long MID = 1_000_050L;     // $100.005

    private final ClearingSeed seed = new ClearingSeed();
    private final AtomicLong wallClock = new AtomicLong(1_000L);

    @BeforeEach
    void emptySchema() {
        TestDatabase.cleanAndMigrate();
    }

    private ClearingStore.Attempt clear(int date) throws SQLException {
        try (Connection connection = TestDatabase.database().connect()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return ClearingStore.clear(connection, date, () -> wallClock.addAndGet(10L));
        }
    }

    private static String status(int date) {
        return TestDatabase.queryOne("SELECT status FROM clearing_run WHERE business_date = " + date);
    }

    /** A day with two brokers and the house, closed and caught up. */
    private void brokerDay() {
        seed.brokers();
        seed.fill(DAY, PX, 10L, "BUY", BROKER_A, BROKER_B);       // A buys 10 at $100.00 from B
        seed.fill(DAY, MID, 4L, "SELL", BROKER_A, HOUSE);         // A sells 4 at $100.005 to the house
        seed.fill(DAY, PX, 3L, "BUY", HOUSE, BROKER_B);           // the house buys 3 at $100.00 from B
        seed.fill(DAY, PX, 5L, "BUY", HOUSE, HOUSE);              // the house trades with itself
        seed.close(DAY);
        seed.caughtUp();
    }

    @Test
    void knownFillsGiveExactObligations() throws SQLException {
        brokerDay();
        ClearingStore.Attempt attempt = clear(DAY);
        assertEquals(ClearingStore.Kind.COMPLETED, attempt.kind());
        ClearingResult r = attempt.result();
        assertEquals(4L, r.fillCount());
        assertEquals(22L, r.volume());
        assertEquals(NEXT, r.nextBusinessDate());
        // House: bought 4 + 3 + 5, sold 5; pays 4,000,200 + 3,000,000, and its self-trade nets to zero.
        assertEquals(List.of(
                new Obligation(HOUSE, "ASML", 12L, 5L, 7L, 7_000_200L),
                new Obligation(BROKER_A, "ASML", 10L, 4L, 6L, 10_000_000L - 4_000_200L),
                new Obligation(BROKER_B, "ASML", 0L, 13L, -13L, -13_000_000L)), r.obligations());
        assertEquals(List.of(
                        DAY + " 1 ASML 12 5 7 7000200",
                        DAY + " 2 ASML 10 4 6 5999800",
                        DAY + " 3 ASML 0 13 -13 -13000000"),
                rows("SELECT * FROM clearing_obligation ORDER BY participant_id"));
        assertEquals("COMPLETE " + NEXT + " 4 22", TestDatabase.queryOne("SELECT status || ' ' || next_business_date"
                + " || ' ' || fill_count || ' ' || volume FROM clearing_run WHERE business_date = " + DAY));
    }

    @Test
    void externalBalancesAndPositionsRollForward() throws SQLException {
        brokerDay();
        balance(BROKER_A, DAY, 50_000_000L);
        position(BROKER_A, DAY, "ASML", 2L);
        balance(BROKER_B, DAY, 1_000L);
        clear(DAY);
        // Cash: opening minus the obligation (positive pays). Position: opening plus net.
        assertEquals(List.of(account(BROKER_A) + " " + NEXT + " " + (50_000_000L - 5_999_800L),
                        account(BROKER_B) + " " + NEXT + " " + (1_000L + 13_000_000L)),
                rows("SELECT * FROM account_balance WHERE business_date = " + NEXT + " ORDER BY account_id"));
        assertEquals(List.of(account(BROKER_A) + " " + NEXT + " ASML 8", account(BROKER_B) + " " + NEXT + " ASML -13"),
                rows("SELECT * FROM account_position WHERE business_date = " + NEXT + " ORDER BY account_id"));
    }

    @Test
    void theHousePositionRollsForwardWithoutCash() throws SQLException {
        brokerDay();
        house(DAY, "ASML", 100L);
        clear(DAY);
        assertEquals(List.of(NEXT + " ASML 107"), rows("SELECT * FROM house_position WHERE business_date = " + NEXT));
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM account a JOIN participant p USING (participant_id)"
                + " WHERE p.participant_type = 'INTERNAL'"), "the house never has a cash account");
    }

    @Test
    void aDayWithNoFillsCompletesWithZeroObligationsAndRollsEverything() throws SQLException {
        seed.brokers();
        balance(BROKER_A, DAY, 7L);
        position(BROKER_A, DAY, "ASML", 3L);
        house(DAY, "ASML", -2L);
        seed.accepted(DAY);
        seed.close(DAY);
        seed.caughtUp();
        ClearingResult r = clear(DAY).result();
        assertEquals(0L, r.fillCount());
        assertEquals(0L, r.volume());
        assertTrue(r.obligations().isEmpty());
        assertEquals("COMPLETE", status(DAY));
        // Broker B has no rows at all: it rolls forward from zero.
        assertEquals(List.of(account(BROKER_A) + " 7", account(BROKER_B) + " 0"),
                rows("SELECT account_id, cash FROM account_balance WHERE business_date = " + NEXT + " ORDER BY account_id"));
        assertEquals(List.of(account(BROKER_A) + " ASML 3"),
                rows("SELECT account_id, symbol, quantity FROM account_position WHERE business_date = " + NEXT));
        assertEquals(List.of("ASML -2"), rows("SELECT symbol, quantity FROM house_position WHERE business_date = " + NEXT));
    }

    @Test
    void openingValuesComeFromTheLatestEarlierRowOrZero() throws SQLException {
        seed.brokers();
        balance(BROKER_A, DAY - 3, 100L);
        balance(BROKER_A, DAY - 1, 200L);           // the latest at or before DAY
        position(BROKER_A, DAY - 3, "ASML", 9L);
        seed.fill(DAY, PX, 1L, "BUY", BROKER_A, BROKER_B);
        seed.close(DAY);
        seed.caughtUp();
        clear(DAY);
        assertEquals(List.of(account(BROKER_A) + " " + (200L - PX), account(BROKER_B) + " " + PX),
                rows("SELECT account_id, cash FROM account_balance WHERE business_date = " + NEXT + " ORDER BY account_id"));
        assertEquals(List.of(account(BROKER_A) + " 10", account(BROKER_B) + " -1"),
                rows("SELECT account_id, quantity FROM account_position WHERE business_date = " + NEXT + " ORDER BY account_id"));
    }

    @Test
    void aNonTradingCalendarDayIsSkipped() throws SQLException {
        TestDatabase.execute("INSERT INTO trading_calendar VALUES (" + NEXT + ", false, 'Saturday'), (20261108, false,"
                + " 'Sunday'), (20261110, true, 'An ordinary day')");
        seed.close(DAY);
        seed.caughtUp();
        assertEquals(20261109, clear(DAY).result().nextBusinessDate());
    }

    @Test
    void twoPendingDaysClearInOrderEachFromThePreviousResult() throws SQLException {
        seed.brokers();
        balance(BROKER_A, DAY, 1_000_000_000L);
        seed.fill(DAY, PX, 2L, "BUY", BROKER_A, BROKER_B);
        seed.close(DAY);
        seed.fill(NEXT, PX, 1L, "SELL", BROKER_A, BROKER_B);
        seed.close(NEXT);
        seed.caughtUp();

        assertEquals(ClearingStore.Kind.EARLIER_PENDING, clear(NEXT).kind());
        assertEquals("PENDING", status(NEXT));
        assertEquals("0", TestDatabase.queryOne("SELECT count(*) FROM clearing_obligation"));

        assertEquals(ClearingStore.Kind.COMPLETED, clear(DAY).kind());
        assertEquals(ClearingStore.Kind.COMPLETED, clear(NEXT).kind());
        int third = 20261108;
        assertEquals(String.valueOf(1_000_000_000L - 2 * PX + PX), TestDatabase.queryOne("SELECT cash FROM account_balance"
                + " WHERE account_id = " + account(BROKER_A) + " AND business_date = " + third));
        assertEquals("1", TestDatabase.queryOne("SELECT quantity FROM account_position WHERE account_id = "
                + account(BROKER_A) + " AND business_date = " + third));
    }

    @Test
    void aReRunForAClosedDateReturnsTheSameResultAndChangesNothing() throws SQLException {
        brokerDay();
        balance(BROKER_A, DAY, 5L);
        ClearingResult first = clear(DAY).result();
        List<String> before = dump();
        ClearingStore.Attempt again = clear(DAY);
        assertEquals(ClearingStore.Kind.ALREADY_COMPLETE, again.kind());
        assertEquals(first, again.result());
        assertEquals(before, dump(), "no row changed, not even its version");
    }

    @Test
    void itWaitsWhileTheCheckpointIsBelowTheClose() throws SQLException {
        seed.fill(DAY, PX, 1L, "BUY", HOUSE, HOUSE);
        long close = seed.close(DAY);
        checkpoint(close - 1);
        ClearingStore.Attempt attempt = clear(DAY);
        assertEquals(ClearingStore.Kind.WAITING_FOR_REPORTER, attempt.kind());
        assertEquals(close - 1, attempt.checkpointSeq());
        assertEquals(close, attempt.closeSeq());
        assertEquals("PENDING", status(DAY));
        checkpoint(close);
        assertEquals(ClearingStore.Kind.COMPLETED, clear(DAY).kind());
    }

    @Test
    void aDateWithoutARunIsReportedAsSuch() throws SQLException {
        assertEquals(ClearingStore.Kind.NO_RUN, clear(DAY).kind());
    }

    /** Asserts that {@code date} fails with a message naming {@code fragment} and wrote nothing. */
    private void assertStops(int date, String fragment) {
        List<String> before = dump();
        ClearingInvariantException e = assertThrows(ClearingInvariantException.class, () -> clear(date));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
        assertEquals(before, dump(), "nothing written");
        assertEquals("PENDING", status(date));
    }

    @Test
    void anExecutionAfterTheCloseStops() {
        long close = seed.close(DAY);
        seed.accepted(DAY);
        seed.caughtUp();
        assertStops(DAY, "closed at outbound seq " + close);
    }

    @Test
    void aFillWithNoBusinessDateStops() {
        long fill = seed.fill(DAY, PX, 1L, "BUY", HOUSE, HOUSE);
        TestDatabase.execute("UPDATE executions SET business_date = NULL WHERE outbound_seq = " + fill);
        seed.close(DAY);
        seed.caughtUp();
        assertStops(DAY, "outbound seq " + fill + " has no business date");
    }

    @Test
    void anEarlierDateWithExecutionsAndNoRunStops() {
        seed.fill(DAY - 1, PX, 1L, "BUY", HOUSE, HOUSE);    // closed before clearing existed
        seed.close(DAY);
        seed.caughtUp();
        assertStops(DAY, "Business date " + (DAY - 1) + " has executions but no clearing run");
    }

    @Test
    void rowsAlreadyDatedAfterTheDateStop() {
        house(NEXT, "ASML", 1L);
        seed.close(DAY);
        seed.caughtUp();
        assertStops(DAY, "dated after " + DAY);
    }

    @Test
    void anExternalParticipantWithoutAnAccountStops() {
        TestDatabase.execute("INSERT INTO participant VALUES (4, 'No account', 'EXTERNAL')");
        seed.fill(DAY, PX, 1L, "BUY", 4L, HOUSE);
        seed.close(DAY);
        seed.caughtUp();
        assertStops(DAY, "Participant 4 traded");
    }

    @Test
    void anInternalParticipantWithAnAccountStops() {
        TestDatabase.execute("INSERT INTO account (participant_id) VALUES (1)");
        seed.close(DAY);
        seed.caughtUp();
        assertStops(DAY, "internal participant 1 has account");
    }

    @Test
    void anObligationThatOverflowsStops() {
        seed.fill(DAY, Long.MAX_VALUE / 2, 3L, "BUY", HOUSE, HOUSE);
        seed.close(DAY);
        seed.caughtUp();
        assertStops(DAY, "overflows a long");
    }
}
