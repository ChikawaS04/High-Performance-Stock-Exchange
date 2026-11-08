package http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The reports behind {@code /api} (SRS 3.13), read from the trading record and the clearing results.
 * Every method runs inside the caller's read-only REPEATABLE READ transaction, and every report reads
 * the Reporter's checkpoint in that same snapshot: the Reporter commits its rows and its checkpoint
 * together, so the rows a report shows are exactly those up to {@code asOfOutboundSeq}.
 *
 * Prices and amounts are long units of $0.0001. A fill is an {@code executions} row with a trade id
 * (one row per trade, naming the aggressor); its symbol comes from the aggressor's {@code orders} row,
 * as clearing reads it. Notional is summed exactly in SQL ({@code numeric}) and cast to bigint, so an
 * overflow is SQLState 22003, never a wrapped value. Figures that depend on clearing are null unless
 * the date's run is COMPLETE ({@link DayStatus#CLEARED}).
 */
final class ReportStore {

    private static final String CONTEXT = "SELECT c.last_outbound_seq, c.session_business_date, r.status"
            + " FROM reporter_checkpoint c LEFT JOIN clearing_run r ON r.business_date = ? WHERE c.id = 1";
    private static final String CHECKPOINT = "SELECT last_outbound_seq, session_business_date FROM reporter_checkpoint"
            + " WHERE id = 1";
    private static final String SYMBOL_EXISTS = "SELECT 1 FROM instrument WHERE symbol = ?";

    private static final String TRADES = "SELECT e.outbound_seq, e.trade_id, e.event_ts, o.symbol, e.price,"
            + " e.filled_quantity, e.aggressor_side, e.aggressor_order_id, e.passive_order_id,"
            + " e.aggressor_participant_id, e.passive_participant_id FROM executions e"
            + " LEFT JOIN orders o ON o.inbound_seq = e.order_inbound_seq"
            + " WHERE e.business_date = ? AND e.trade_id IS NOT NULL AND e.outbound_seq > ?"
            + " ORDER BY e.outbound_seq LIMIT ?";

    private static final String DAILY_SYMBOLS = "SELECT o.symbol, count(*), sum(e.filled_quantity)::bigint,"
            + " sum(e.price::numeric * e.filled_quantity)::bigint, max(e.price), min(e.price), min(e.event_ts),"
            + " max(e.event_ts), (array_agg(e.price ORDER BY e.outbound_seq))[1],"
            + " (array_agg(e.price ORDER BY e.outbound_seq DESC))[1] FROM executions e"
            + " LEFT JOIN orders o ON o.inbound_seq = e.order_inbound_seq"
            + " WHERE e.business_date = ? AND e.trade_id IS NOT NULL GROUP BY o.symbol ORDER BY o.symbol";
    private static final String RUN = "SELECT status, close_outbound_seq, closed_ts, completed_ts, next_business_date,"
            + " fill_count, volume FROM clearing_run WHERE business_date = ?";
    private static final String OBLIGATIONS = "SELECT participant_id, symbol, bought_quantity, sold_quantity,"
            + " net_quantity, cash_obligation FROM clearing_obligation WHERE business_date = ?"
            + " ORDER BY participant_id, symbol";

    private static final String VOLUME = "SELECT e.business_date, o.symbol, count(*), sum(e.filled_quantity)::bigint,"
            + " sum(e.price::numeric * e.filled_quantity)::bigint FROM executions e"
            + " LEFT JOIN orders o ON o.inbound_seq = e.order_inbound_seq"
            + " WHERE e.business_date BETWEEN ? AND ? AND e.trade_id IS NOT NULL AND (?::varchar IS NULL OR o.symbol = ?)"
            + " GROUP BY e.business_date, o.symbol ORDER BY e.business_date, o.symbol";
    private static final String RUNS_IN_RANGE = "SELECT business_date, status FROM clearing_run"
            + " WHERE business_date BETWEEN ? AND ?";

