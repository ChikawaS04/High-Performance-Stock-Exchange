package participant;

import event.ExecutionEventType;
import model.Prices;
import model.Side;

import java.util.List;

/** Shared builders for the participant tests: views, EXEC reports and action filters. */
final class Fixtures {

    private Fixtures() { }

    static final long OPEN = 1_500_000L;   // $150.00

    static long ticks(long n) {
        return n * Prices.TICK;
    }

    static MarketView view(long bid, long ask, long last) {
        long mid = (bid > 0 && ask > 0) ? (bid + ask) / 2 : Prices.NA;
        return new MarketView(bid, ask, mid, last);
    }

    static MarketView emptyView() {
        return MarketView.empty();
    }

    /** A fill: aggressor {@code agg} (side {@code aggSide}) trades {@code qty} against resting {@code pas}. */
    static ExecReport fill(long agg, long pas, Side aggSide, long price, long qty, long aggRemaining) {
        ExecutionEventType t = aggRemaining == 0 ? ExecutionEventType.ORDER_FILLED
                : ExecutionEventType.ORDER_PARTIALLY_FILLED;
        return new ExecReport(t, agg, 1L, price, qty, aggRemaining, agg, pas, 0L, aggSide);
    }

    /** A trade between two outsiders, used to move a bot's price signals. */
    static ExecReport print(long price) {
        return fill(5L, 6L, Side.BUY, price, 10, 0);
    }

    static ExecReport accepted(long id, Side side, long price, long remaining) {
        return new ExecReport(ExecutionEventType.ORDER_ACCEPTED, id, -1, price, -1, remaining, -1, -1, 0L, side);
    }

    static ExecReport cancelled(long id) {
        return new ExecReport(ExecutionEventType.ORDER_CANCELLED, id, -1, -1, -1, -1, -1, -1, 0L, null);
    }

    static List<Action> news(List<Action> actions) {
        return actions.stream().filter(a -> a.kind() == Action.Kind.NEW).toList();
    }

    static List<Action> cancels(List<Action> actions) {
        return actions.stream().filter(a -> a.kind() == Action.Kind.CANCEL).toList();
    }

    static List<Action> side(List<Action> actions, Side side) {
        return news(actions).stream().filter(a -> a.side() == side).toList();
    }
}
