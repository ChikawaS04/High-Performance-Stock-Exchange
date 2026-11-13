package participant;

import model.Side;
import model.TimeInForce;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Aggressor that follows the trend. It keeps a fast and a slow moving average of trade prices
 * ({@link PriceStats}); while the fast one is above the slow one by at least
 * {@code signalTicks} it is in an up regime and buys, below by the same margin it sells, and in
 * between it is flat and unwinds whatever position it holds.
 *
 * <p>Each order is an IOC priced {@code throughTicks} beyond the opposite best, so it takes
 * liquidity, can sweep a few levels, and never rests. Orders go out at a jittered interval, so the
 * bot reads as a steady stream of aggressive prints in one direction rather than a metronome.
 *
 * <p>The averages start at the open, so the bot does nothing until the market has traded
 * {@code warmupTrades} times and the averages have separated.
 */
public final class TrendFollowerStrategy implements Strategy {

    public record Config(
            long openUnits,
            double fastHalfLife,      // in trades
            double slowHalfLife,      // in trades
            double signalTicks,       // minimum fast-slow gap, in ticks, to call a trend
            long warmupTrades,
            long qty,
            int throughTicks,
            long minIntervalMillis,
            long maxIntervalMillis,
            RiskLimits risk) {

        public static Config defaults(long openUnits) {
            return new Config(openUnits, 8, 40, 1.0, 10, 50, 2, 1_500, 3_000,
                    new RiskLimits(openUnits, RiskLimits.DEFAULT_BAND_PERCENT, 1_500));
        }
    }

    public enum Regime { UP, DOWN, FLAT }

    private final Config cfg;
    private final OwnOrders own;
    private final PriceStats stats;
    private final SplittableRandom rnd;

    private long nextActionMillis = Long.MIN_VALUE;

    public TrendFollowerStrategy(Config cfg, Bots.IdSequence ids, SplittableRandom rnd) {
        this.cfg = cfg;
        this.own = new OwnOrders(ids);
        this.stats = new PriceStats(cfg.openUnits(), cfg.fastHalfLife(), cfg.slowHalfLife(), 20);
        this.rnd = rnd;
    }

    @Override public String name() { return "TREND"; }

    @Override public Bots.Owner owner() { return own.ids().owner(); }

    /** The current regime from the averages. */
    public Regime regime() {
        if (stats.trades() < cfg.warmupTrades()) {
            return Regime.FLAT;
        }
        double gapTicks = (stats.fast() - stats.slow()) / model.Prices.TICK;
        if (gapTicks >= cfg.signalTicks()) return Regime.UP;
        if (gapTicks <= -cfg.signalTicks()) return Regime.DOWN;
        return Regime.FLAT;
    }

    @Override
    public List<Action> onTick(MarketView view, long nowMillis) {
        if (nowMillis < nextActionMillis) {
            return List.of();
        }
        Side side;
        long qty = cfg.qty();
        switch (regime()) {
            case UP -> side = Side.BUY;
            case DOWN -> side = Side.SELL;
            default -> {
                long pos = own.position();
                if (pos == 0) {
                    return List.of();
                }
                side = pos > 0 ? Side.SELL : Side.BUY;
                qty = Math.min(qty, Math.abs(pos));
            }
        }
        if (!cfg.risk().allows(side, qty, own.position(), 0)) {
            return List.of();
        }
        long px;
        if (side == Side.BUY) {
            if (view.bestAsk() <= 0) return List.of();
            px = Math.min(view.bestAsk() + Anchors.ticks(cfg.throughTicks()), cfg.risk().highest());
        } else {
            if (view.bestBid() <= 0) return List.of();
            px = Math.max(view.bestBid() - Anchors.ticks(cfg.throughTicks()), cfg.risk().lowest());
        }
        if (!cfg.risk().inBand(px)) {
            return List.of();
        }
        nextActionMillis = nowMillis + Anchors.between(rnd, cfg.minIntervalMillis(), cfg.maxIntervalMillis());
        return List.of(own.sent(Action.newLimit(own.ids().next(), side, TimeInForce.IOC, px, qty)));
    }

    @Override
    public void onExec(ExecReport e) {
        own.onExec(e);
        stats.onExec(e);
    }

    // Test visibility.
    OwnOrders ownForTest() { return own; }
    PriceStats statsForTest() { return stats; }
}
