package participant;

import model.Side;
import model.TimeInForce;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Retail-style two-way flow. Orders arrive at random (exponential gaps, so a Poisson stream), on a
 * random side, for a random small size. Most rest passively up to {@code maxBehindTicks} behind the
 * touch; the rest are IOC orders at the opposite best that take liquidity. Now and then it cancels
 * one of its resting orders, and it never keeps more than {@code maxResting} working.
 *
 * <p>When it is near its position limit it leans its side choice toward flattening, so it stays a
 * noise source rather than drifting into one direction. Every price is the touch (or the reference
 * price on an empty side) plus or minus whole ticks, inside the risk band.
 */
public final class NoiseTraderStrategy implements Strategy {

    public record Config(
            long openUnits,
            long meanArrivalMillis,
            long minQty,
            long maxQty,
            long qtyStep,
            double passiveProbability,
            double cancelProbability,
            int maxResting,
            int maxBehindTicks,
            RiskLimits risk) {

        public static Config defaults(long openUnits) {
            return new Config(openUnits, 1_200, 10, 100, 10, 0.6, 0.15, 10, 3,
                    new RiskLimits(openUnits, RiskLimits.DEFAULT_BAND_PERCENT, 1_000));
        }
    }

    private final Config cfg;
    private final OwnOrders own;
    private final SplittableRandom rnd;

    private long nextArrivalMillis = Long.MIN_VALUE;

    public NoiseTraderStrategy(Config cfg, Bots.IdSequence ids, SplittableRandom rnd) {
        this.cfg = cfg;
        this.own = new OwnOrders(ids);
        this.rnd = rnd;
    }

    @Override public String name() { return "NOISE"; }

    @Override public Bots.Owner owner() { return own.ids().owner(); }

    @Override
    public List<Action> onTick(MarketView view, long nowMillis) {
        if (nextArrivalMillis == Long.MIN_VALUE) {
            nextArrivalMillis = nowMillis + gap();
            return List.of();
        }
        if (nowMillis < nextArrivalMillis) {
            return List.of();
        }
        nextArrivalMillis = nowMillis + gap();

        List<OwnOrders.Resting> working = own.working();
        if (!working.isEmpty() && rnd.nextDouble() < cfg.cancelProbability()) {
            Action c = own.cancel(working.get(rnd.nextInt(working.size())).id);
            return c == null ? List.of() : List.of(c);
        }

        Side side = pickSide();
        long qty = Anchors.between(rnd, cfg.minQty() / cfg.qtyStep(), cfg.maxQty() / cfg.qtyStep()) * cfg.qtyStep();
        boolean passive = rnd.nextDouble() < cfg.passiveProbability();
        long opposite = side == Side.BUY ? view.bestAsk() : view.bestBid();
        if (!passive && opposite > 0) {
            if (!cfg.risk().inBand(opposite) || !cfg.risk().allows(side, qty, own.position(), 0)) {
                return List.of();
            }
            return List.of(own.sent(Action.newLimit(own.ids().next(), side, TimeInForce.IOC, opposite, qty)));
        }

        if (working.size() >= cfg.maxResting()) {
            Action c = own.cancel(working.get(0).id);   // oldest makes room
            return c == null ? List.of() : List.of(c);
        }
        long behind = Anchors.ticks(Anchors.between(rnd, 0, cfg.maxBehindTicks()));
        long px;
        if (side == Side.BUY) {
            long base = view.bestBid() > 0 ? view.bestBid()
                    : Anchors.reference(view, cfg.openUnits()) - Anchors.ticks(1);
            px = base - behind;
        } else {
            long base = view.bestAsk() > 0 ? view.bestAsk()
                    : Anchors.reference(view, cfg.openUnits()) + Anchors.ticks(1);
            px = base + behind;
        }
        if (!cfg.risk().inBand(px) || !cfg.risk().allows(side, qty, own.position(), own.workingQty(side))) {
            return List.of();
        }
        return List.of(own.sent(Action.newLimit(own.ids().next(), side, TimeInForce.GTC, px, qty)));
    }

    /** Buy or sell at random, leaning toward flattening as the position nears its limit. */
    private Side pickSide() {
        double lean = (double) own.position() / cfg.risk().maxPosition();   // -1 .. 1
        double buyProbability = 0.5 - 0.4 * Math.max(-1.0, Math.min(1.0, lean));
        return rnd.nextDouble() < buyProbability ? Side.BUY : Side.SELL;
    }

    private long gap() {
        double u = 1.0 - rnd.nextDouble();   // (0, 1], so the log is finite
        return Math.max(1L, (long) (-Math.log(u) * cfg.meanArrivalMillis()));
    }

    @Override
    public void onExec(ExecReport e) {
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

    // Test visibility.
    OwnOrders ownForTest() { return own; }
}