    private static final String PARTICIPANT = "SELECT p.participant_id, p.name, p.participant_type, a.account_id"
            + " FROM participant p LEFT JOIN account a ON a.participant_id = p.participant_id WHERE p.participant_id = ?";
    private static final String PARTICIPANT_FILLS = "SELECT e.outbound_seq, e.trade_id, e.event_ts, o.symbol,"
            + " e.aggressor_side, e.price, e.filled_quantity, e.aggressor_participant_id, e.passive_participant_id"
            + " FROM executions e LEFT JOIN orders o ON o.inbound_seq = e.order_inbound_seq"
            + " WHERE e.business_date = ? AND e.trade_id IS NOT NULL AND e.outbound_seq > ?"
            + " AND (e.aggressor_participant_id = ? OR e.passive_participant_id = ?) ORDER BY e.outbound_seq LIMIT ?";
    private static final String PARTICIPANT_OBLIGATIONS = "SELECT symbol, bought_quantity, sold_quantity, net_quantity,"
            + " cash_obligation FROM clearing_obligation WHERE business_date = ? AND participant_id = ? ORDER BY symbol";
    private static final String OPENING_CASH = "SELECT cash FROM account_balance WHERE account_id = ?"
            + " AND business_date <= ? ORDER BY business_date DESC LIMIT 1";
    private static final String CASH_ON = "SELECT cash FROM account_balance WHERE account_id = ? AND business_date = ?";
    private static final String OPENING_POSITIONS = "SELECT DISTINCT ON (symbol) symbol, quantity FROM account_position"
            + " WHERE account_id = ? AND business_date <= ? ORDER BY symbol, business_date DESC";
    private static final String POSITIONS_ON = "SELECT symbol, quantity FROM account_position"
            + " WHERE account_id = ? AND business_date = ?";
    private static final String OPENING_HOUSE = "SELECT DISTINCT ON (symbol) symbol, quantity FROM house_position"
            + " WHERE business_date <= ? ORDER BY symbol, business_date DESC";
    private static final String HOUSE_ON = "SELECT symbol, quantity FROM house_position WHERE business_date = ?";

    private static final String CANDLES = "SELECT bucket_start_ts, open, high, low, close, volume, trade_count"
            + " FROM candle WHERE symbol = ? AND business_date = ? ORDER BY bucket_start_ts";

    private ReportStore() {
    }

    /** The checkpoint and where one date stands, from the same snapshot. */
    record Context(long asOfOutboundSeq, DayStatus dayStatus) {
    }

