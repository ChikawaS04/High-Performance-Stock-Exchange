package participant;

import model.Prices;
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
 * <p><b>Depth.</b> The default ladder is 20 levels a side (the server's BOOK depth), sized up
 * outward from the touch. The position limit covers a whole side of it plus 3,000, so the full
 * ladder rests on both sides while the inventory is within 3,000 of flat; past that, the outer
 * levels on the side that would add inventory drop first.
 *
 * <p><b>Anchor and throttle (P16-5).</b> For unattended running the centre is also pulled toward
 * an anchor (the open at start, a new day's open after a session open) by a bounded number of
 * ticks, so the book leans back toward the open instead of drifting without bound; and a full
 * requote waits at least {@code minRequoteGapMillis} after the last one. At the inventory limit
 * the side that would add inventory loses its outer levels first and then all of them, the
 * reducing side keeps quoting, and the centre is skewed by up to {@code maxSkewTicks}. Both are
 * off in the development defaults.
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
            RiskLimits risk,
            long minRequoteGapMillis,    // at most one full requote per gap (P16-5); 0 = no gap
            int anchorMaxPullTicks,      // bound on the pull toward the anchor, in ticks; 0 = off
            int anchorPullDivisor) {     // ticks of distance from the anchor per tick of pull

        public Config {
            if (levelQtys.length == 0) {
                throw new IllegalArgumentException("the ladder needs at least one level");
            }
            if (minRequoteGapMillis < 0) {
                throw new IllegalArgumentException("min requote gap must be >= 0: " + minRequoteGapMillis);
            }
            if (anchorMaxPullTicks < 0) {
                throw new IllegalArgumentException("anchor max pull must be >= 0: " + anchorMaxPullTicks);
            }
            if (anchorPullDivisor < 1) {
                throw new IllegalArgumentException("anchor pull divisor must be >= 1: " + anchorPullDivisor);
            }
        }

        /** The pre-P16-5 shape: no requote gap and no anchor pull (today's behaviour). */
        public Config(long openUnits, int baseHalfSpreadTicks, long[] levelQtys, double volMultiplier,
                      int requoteMoveTicks, long requoteIntervalMillis, long skewClipQty, int maxSkewTicks,
                      RiskLimits risk) {
            this(openUnits, baseHalfSpreadTicks, levelQtys, volMultiplier, requoteMoveTicks,
                    requoteIntervalMillis, skewClipQty, maxSkewTicks, risk, 0L, 0, 1);
        }

        /** Levels per side in the default ladder: the server's BOOK prefix depth (MAX_DEPTH_LEVELS). */
        public static final int DEFAULT_LEVELS = 20;

        /**
         * Inventory range, either side of flat, in which the full default ladder still rests on
         * both sides. It is the pre-deepening position limit, kept as the range in which the book
         * stays full.
         */
        public static final long FULL_LADDER_INVENTORY = 3_000;

        public static Config defaults(long openUnits) {
            long[] qtys = defaultLevelQtys();
            return new Config(openUnits, 2, qtys, 1.0, 2, 10_000, 300, 10,
                    new RiskLimits(openUnits, RiskLimits.DEFAULT_BAND_PERCENT, defaultMaxPosition(qtys)));
        }

        /**
         * The default ladder: 100, 200, ..., 1000 over the first ten levels, then 1000 for the
         * remaining ten, so size steps up outward from the touch and then flattens. 15,500 a side.
         */
        static long[] defaultLevelQtys() {
            return levelQtys(DEFAULT_LEVELS);
        }

        /** The first {@code levels} levels of the same shape (100, 200, ... capped at 1000). */
        static long[] levelQtys(int levels) {
            if (levels < 1) {
                throw new IllegalArgumentException("levels must be >= 1: " + levels);
            }
            long[] qtys = new long[levels];
            for (int i = 0; i < levels; i++) {
                qtys[i] = Math.min(100L * (i + 1), 1_000L);
            }
            return qtys;
        }

        /**
         * RiskLimits counts the ladder's working quantity on a side as if it filled, so the limit
         * must cover a whole side plus the inventory range: the sum of the ladder plus
         * {@link #FULL_LADDER_INVENTORY} (18,500 for the default ladder).
         */
        static long defaultMaxPosition(long[] qtys) {
            long side = 0;
            for (long q : qtys) {
                side += q;
            }
            return side + FULL_LADDER_INVENTORY;
        }
    }

    private final Config cfg;
    private final OwnOrders own;
    private final PriceStats stats;

    private long reference;          // last on-tick trade, the open before the first print
    private long anchor;             // the pull target: the open at start, a new day's open later
    private long lastCentre = -1;
    private long lastHalf = -1;
    private long lastQuoteMillis = Long.MIN_VALUE / 2;

    public MarketMakerStrategy(Config cfg, Bots.IdSequence ids) {
        this.cfg = cfg;
        this.own = new OwnOrders(ids);
        this.stats = new PriceStats(cfg.openUnits(), 8, 40, 20);
        this.reference = cfg.openUnits();
        this.anchor = cfg.openUnits();
    }

    @Override public String name() { return "MAKER"; }

    @Override public Bots.Owner owner() { return own.ids().owner(); }

    @Override
    public List<Action> onTick(MarketView view, long nowMillis) {
        reference = Anchors.reference(view, reference);
        long centre = reference + Anchors.ticks(pullTicks()) - Anchors.ticks(skewTicks());
        long half = cfg.baseHalfSpreadTicks() + Math.round(cfg.volMultiplier() * stats.volTicks());

        boolean empty = own.working().isEmpty();
        boolean moved = lastCentre > 0 && Math.abs(centre - lastCentre) >= Anchors.ticks(cfg.requoteMoveTicks());
        boolean widened = half != lastHalf;
        boolean stale = nowMillis - lastQuoteMillis >= cfg.requoteIntervalMillis();
        if (!empty && !moved && !widened && !stale) {
            return List.of();
        }
        // P16-5 throttle: whatever triggered it, a full requote waits out the gap (deferred, not dropped).
        if (nowMillis - lastQuoteMillis < cfg.minRequoteGapMillis()) {
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

    /**
     * After a reconnect's catch-up: cancel every order whose fate is uncertain, the unacknowledged ones and
     * the ones whose cancel was in flight when the connection dropped. A requote's {@code cancelAll} skips an
     * order with a cancel pending, so without this a cancel lost at a venue restart would leave that quote
     * resting at its old price until the session closes.
     */
    @Override
    public List<Action> onResync() {
        return own.cancelUncertain();
    }

    /** A new day's open (P16-5): becomes the pull target. Off-tick or non-positive values are ignored. */
    @Override
    public void onAnchor(long openUnits) {
        if (Prices.isOnTick(openUnits)) {
            anchor = openUnits;
        }
    }

    /** The current pull target. */
    long anchor() {
        return anchor;
    }

    /**
     * The centre's pull toward the anchor in ticks (P16-5), signed: positive when the anchor is
     * above the reference. One tick of pull per {@code anchorPullDivisor} ticks of distance,
     * clamped to {@code anchorMaxPullTicks}; 0 when the pull is off. Integer arithmetic on two
     * on-tick prices, so the centre stays on the tick.
     */
    long pullTicks() {
        int max = cfg.anchorMaxPullTicks();
        if (max <= 0) {
            return 0;
        }
        long distanceTicks = (anchor - reference) / Prices.TICK;
        long pull = distanceTicks / cfg.anchorPullDivisor();
        return Math.max(-max, Math.min(max, pull));
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
