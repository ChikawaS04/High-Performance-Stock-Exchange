package http;

import db.TestDatabase;

/**
 * Seeds {@code exchange_test} for the report tests (P15-12): participants, accounts, orders, fills,
 * clearing runs and results, balances, positions, candles and the Reporter's checkpoint, as the
 * Reporter and clearing would leave them. Never used against any other database; never a migration.
 * Seqs come from this instance's own counters, in call order; a fill's timestamp is 1,000 plus its seq.
 */
final class ReportSeed {

    static final long HOUSE = 1L;
    static final long BROKER_A = 2L;
    static final long BROKER_B = 3L;

    private long inboundSeq;
    private long outboundSeq;
    private long tradeId;

    /** Broker A and Broker B, external, with accounts 1 and 2; and a second instrument, NVDA. */
    ReportSeed brokers() {
        TestDatabase.execute("INSERT INTO instrument VALUES ('NVDA', 1, 1, 10000)");
        TestDatabase.execute("INSERT INTO participant VALUES (" + BROKER_A + ", 'Broker A', 'EXTERNAL'), ("
                + BROKER_B + ", 'Broker B', 'EXTERNAL')");
        TestDatabase.execute("INSERT INTO account (participant_id) VALUES (" + BROKER_A + "), (" + BROKER_B + ")");
        return this;
    }

    static long account(long participantId) {
        return Long.parseLong(TestDatabase.queryOne("SELECT account_id FROM account WHERE participant_id = " + participantId));
    }

    /**
     * One trade: an orders row for each side (passive first), and one fill naming the aggressor.
     *
     * @return the fill's outbound seq
     */
    long fill(int date, String symbol, long price, long quantity, String aggressorSide, long aggressor, long passive) {
        long passiveSeq = order(date, symbol, passive, aggressorSide.equals("BUY") ? "SELL" : "BUY", price, quantity);
        long aggressorSeq = order(date, symbol, aggressor, aggressorSide, price, quantity);
        long seq = ++outboundSeq;
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id, trade_id, price,"
                + " filled_quantity, remaining_quantity, aggressor_order_id, passive_order_id, passive_order_inbound_seq,"
                + " aggressor_side, aggressor_participant_id, passive_participant_id, business_date, event_ts) VALUES ("
                + seq + ", 'ORDER_FILLED', " + aggressorSeq + ", " + aggressorSeq + ", " + (++tradeId) + ", " + price + ", "
                + quantity + ", 0, " + aggressorSeq + ", " + passiveSeq + ", " + passiveSeq + ", '" + aggressorSide + "', "
                + aggressor + ", " + passive + ", " + date + ", " + (1_000L + seq) + ")");
        return seq;
    }

    /** A non-fill execution (an ACCEPTED) on {@code date}; returns its outbound seq. */
    long accepted(int date) {
        long orderSeq = order(date, "ASML", HOUSE, "BUY", 1_000_000L, 1L);
        long seq = ++outboundSeq;
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id,"
                + " remaining_quantity, business_date, event_ts) VALUES (" + seq + ", 'ORDER_ACCEPTED', " + orderSeq + ", "
                + orderSeq + ", 1, " + date + ", " + (1_000L + seq) + ")");
        return seq;
    }

    private long order(int date, String symbol, long participant, String side, long price, long quantity) {
        long seq = ++inboundSeq;
        TestDatabase.execute("INSERT INTO orders (inbound_seq, order_id, participant_id, business_date, symbol, side,"
                + " ord_type, time_in_force, limit_price, quantity, received_ts) VALUES (" + seq + ", " + seq + ", "
                + participant + ", " + date + ", '" + symbol + "', '" + side + "', 'LIMIT', 'GTC', " + price + ", "
                + quantity + ", " + seq + ")");
        return seq;
    }

    /** Closes {@code date}: a PENDING run at the next outbound seq, closed at 1,000 plus that seq. Returns the seq. */
    long close(int date) {
        long seq = ++outboundSeq;
        TestDatabase.execute("INSERT INTO clearing_run (business_date, status, close_outbound_seq, closed_ts) VALUES ("
                + date + ", 'PENDING', " + seq + ", " + (1_000L + seq) + ")");
        return seq;
    }

    /** Marks {@code date}'s run COMPLETE as clearing would, started at 2000 and completed at 2001. */
    static void complete(int date, int next, long fillCount, long volume) {
        TestDatabase.execute("UPDATE clearing_run SET status = 'COMPLETE', started_ts = 2000, completed_ts = 2001,"
                + " next_business_date = " + next + ", fill_count = " + fillCount + ", volume = " + volume
                + " WHERE business_date = " + date);
    }

    static void obligation(int date, long participant, String symbol, long bought, long sold, long cash) {
        TestDatabase.execute("INSERT INTO clearing_obligation VALUES (" + date + ", " + participant + ", '" + symbol + "', "
                + bought + ", " + sold + ", " + (bought - sold) + ", " + cash + ")");
    }

    static void balance(long participant, int date, long cash) {
        TestDatabase.execute("INSERT INTO account_balance VALUES (" + account(participant) + ", " + date + ", " + cash + ")");
    }

    static void position(long participant, int date, String symbol, long quantity) {
        TestDatabase.execute("INSERT INTO account_position VALUES (" + account(participant) + ", " + date + ", '"
                + symbol + "', " + quantity + ")");
    }

    static void house(int date, String symbol, long quantity) {
        TestDatabase.execute("INSERT INTO house_position VALUES (" + date + ", '" + symbol + "', " + quantity + ")");
    }

    static void candle(String symbol, int date, long bucketStartTs, long open, long high, long low, long close,
                       long volume, long count) {
        TestDatabase.execute("INSERT INTO candle VALUES ('" + symbol + "', " + bucketStartTs + ", " + date + ", " + open
                + ", " + high + ", " + low + ", " + close + ", " + volume + ", " + count + ", 1)");
    }

    /** The Reporter's checkpoint past everything seeded so far, with {@code sessionDate} open (or none if 0). */
    void checkpoint(int sessionDate) {
        TestDatabase.execute("UPDATE reporter_checkpoint SET last_inbound_seq = " + inboundSeq + ", last_outbound_seq = "
                + outboundSeq + ", session_business_date = " + (sessionDate == 0 ? "NULL" : Integer.toString(sessionDate))
                + ", session_symbol = " + (sessionDate == 0 ? "NULL" : "'ASML'") + " WHERE id = 1");
    }
}
