package participant;

import model.Side;
import model.TimeInForce;

import java.util.ArrayList;
import java.util.List;

/**
 * The stabilizer. It holds a slow moving average of trade prices, seeded at the open, as its idea
 * of fair value. When the last trade runs {@code entryTicks} or more away from fair, it rests a
 * GTC order against the move one tick inside the touch (a bid just above the best bid when price
 * has fallen, an offer just below the best ask when it has risen), sized up the further price has
 * gone. When price comes back within {@code exitTicks} of fair it withdraws the fade and rests a
 * closing order at the touch to flatten what it bought or sold.
 *
 * <p>It keeps at most one working order, and replaces it when the wanted side, price or size
 * changes, or on a cadence. Prices are the touch plus or minus whole ticks, inside the risk band.
 */
public final class MeanReversionStrategy implements Strategy {

    public record Config(
            long openUnits,
            double fairHalfLife,     // in trades
            int entryTicks,
            int exitTicks,
            long qtyPerStep,         // size per entryTicks of distance
            long maxStepQty,         // cap on one fade
            long requoteIntervalMillis,
            RiskLimits risk) {

        public static Config defaults(long openUnits) {
            return new Config(openUnits, 200, 6, 2, 100, 400, 4_000,
                    new RiskLimits(openUnits, RiskLimits.DEFAULT_BAND_PERCENT, 2_000));
        }
    }

    /** What the bot wants resting: side, price and size. */
    record Want(Side side, long price, long qty) { }

    private final Config cfg;
    private final OwnOrders own;
    private final PriceStats stats;

    private long lastQuoteMillis = Long.MIN_VALUE / 2;

    public MeanReversionStrategy(Config cfg, Bots.IdSequence ids) {
        this.cfg = cfg;
        this.own = new OwnOrders(ids);
        this.stats = new PriceStats(cfg.openUnits(), 4, cfg.fairHalfLife(), 20);
    }

    @Override public String name() { return "REVERT"; }

    @Override public Bots.Owner owner() { return own.ids().owner(); }

    /** Distance of the last trade from fair, in ticks (positive when price is above fair). */
    double deviationTicks() {
        if (stats.lastTrade() <= 0) {
            return 0;
        }
        return (stats.lastTrade() - stats.slow()) / model.Prices.TICK;
    }

    Want want(MarketView view) {
        double dev = deviationTicks();
        long pos = own.position();
        if (Math.abs(dev) >= cfg.entryTicks()) {
            Side side = dev > 0 ? Side.SELL : Side.BUY;
            long steps = (long) (Math.abs(dev) / cfg.entryTicks());
            long qty = Math.min(cfg.maxStepQty(), steps * cfg.qtyPerStep());
            Long px = insideTouch(view, side);
            return px == null ? null : new Want(side, px, qty);
        }
        if (Math.abs(dev) <= cfg.exitTicks() && pos != 0) {
            Side side = pos > 0 ? Side.SELL : Side.BUY;
            long qty = Math.min(cfg.maxStepQty(), Math.abs(pos));
            Long px = atTouch(view, side);
            return px == null ? null : new Want(side, px, qty);
        }
        return null;
    }

    @Override
    public List<Action> onTick(MarketView view, long nowMillis) {
        Want want = want(view);
        if (want != null && (!cfg.risk().inBand(want.price())
                || !cfg.risk().allows(want.side(), want.qty(), own.position(), 0))) {
            want = null;
        }
        List<OwnOrders.Resting> working = own.working();
        OwnOrders.Resting current = working.isEmpty() ? null : working.get(0);
        boolean stale = nowMillis - lastQuoteMillis >= cfg.requoteIntervalMillis();
        if (current != null && want != null && current.side == want.side() && current.price == want.price()
                && current.remaining() == want.qty() && !stale) {
            return List.of();
        }
        if (current == null && want == null) {
            return List.of();
        }
        if (!own.resting().isEmpty() && current == null) {
            return List.of();   // a cancel is still in flight; wait for it before posting again
        }
        List<Action> out = new ArrayList<>(own.cancelAll());
        if (want != null) {
            out.add(own.sent(Action.newLimit(own.ids().next(), want.side(), TimeInForce.GTC, want.price(), want.qty())));
        }
        lastQuoteMillis = nowMillis;
        return out;
    }

    /** One tick inside the touch on {@code side}'s own side, or joining it when the spread is one tick. */
    private static Long insideTouch(MarketView v, Side side) {
        if (v.bestBid() <= 0 || v.bestAsk() <= 0) {
            return atTouch(v, side);
        }
        if (side == Side.BUY) {
            long px = v.bestBid() + Anchors.ticks(1);
            return px < v.bestAsk() ? px : v.bestBid();
        }
        long px = v.bestAsk() - Anchors.ticks(1);
        return px > v.bestBid() ? px : v.bestAsk();
    }

    /** The best price on {@code side}'s own side, falling back to the far side less one tick. */
    private static Long atTouch(MarketView v, Side side) {
        if (side == Side.BUY) {
            if (v.bestBid() > 0) return v.bestBid();
            if (v.bestAsk() > 0) return v.bestAsk() - Anchors.ticks(1);
        } else {
            if (v.bestAsk() > 0) return v.bestAsk();
            if (v.bestBid() > 0) return v.bestBid() + Anchors.ticks(1);
        }
        return null;
    }

    @Override
    public void onExec(ExecReport e) {
        own.onExec(e);
        stats.onExec(e);
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
