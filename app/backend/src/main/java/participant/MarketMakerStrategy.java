package participant;

import model.Side;
import model.TimeInForce;

import java.util.ArrayList;
import java.util.List;

/**
 * Liquidity provider. Keeps a ladder of GTC quotes resting on both sides of a centre price, so
 * the book and the depth chart have shape, and refreshes the ladder when the centre moves, when
 * the market gets more volatile, or on a cadence.
 *
 * <p><b>Centre.</b> Before the first print the centre is the open (the Alpaca market open the
 * terminal also draws), which gives the venue its starting level. After that it follows the last
 * on-tick trade, so when other bots push price the maker re-centres on the new level. It never
 * centres on the book, which holds its own quotes. The centre is then
 * shifted against inventory: a long maker lowers both sides to sell more and buy less.
 *
 * <p><b>Spread.</b> Level {@code i} (0-based) sits {@code half + i} ticks from the centre on each
 * side, where {@code half = baseHalfSpreadTicks + volMultiplier * volatility}, the volatility being
 * the typical tick move between trades. A choppy market widens the maker out.
 *
 * <p>Every price is the on-tick centre plus a whole number of ticks. A quote outside the risk band,
 * or one that would take the position past its limit, is not sent.
 */
public final class MarketMakerStrategy implements Strategy {

    public record Config(
            long openUnits,
            int baseHalfSpreadTicks,     // distance from centre to the first level
            long[] levelQtys,            // size per level, inner first; its length is the depth
            double volMultiplier,        // extra half-spread ticks per tick of volatility
            int requoteMoveTicks,        // re-centre when the centre moves this many ticks
            long requoteIntervalMillis,  // refresh at least this often
            long skewClipQty,            // inventory per tick of centre skew
            int maxSkewTicks,
            RiskLimits risk) {

        public static Config defaults(long openUnits) {
            return new Config(openUnits, 2, new long[] {100, 200, 300}, 1.0, 2, 10_000, 300, 10,
                    new RiskLimits(openUnits, RiskLimits.DEFAULT_BAND_PERCENT, 3_000));
        }
    }

    private final Config cfg;
    private final OwnOrders own;
    private final PriceStats stats;

    private long reference;          // last on-tick trade, the open before the first print
    private long lastCentre = -1;
    private long lastHalf = -1;
    private long lastQuoteMillis = Long.MIN_VALUE / 2;

    public MarketMakerStrategy(Config cfg, Bots.IdSequence ids) {
        this.cfg = cfg;
        this.own = new OwnOrders(ids);
        this.stats = new PriceStats(cfg.openUnits(), 8, 40, 20);
        this.reference = cfg.openUnits();
    }

    @Override public String name() { return "MAKER"; }

    @Override public Bots.Owner owner() { return own.ids().owner(); }

    @Override
    public List<Action> onTick(MarketView view, long nowMillis) {
        reference = Anchors.reference(view, reference);
        long centre = reference - Anchors.ticks(skewTicks());
        long half = cfg.baseHalfSpreadTicks() + Math.round(cfg.volMultiplier() * stats.volTicks());

        boolean empty = own.working().isEmpty();
        boolean moved = lastCentre > 0 && Math.abs(centre - lastCentre) >= Anchors.ticks(cfg.requoteMoveTicks());
        boolean widened = half != lastHalf;
        boolean stale = nowMillis - lastQuoteMillis >= cfg.requoteIntervalMillis();
        if (!empty && !moved && !widened && !stale) {
            return List.of();
        }

        List<Action> out = new ArrayList<>(own.cancelAll());
        long bidWorking = 0;
        long askWorking = 0;
        long[] qtys = cfg.levelQtys();
        for (int i = 0; i < qtys.length; i++) {
            long bidPx = centre - Anchors.ticks(half + i);
            long askPx = centre + Anchors.ticks(half + i);
            if (cfg.risk().inBand(bidPx) && cfg.risk().allows(Side.BUY, qtys[i], own.position(), bidWorking)) {
                out.add(own.sent(Action.newLimit(own.ids().next(), Side.BUY, TimeInForce.GTC, bidPx, qtys[i])));
                bidWorking += qtys[i];
            }
            if (cfg.risk().inBand(askPx) && cfg.risk().allows(Side.SELL, qtys[i], own.position(), askWorking)) {
                out.add(own.sent(Action.newLimit(own.ids().next(), Side.SELL, TimeInForce.GTC, askPx, qtys[i])));
                askWorking += qtys[i];
            }
        }
        lastCentre = centre;
        lastHalf = half;
        lastQuoteMillis = nowMillis;
        return out;
    }

    @Override
    public void onExec(ExecReport e) {
        own.onExec(e);
        stats.onExec(e);
    }

    @Override
    public List<Action> onSessionClose() {
        lastCentre = -1;
        return own.cancelEverything();
    }

    @Override
    public boolean hasResting() {
        return !own.resting().isEmpty();
    }

    /** Net inventory as a centre shift in ticks, clamped. Positive when long. */
    long skewTicks() {
        if (cfg.skewClipQty() <= 0) {
            return 0;
        }
        long ticks = own.position() / cfg.skewClipQty();
        return Math.max(-cfg.maxSkewTicks(), Math.min(cfg.maxSkewTicks(), ticks));
    }

    // Test visibility.
    OwnOrders ownForTest() { return own; }
}
