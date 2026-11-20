package clearing;

import db.TestDatabase;

/**
 * Seeds {@code exchange_test} with trading-record rows as the Reporter writes them, for the database
 * retention tests (P16-8). Seqs and timestamps are given by the caller, so every scenario is explicit.
 * Never used against any other database; never a migration.
 */
final class RetentionSeed {

    private RetentionSeed() {
    }

    static void order(long inboundSeq, long orderId, Integer date, String status, long lastOutboundSeq, long receivedTs) {
        TestDatabase.execute("INSERT INTO orders (inbound_seq, order_id, participant_id, business_date, symbol, side,"
                + " ord_type, time_in_force, limit_price, quantity, received_ts, status, cum_quantity, last_outbound_seq)"
                + " VALUES (" + inboundSeq + ", " + orderId + ", 1, " + date + ", " + (date == null ? "NULL" : "'ASML'")
                + ", 'BUY', 'LIMIT', 'GTC', 1000000, 5, " + receivedTs + ", " + (status == null ? "NULL" : "'" + status + "'")
                + ", 0, " + lastOutboundSeq + ")");
    }

    static void accepted(long outboundSeq, long orderInboundSeq, long orderId, Integer date, long ts) {
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id,"
                + " remaining_quantity, business_date, event_ts) VALUES (" + outboundSeq + ", 'ORDER_ACCEPTED', "
                + orderInboundSeq + ", " + orderId + ", 5, " + date + ", " + ts + ")");
    }

    static void rejected(long outboundSeq, long orderInboundSeq, long orderId, Integer date, long ts) {
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id,"
                + " business_date, event_ts) VALUES (" + outboundSeq + ", 'ORDER_REJECTED', " + orderInboundSeq + ", "
                + orderId + ", " + date + ", " + ts + ")");
    }

    static void fill(long outboundSeq, long aggressorInboundSeq, long passiveInboundSeq, long tradeId, long price,
                     long quantity, int date, long ts) {
        TestDatabase.execute("INSERT INTO executions (outbound_seq, event_type, order_inbound_seq, order_id, trade_id, price,"
                + " filled_quantity, remaining_quantity, aggressor_order_id, passive_order_id, passive_order_inbound_seq,"
                + " aggressor_side, aggressor_participant_id, passive_participant_id, business_date, event_ts) VALUES ("
                + outboundSeq + ", 'ORDER_FILLED', " + aggressorInboundSeq + ", " + aggressorInboundSeq + ", " + tradeId + ", "
                + price + ", " + quantity + ", 0, " + aggressorInboundSeq + ", " + passiveInboundSeq + ", "
                + passiveInboundSeq + ", 'BUY', 1, 1, " + date + ", " + ts + ")");
    }

    static void audit(String stream, long seq, Long orderInboundSeq, long orderId, String event, Integer date, long ts) {
        TestDatabase.execute("INSERT INTO order_audit (stream, seq, order_inbound_seq, order_id, event, business_date,"
                + " event_ts) VALUES ('" + stream + "', " + seq + ", " + orderInboundSeq + ", " + orderId + ", '" + event
                + "', " + date + ", " + ts + ")");
    }

    static void run(int date, String status, long closeOutboundSeq, long closedTs) {
        TestDatabase.execute("INSERT INTO clearing_run (business_date, status, close_outbound_seq, closed_ts) VALUES ("
                + date + ", 'PENDING', " + closeOutboundSeq + ", " + closedTs + ")");
        if ("COMPLETE".equals(status)) {
            TestDatabase.execute("UPDATE clearing_run SET status = 'COMPLETE', started_ts = 1, completed_ts = 2,"
                    + " next_business_date = " + (date + 1) + ", fill_count = 0, volume = 0 WHERE business_date = " + date);
        }
    }

    static void checkpoint(long inboundSeq, long outboundSeq) {
        TestDatabase.execute("UPDATE reporter_checkpoint SET last_inbound_seq = " + inboundSeq + ", last_outbound_seq = "
                + outboundSeq + " WHERE id = 1");
    }
}