    static Context context(Connection c, int date) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(CONTEXT)) {
            s.setInt(1, date);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SQLException("reporter_checkpoint has no row");
                int session = r.getInt(2);
                boolean open = !r.wasNull() && session == date;
                return new Context(r.getLong(1), DayStatus.of(r.getString(3), open));
            }
        }
    }

    /** GET /api/trades: the date's fills in outbound seq order, a page at a time. */
    static ObjectNode trades(Connection c, ObjectMapper mapper, int date, long afterSeq, int limit) throws SQLException {
        Context ctx = context(c, date);
        ObjectNode body = header(mapper, date, ctx);
        ArrayNode trades = body.putArray("trades");
        int rows = 0;
        long lastSeq = afterSeq;
        boolean more = false;
        try (PreparedStatement s = c.prepareStatement(TRADES)) {
            s.setInt(1, date);
            s.setLong(2, afterSeq);
            s.setInt(3, limit + 1);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (rows == limit) {
                        more = true;
                        break;
                    }
                    rows++;
                    lastSeq = r.getLong(1);
                    ObjectNode t = trades.addObject();
                    t.put("outboundSeq", lastSeq);
                    t.put("tradeId", r.getLong(2));
                    t.put("timestamp", r.getLong(3));
                    t.put("symbol", r.getString(4));
                    t.put("price", r.getLong(5));
                    t.put("quantity", r.getLong(6));
                    t.put("aggressorSide", r.getString(7));
                    putLong(t, "aggressorOrderId", r, 8);
                    putLong(t, "passiveOrderId", r, 9);
                    putLong(t, "aggressorParticipantId", r, 10);
                    putLong(t, "passiveParticipantId", r, 11);
                }
            }
        }
        putNext(body, more, lastSeq);
        return body;
    }

    /** GET /api/reports/daily: per-symbol figures, the clearing run, and its obligations once COMPLETE. */
    static ObjectNode daily(Connection c, ObjectMapper mapper, int date) throws SQLException {
        Context ctx = context(c, date);
        ObjectNode body = header(mapper, date, ctx);
        ObjectNode run = null;
        try (PreparedStatement s = c.prepareStatement(RUN)) {
            s.setInt(1, date);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    run = mapper.createObjectNode();
                    run.put("status", r.getString(1));
                    run.put("closeOutboundSeq", r.getLong(2));
                    run.put("closedTs", r.getLong(3));
                    putLong(run, "completedTs", r, 4);
                    putInt(run, "nextBusinessDate", r, 5);
                    putLong(run, "fillCount", r, 6);
                    putLong(run, "volume", r, 7);
                }
            }
        }
        if (run == null) {
            body.putNull("clearing");
        } else {
            body.set("clearing", run);
        }
        ArrayNode symbols = body.putArray("symbols");
        try (PreparedStatement s = c.prepareStatement(DAILY_SYMBOLS)) {
            s.setInt(1, date);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    ObjectNode n = symbols.addObject();
                    n.put("symbol", r.getString(1));
                    n.put("tradeCount", r.getLong(2));
                    long volume = r.getLong(3);
                    long notional = r.getLong(4);
                    n.put("volume", volume);
                    n.put("notional", notional);
                    n.put("vwap", Vwap.halfEven(notional, volume));
                    n.put("open", r.getLong(9));
                    n.put("high", r.getLong(5));
                    n.put("low", r.getLong(6));
                    n.put("close", r.getLong(10));
                    n.put("firstTradeTs", r.getLong(7));
                    n.put("lastTradeTs", r.getLong(8));
                }
            }
        }
        if (ctx.dayStatus() != DayStatus.CLEARED) {
            body.putNull("obligations");
            return body;
        }
        ArrayNode obligations = body.putArray("obligations");
        try (PreparedStatement s = c.prepareStatement(OBLIGATIONS)) {
            s.setInt(1, date);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    ObjectNode o = obligations.addObject();
                    o.put("participantId", r.getLong(1));
                    o.put("symbol", r.getString(2));
                    o.put("bought", r.getLong(3));
                    o.put("sold", r.getLong(4));
                    o.put("net", r.getLong(5));
                    o.put("cashObligation", r.getLong(6));
                }
            }
        }
        return body;
    }

    /** GET /api/reports/volume: volume, notional and VWAP per date and symbol over a range, with totals. */
    static ObjectNode volume(Connection c, ObjectMapper mapper, int from, int to, String symbol) throws SQLException {
        if (symbol != null) requireSymbol(c, symbol);
        long asOf;
        int sessionDate;
        try (PreparedStatement s = c.prepareStatement(CHECKPOINT); ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("reporter_checkpoint has no row");
            asOf = r.getLong(1);
            int session = r.getInt(2);
            sessionDate = r.wasNull() ? -1 : session;
        }
        Map<Integer, String> runs = new HashMap<>();
        try (PreparedStatement s = c.prepareStatement(RUNS_IN_RANGE)) {
            s.setInt(1, from);
            s.setInt(2, to);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) runs.put(r.getInt(1), r.getString(2));
            }
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("from", from);
        body.put("to", to);
        if (symbol == null) {
            body.putNull("symbol");
        } else {
            body.put("symbol", symbol);
        }
        body.put("asOfOutboundSeq", asOf);
        ArrayNode days = body.putArray("days");
        Map<String, long[]> totals = new TreeMap<>();   // symbol -> tradeCount, volume, notional
        try (PreparedStatement s = c.prepareStatement(VOLUME)) {
            s.setInt(1, from);
            s.setInt(2, to);
            s.setString(3, symbol);
            s.setString(4, symbol);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    int date = r.getInt(1);
                    String sym = r.getString(2);
                    long count = r.getLong(3);
                    long volume = r.getLong(4);
                    long notional = r.getLong(5);
                    ObjectNode d = days.addObject();
                    d.put("businessDate", date);
                    d.put("dayStatus", DayStatus.of(runs.get(date), date == sessionDate).name());
                    d.put("symbol", sym);
                    d.put("tradeCount", count);
                    d.put("volume", volume);
                    d.put("notional", notional);
                    d.put("vwap", Vwap.halfEven(notional, volume));
                    long[] t = totals.computeIfAbsent(sym == null ? "" : sym, k -> new long[3]);
                    t[0] = Math.addExact(t[0], count);
                    t[1] = Math.addExact(t[1], volume);
                    t[2] = Math.addExact(t[2], notional);
                }
            }
        }
        ArrayNode totalNodes = body.putArray("totals");
        for (Map.Entry<String, long[]> e : totals.entrySet()) {
            long[] t = e.getValue();
            ObjectNode n = totalNodes.addObject();
            if (e.getKey().isEmpty()) {
                n.putNull("symbol");
            } else {
                n.put("symbol", e.getKey());
            }
            n.put("tradeCount", t[0]);
            n.put("volume", t[1]);
            n.put("notional", t[2]);
            n.put("vwap", Vwap.halfEven(t[2], t[1]));
        }
        return body;
    }

    /**
     * GET /api/reports/statement: one participant's day. Fills are listed one per leg, from the
     * participant's side, with no counterparty (post-trade anonymity); a self-trade shows both legs.
     * {@code limit} counts trades. External accounts show cash and positions; the house shows positions
     * only. Opening values are the latest row dated on or before the date and closing values the row
     * at the run's next business date, each zero when there is no row, as clearing reads them.
     */
    static ObjectNode statement(Connection c, ObjectMapper mapper, long participantId, int date, long afterSeq,
                                int limit) throws SQLException {
        ObjectNode participant = mapper.createObjectNode();
        String type;
        long accountId;
        try (PreparedStatement s = c.prepareStatement(PARTICIPANT)) {
            s.setLong(1, participantId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw ApiException.notFound();
                participant.put("participantId", r.getLong(1));
                participant.put("name", r.getString(2));
                type = r.getString(3);
                participant.put("type", type);
                accountId = r.getLong(4);
                if (r.wasNull()) {
                    accountId = -1L;
                    participant.putNull("accountId");
                } else {
                    participant.put("accountId", accountId);
                }
            }
        }
        Context ctx = context(c, date);
        ObjectNode body = mapper.createObjectNode();
        body.set("participant", participant);
        body.put("businessDate", date);
        body.put("dayStatus", ctx.dayStatus().name());
        body.put("asOfOutboundSeq", ctx.asOfOutboundSeq());

        ArrayNode fills = body.putArray("fills");
        int rows = 0;
        long lastSeq = afterSeq;
        boolean more = false;
        try (PreparedStatement s = c.prepareStatement(PARTICIPANT_FILLS)) {
            s.setInt(1, date);
            s.setLong(2, afterSeq);
            s.setLong(3, participantId);
            s.setLong(4, participantId);
            s.setInt(5, limit + 1);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (rows == limit) {
                        more = true;
                        break;
                    }
                    rows++;
                    lastSeq = r.getLong(1);
                    String aggressorSide = r.getString(5);
                    String passiveSide = "BUY".equals(aggressorSide) ? "SELL" : "BUY";
                    if (r.getLong(8) == participantId) {
                        leg(fills.addObject(), r, "AGGRESSOR", aggressorSide);
                    }
                    if (r.getLong(9) == participantId) {
                        leg(fills.addObject(), r, "PASSIVE", passiveSide);
                    }
                }
            }
        }
        putNext(body, more, lastSeq);

        boolean cleared = ctx.dayStatus() == DayStatus.CLEARED;
        if (cleared) {
            ArrayNode obligations = body.putArray("obligations");
            try (PreparedStatement s = c.prepareStatement(PARTICIPANT_OBLIGATIONS)) {
                s.setInt(1, date);
                s.setLong(2, participantId);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        ObjectNode o = obligations.addObject();
                        o.put("symbol", r.getString(1));
                        o.put("bought", r.getLong(2));
                        o.put("sold", r.getLong(3));
                        o.put("net", r.getLong(4));
                        o.put("cashObligation", r.getLong(5));
                    }
                }
            }
        } else {
            body.putNull("obligations");
        }
        int next = cleared ? nextBusinessDate(c, date) : -1;

        boolean house = "INTERNAL".equals(type);
        if (house || accountId < 0) {
            body.putNull("cash");
        } else {
            ObjectNode cash = body.putObject("cash");
            Long opening = singleLong(c, OPENING_CASH, accountId, date);
            cash.put("opening", opening == null ? 0L : opening);
            if (next < 0) {
                cash.putNull("closing");
            } else {
                Long closing = singleLong(c, CASH_ON, accountId, next);
                cash.put("closing", closing == null ? 0L : closing);
            }
        }

        ArrayNode positions = body.putArray("positions");
        if (house || accountId >= 0) {
            Map<String, Long> opening = house ? quantities(c, OPENING_HOUSE, -1L, date)
                    : quantities(c, OPENING_POSITIONS, accountId, date);
            Map<String, Long> closing = next < 0 ? Map.of()
                    : house ? quantities(c, HOUSE_ON, -1L, next) : quantities(c, POSITIONS_ON, accountId, next);
            TreeMap<String, Boolean> symbols = new TreeMap<>();
            opening.keySet().forEach(k -> symbols.put(k, true));
            closing.keySet().forEach(k -> symbols.put(k, true));
            for (String sym : symbols.keySet()) {
                ObjectNode p = positions.addObject();
                p.put("symbol", sym);
                p.put("opening", opening.getOrDefault(sym, 0L));
                if (next < 0) {
                    p.putNull("closing");
                } else {
                    p.put("closing", closing.getOrDefault(sym, 0L));
                }
            }
        }
        return body;
    }

    /** GET /api/candles: the date's one-minute candles, aggregated to {@code intervalMinutes}. */
    static ObjectNode candles(Connection c, ObjectMapper mapper, String symbol, int date, int intervalMinutes)
            throws SQLException {
        requireSymbol(c, symbol);
        Context ctx = context(c, date);
        List<CandleIntervals.Candle> minutes = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(CANDLES)) {
            s.setString(1, symbol);
            s.setInt(2, date);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    minutes.add(new CandleIntervals.Candle(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4),
                            r.getLong(5), r.getLong(6), r.getLong(7)));
                }
            }
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("symbol", symbol);
        body.put("businessDate", date);
        body.put("dayStatus", ctx.dayStatus().name());
        body.put("intervalMinutes", intervalMinutes);
        body.put("asOfOutboundSeq", ctx.asOfOutboundSeq());
        ArrayNode candles = body.putArray("candles");
        for (CandleIntervals.Candle k : CandleIntervals.aggregate(minutes, intervalMinutes)) {
            ObjectNode n = candles.addObject();
            n.put("bucketStartTs", k.bucketStartTs());
            n.put("open", k.open());
            n.put("high", k.high());
            n.put("low", k.low());
            n.put("close", k.close());
            n.put("volume", k.volume());
            n.put("tradeCount", k.tradeCount());
        }
        return body;
    }

    private static void leg(ObjectNode n, ResultSet r, String role, String side) throws SQLException {
        n.put("outboundSeq", r.getLong(1));
        n.put("tradeId", r.getLong(2));
        n.put("timestamp", r.getLong(3));
        n.put("symbol", r.getString(4));
        n.put("role", role);
        n.put("side", side);
        n.put("price", r.getLong(6));
        n.put("quantity", r.getLong(7));
    }

    private static ObjectNode header(ObjectMapper mapper, int date, Context ctx) {
        ObjectNode body = mapper.createObjectNode();
        body.put("businessDate", date);
        body.put("dayStatus", ctx.dayStatus().name());
        body.put("asOfOutboundSeq", ctx.asOfOutboundSeq());
        return body;
    }

    private static void putNext(ObjectNode body, boolean more, long lastSeq) {
        if (more) {
            body.put("nextAfterSeq", lastSeq);
        } else {
            body.putNull("nextAfterSeq");
        }
    }

    private static void requireSymbol(Connection c, String symbol) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(SYMBOL_EXISTS)) {
            s.setString(1, symbol);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw ApiException.notFound();
            }
        }
    }

    private static int nextBusinessDate(Connection c, int date) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(RUN)) {
            s.setInt(1, date);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SQLException("no clearing run for a CLEARED date " + date);
                return r.getInt(5);
            }
        }
    }

    private static Long singleLong(Connection c, String sql, long id, int date) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, id);
            s.setInt(2, date);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getLong(1) : null;
            }
        }
    }

    /** symbol to quantity; {@code accountId} below 0 means the query takes only the date. */
    private static Map<String, Long> quantities(Connection c, String sql, long accountId, int date) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            if (accountId >= 0) s.setLong(i++, accountId);
            s.setInt(i, date);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.put(r.getString(1), r.getLong(2));
            }
        }
        return out;
    }

    private static void putLong(ObjectNode n, String field, ResultSet r, int column) throws SQLException {
        long v = r.getLong(column);
        if (r.wasNull()) {
            n.putNull(field);
        } else {
            n.put(field, v);
        }
    }

    private static void putInt(ObjectNode n, String field, ResultSet r, int column) throws SQLException {
        int v = r.getInt(column);
        if (r.wasNull()) {
            n.putNull(field);
        } else {
            n.put(field, v);
        }
    }
}
