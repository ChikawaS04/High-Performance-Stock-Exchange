package participant;

import model.Side;
import model.TimeInForce;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Works a large parent order over minutes, the way an institution would. A campaign picks a side
 * and a parent size, then keeps one GTC iceberg (display {@code maxFloor}) working at the best
 * price on its own side, so the book shows a steady bid or offer that keeps reloading. When the
 * touch moves {@code repegTicks} away from the iceberg it cancels and re-posts at the new touch.
 *
 * <p><b>Schedule.</b> The campaign aims to finish within {@code targetMillis}. Once its fills lag
 * that schedule by a third of {@code catchUpFraction} it posts one tick more aggressively (improving the touch when the spread allows),
 * and when it lags by more than {@code catchUpFraction} it also lifts or hits one display clip with
 * an IOC at the opposite best. That is what turns a passive bid into a slow upward drift.
 *
 * <p>A campaign ends when the parent is done or after {@code maxCampaignMillis}; the bot then
 * cancels what is left, pauses, and starts another. Its side choice leans against the position it
 * has built, so successive campaigns do not run away in one direction.
 */
public final class InstitutionalStrategy implements Strategy {

    public record Config(
            long openUnits,
            long minParentQty,
            long maxParentQty,
            long maxFloor,
            int repegTicks,
            long targetMillis,
            double catchUpFraction,
            long maxCampaignMillis,
            long minPauseMillis,
            long maxPauseMillis,
            long firstDelayMillis,
            RiskLimits risk) {

        public static Config defaults(long openUnits) {
            return new Config(openUnits, 10_000, 30_000, 200, 2, 600_000, 0.15, 900_000,
                    30_000, 90_000, 15_000,
                    new RiskLimits(openUnits, RiskLimits.DEFAULT_BAND_PERCENT, 40_000));
        }
    }

    private final Config cfg;
    private final OwnOrders own;
    private final SplittableRandom rnd;

    private Side side;               // null between campaigns
    private long parentQty;
    private long filledQty;
    private long campaignStart;
    private long pausedUntil = Long.MIN_VALUE;
    private long lastTakeMillis = Long.MIN_VALUE / 2;

    public InstitutionalStrategy(Config cfg, Bots.IdSequence ids, SplittableRandom rnd) {
        this.cfg = cfg;
        this.own = new OwnOrders(ids);
        this.rnd = rnd;
    }

    @Override public String name() { return "INST"; }

    @Override public Bots.Owner owner() { return own.ids().owner(); }

    @Override
    public List<Action> onTick(MarketView view, long nowMillis) {
        if (pausedUntil == Long.MIN_VALUE) {
            pausedUntil = nowMillis + cfg.firstDelayMillis();
        }
        if (side == null) {
            if (nowMillis < pausedUntil) {
                return List.of();
            }
            startCampaign(nowMillis);
        }

        long remaining = parentQty - filledQty;
        if (remaining <= 0 || nowMillis - campaignStart >= cfg.maxCampaignMillis()) {
            return endCampaign(nowMillis);
        }

        double elapsed = Math.min(1.0, (double) (nowMillis - campaignStart) / cfg.targetMillis());
        double done = (double) filledQty / parentQty;
        double lag = elapsed - done;

        Long target = target(view, lag > cfg.catchUpFraction() / 3);
        if (target == null || !cfg.risk().inBand(target)) {
            return List.of();
        }

        List<Action> out = new ArrayList<>();
        List<OwnOrders.Resting> working = own.working();
        if (!working.isEmpty()) {
            OwnOrders.Resting ice = working.get(0);
            boolean drifted = Math.abs(ice.price - target) >= Anchors.ticks(cfg.repegTicks());
            boolean oversized = ice.remaining() > remaining + cfg.maxFloor();   // IOC catch-up fills shrank the parent
            if (drifted || oversized) {
                Action c = own.cancel(ice.id);
                if (c != null) out.add(c);
            }
        } else if (own.resting().isEmpty()) {
            if (cfg.risk().allows(side, remaining, own.position(), 0)) {
                out.add(own.sent(Action.newIceberg(own.ids().next(), side, target, remaining,
                        Math.min(cfg.maxFloor(), remaining))));
            }
        }

        // Well behind schedule: take one display clip at the opposite best, at most once a second.
        long opposite = side == Side.BUY ? view.bestAsk() : view.bestBid();
        if (lag > cfg.catchUpFraction() && opposite > 0 && cfg.risk().inBand(opposite)
                && nowMillis - lastTakeMillis >= 1_000) {
            long clip = Math.min(cfg.maxFloor(), remaining);
            if (cfg.risk().allows(side, clip, own.position(), own.workingQty(side))) {
                out.add(own.sent(Action.newLimit(own.ids().next(), side, TimeInForce.IOC, opposite, clip)));
                lastTakeMillis = nowMillis;
            }
        }
        return out;
    }

    /** Join the own-side best; one tick better when behind schedule and the spread has room. */
    private Long target(MarketView v, boolean behind) {
        long bid = v.bestBid();
        long ask = v.bestAsk();
        if (side == Side.BUY) {
            if (bid <= 0) return ask > 0 ? ask - Anchors.ticks(1) : Anchors.reference(v, cfg.openUnits());
            if (behind && ask > 0 && bid + Anchors.ticks(1) < ask) return bid + Anchors.ticks(1);
            return bid;
        }
        if (ask <= 0) return bid > 0 ? bid + Anchors.ticks(1) : Anchors.reference(v, cfg.openUnits());
        if (behind && bid > 0 && ask - Anchors.ticks(1) > bid) return ask - Anchors.ticks(1);
        return ask;
    }

    private void startCampaign(long nowMillis) {
        double lean = (double) own.position() / cfg.risk().maxPosition();
        double buyProbability = 0.5 - 0.45 * Math.max(-1.0, Math.min(1.0, lean));
        side = rnd.nextDouble() < buyProbability ? Side.BUY : Side.SELL;
        parentQty = Anchors.between(rnd, cfg.minParentQty() / 100, cfg.maxParentQty() / 100) * 100;
        filledQty = 0;
        campaignStart = nowMillis;
    }

    private List<Action> endCampaign(long nowMillis) {
        side = null;
        pausedUntil = nowMillis + Anchors.between(rnd, cfg.minPauseMillis(), cfg.maxPauseMillis());
        return own.cancelAll();
    }

    @Override
    public void onExec(ExecReport e) {
        long before = own.position();
        own.onExec(e);
        long moved = own.position() - before;
        if (side == Side.BUY && moved > 0) filledQty += moved;
        if (side == Side.SELL && moved < 0) filledQty -= moved;
    }

    @Override
    public List<Action> onSessionClose() {
        side = null;
        pausedUntil = Long.MIN_VALUE;
        return own.cancelEverything();
    }

    @Override
    public boolean hasResting() {
        return !own.resting().isEmpty();
    }

    // Test visibility.
    OwnOrders ownForTest() { return own; }
    Side sideForTest() { return side; }
    long parentForTest() { return parentQty; }
    long filledForTest() { return filledQty; }
}
