package participant;

import model.Side;
import model.TimeInForce;

import java.util.ArrayList;
import java.util.List;

/**
 * Test strategy: rests {@code count} GTC bids of {@code qty} (one per tick) and keeps its book in
 * an {@link OwnOrders}, like the real resting strategies, so resync and stop behave as theirs do.
 */
final class RestingStrategy implements Strategy {

    final OwnOrders own = new OwnOrders(new Bots.IdSequence(Bots.Owner.NOISE));
    final List<ExecReport> execs = new ArrayList<>();
    private final int count;
    private final long qty;
    private final long price;
    private int posted;

    RestingStrategy(int count, long qty, long price) {
        this.count = count;
        this.qty = qty;
        this.price = price;
    }

    @Override public String name() { return "REST"; }

    @Override public Bots.Owner owner() { return Bots.Owner.NOISE; }

    @Override
    public List<Action> onTick(MarketView view, long nowMillis) {
        if (posted >= count) {
            return List.of();
        }
        posted++;
        return List.of(own.sent(Action.newLimit(own.ids().next(), Side.BUY, TimeInForce.GTC, price, qty)));
    }

    @Override
    public void onExec(ExecReport e) {
        execs.add(e);
        own.onExec(e);
    }

    @Override
    public List<Action> onSessionClose() {
        return own.cancelEverything();
    }

    @Override
    public boolean hasResting() {
        return !own.resting().isEmpty();
    }

    @Override
    public List<Action> onResync() {
        return own.cancelUncertain();
    }

    List<Long> restingIds() {
        List<Long> ids = new ArrayList<>();
        for (OwnOrders.Resting r : own.resting()) ids.add(r.id);
        return ids;
    }
}
