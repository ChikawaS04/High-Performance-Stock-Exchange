package clearing;

import db.TestDatabase;

import java.util.ArrayList;
import java.util.List;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Seeds {@code exchange_test} for clearing: external participants with accounts and opening rows, and
 * synthetic orders and fills, since no external participant trades through the venue yet. Never used
 * against any other database; never a migration. Seqs come from this instance's own counters.
 */
final class ClearingSeed {

    static final long HOUSE = 1L;
    static final long BROKER_A = 2L;
    static final long BROKER_B = 3L;

    private long inboundSeq;
    private long outboundSeq;
    private long tradeId;

    /** Two external participants, each with an account. */
    ClearingSeed brokers() {
        TestDatabase.execute("INSERT INTO participant VALUES (" + BROKER_A + ", 'Broker A', 'EXTERNAL'), ("
                + BROKER_B + ", 'Broker B', 'EXTERNAL')");
        TestDatabase.execute("INSERT INTO account (participant_id) VALUES (" + BROKER_A + "), (" + BROKER_B + ")");
        return this;
    }

    static long account(long participantId) {
        return Long.parseLong(TestDatabase.queryOne("SELECT account_id FROM account WHERE participant_id = " + participantId));
    }

    static void balance(long participantId, int date, long cash) {
        TestDatabase.execute("INSERT INTO account_balance VALUES (" + account(participantId) + ", " + date + ", " + cash + ")");
    }

    static void position(long participantId, int date, String symbol, long quantity) {
        TestDatabase.execute("INSERT INTO account_position VALUES (" + account(participantId) + ", " + date + ", '"
                + symbol + "', " + quantity + ")");
    }

    static void house(int date, String symbol, long quantity) {
        TestDatabase.execute("INSERT INTO house_position VALUES (" + date + ", '" + symbol + "', " + quantity + ")");
    }

    /**
     * One trade on {@code date}: an orders row for each side and one fill row naming the aggressor.
     *
     * @return the fill's outbound seq
     */
    long fill(int date, long price, long quantity, String aggressorSide, long aggressor, long passive) {
        long passiveSeq = order(date, passive, aggressorSide.equals("BUY") ? "SELL" : "BUY", price, quantity);
        long aggressorSeq = order(date, aggressor, aggressorSide, price, quantity);
        long seq = ++outboundSeq;
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id, trade_id, price,"
                + " filled_quantity, remaining_quantity, aggressor_order_id, passive_order_id, passive_order_inbound_seq,"
                + " aggressor_side, aggressor_participant_id, passive_participant_id, business_date, event_ts) VALUES ("
                + seq + ", 'ORDER_FILLED', " + aggressorSeq + ", " + aggressorSeq + ", " + (++tradeId) + ", " + price + ", "
                + quantity + ", 0, " + aggressorSeq + ", " + passiveSeq + ", " + passiveSeq + ", '" + aggressorSide + "', "
                + aggressor + ", " + passive + ", " + date + ", " + seq + ")");
        return seq;
    }

    /** A non-fill execution on {@code date}, such as an ACCEPTED; returns its outbound seq. */
    long accepted(int date) {
        long orderSeq = order(date, HOUSE, "BUY", 1_000_000L, 1L);
        long seq = ++outboundSeq;
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id,"
                + " remaining_quantity, business_date, event_ts) VALUES (" + seq + ", 'ORDER_ACCEPTED', " + orderSeq + ", "
                + orderSeq + ", 1, " + date + ", " + seq + ")");
        return seq;
    }

    private long order(int date, long participant, String side, long price, long quantity) {
        long seq = ++inboundSeq;
        TestDatabase.execute("INSERT INTO orders (inbound_seq, order_id, participant_id, business_date, symbol, side,"
                + " ord_type, time_in_force, limit_price, quantity, received_ts) VALUES (" + seq + ", " + seq + ", "
                + participant + ", " + date + ", 'ASML', '" + side + "', 'LIMIT', 'GTC', " + price + ", " + quantity + ", "
                + seq + ")");
        return seq;
    }

    /** Closes {@code date} as the Reporter would: a PENDING run at the next outbound seq. Returns that seq. */
    long close(int date) {
        long seq = ++outboundSeq;
        TestDatabase.execute("INSERT INTO clearing_run (business_date, status, close_outbound_seq, closed_ts) VALUES ("
                + date + ", 'PENDING', " + seq + ", " + seq + ")");
        return seq;
    }

    /** The Reporter's checkpoint at {@code seq}. */
    static void checkpoint(long seq) {
        TestDatabase.execute("UPDATE reporter_checkpoint SET last_outbound_seq = " + seq + " WHERE id = 1");
    }

    /** Moves the checkpoint past everything seeded so far. */
    void caughtUp() {
        checkpoint(outboundSeq);
    }

    /**
     * Every row clearing reads or writes, each with its row version ({@code xmin}), so an UPDATE that
     * changes no value still shows.
     */
    static List<String> dump() {
        List<String> all = new ArrayList<>();
        for (String sql : List.of(
                "SELECT xmin::text || ' ' || r::text FROM clearing_run r ORDER BY business_date",
                "SELECT xmin::text || ' ' || o::text FROM clearing_obligation o ORDER BY business_date, participant_id, symbol",
                "SELECT xmin::text || ' ' || b::text FROM account_balance b ORDER BY account_id, business_date",
                "SELECT xmin::text || ' ' || p::text FROM account_position p ORDER BY account_id, business_date, symbol",
                "SELECT xmin::text || ' ' || h::text FROM house_position h ORDER BY business_date, symbol")) {
            all.addAll(rows(sql));
        }
        return all;
    }

    static List<String> rows(String sql) {
        List<String> rows = new ArrayList<>();
        try (Connection c = TestDatabase.database().connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            int columns = r.getMetaData().getColumnCount();
            while (r.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) b.append(' ');
                    b.append(r.getString(i));
                }
                rows.add(b.toString());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
        return rows;
    }
}
