package participant;

import model.Prices;
import model.Side;
import model.TimeInForce;

import java.util.ArrayList;
import java.util.List;

/**
 * Liquidity provider. Keeps a two-sided GTC limit quote resting around a fair value, re-quoting
 * when fair moves or on a cadence, and leans its quotes against accumulated inventory.
 *
 * <p><b>Fair value is anchored to the market open.</b> Before any trade prints, fair is the
 * open-price anchor passed in at construction (the Alpaca market open the terminal also draws),
 * so the maker gives the venue a real starting level. Once trades print, fair follows the last
 * trade price, so when the taker sweeps the book up or down the maker re-centres onto the new
 * level and keeps quoting around it. That is the point of the open: a known place to start, from
 * which price is pushed up or down.
 *
 * <p>Posts GTC (it wants to rest). Cancels its existing quotes before posting fresh ones, so it
 * never stacks duplicate resting orders. clOrdIds come from its own {@link Bots.IdSequence}, so
 * every quote is attributable to the maker on the wire.
 *
 * <p>State is touched only on the client's single executor thread (see {@link ParticipantClient}),
 * so no synchronization is needed.
 */
public final class MarketMakerStrategy implements Strategy {

    /** Tunables; all tick/qty counts, not raw units, except {@code openUnits}. */
    public record Config(
            long openUnits,              // market-open anchor, units of $0.0001
            int halfSpreadTicks,         // distance from fair to each quote, in one-cent ticks
            long clipQty,                // size posted on each side
            int requoteThresholdTicks,   // re-centre when fair moves at least this many ticks
            long requoteIntervalMillis,  // force a refresh at least this often
            int skewTicksPerClip,        // shift centre this many ticks per clip of net inventory
            int maxSkewTicks) {          // clamp on the inventory skew
    }

    private final Config cfg;
    private final Bots.IdSequence ids;

    // Resting state (executor thread only).
    private long bidId;      // 0 = no resting bid
    private long askId;      // 0 = no resting ask
    private long bidRemaining;
    private long askRemaining;
    private long lastCentre = Prices.NA;
    private long lastQuoteMillis = Long.MIN_VALUE;
    private long inventory;   // net position, + = long

    public MarketMakerStrategy(Config cfg, Bots.IdSequence ids) {
        this.cfg = cfg;
        this.ids = ids;
    }

    @Override public String name() { return "MAKER"; }

    @Override public long rangeBase() { return Bots.MAKER_BASE; }

    @Override
    public List<Action> onTick(MarketView view, long nowMillis) {
        long fair = view.hasTraded() ? view.lastTradeUnits() : cfg.openUnits();
        if (fair <= 0) {
            return List.of();   // nothing to anchor to yet
        }
        long centre = roundToTick(fair - skewUnits());

        boolean missing = (bidId == 0 || askId == 0);
        boolean moved = lastCentre > 0 && Math.abs(centre - lastCentre) >= (long) cfg.requoteThresholdTicks() * Prices.TICK;
        boolean stale = nowMillis - lastQuoteMillis >= cfg.requoteIntervalMillis();
        if (!missing && !moved && !stale) {
            return List.of();
        }

        long half = (long) cfg.halfSpreadTicks() * Prices.TICK;
        long bidPx = roundToTick(centre - half);
        long askPx = roundToTick(centre + half);
        if (bidPx <= 0) {
            return List.of();   // degenerate; skip this tick
        }

        List<Action> out = new ArrayList<>(4);
        if (bidId != 0) out.add(Action.cancel(ids.next(), bidId));
        if (askId != 0) out.add(Action.cancel(ids.next(), askId));

        bidId = ids.next();
        askId = ids.next();
        bidRemaining = cfg.clipQty();
        askRemaining = cfg.clipQty();
        out.add(Action.newLimit(bidId, Side.BUY, TimeInForce.GTC, bidPx, cfg.clipQty()));
        out.add(Action.newLimit(askId, Side.SELL, TimeInForce.GTC, askPx, cfg.clipQty()));

        lastCentre = centre;
        lastQuoteMillis = nowMillis;
        return out;
    }

    @Override
    public void onExec(ExecReport e) {
        if (!ParticipantClient.isFill(e.execType())) {
            return;
        }
        // The maker only ever rests, so its fills show up as the passive side of a trade.
        if (e.passiveOrderId() == bidId && bidId != 0) {
            inventory += e.filledQuantity();          // bought
            bidRemaining -= e.filledQuantity();
            if (bidRemaining <= 0) bidId = 0;          // fully consumed -> repost next tick
        } else if (e.passiveOrderId() == askId && askId != 0) {
            inventory -= e.filledQuantity();          // sold
            askRemaining -= e.filledQuantity();
            if (askRemaining <= 0) askId = 0;
        }
    }

    /** Net inventory, in price units, translated to a centre shift and clamped. */
    private long skewUnits() {
        if (cfg.skewTicksPerClip() == 0 || cfg.clipQty() <= 0) {
            return 0L;
        }
        long clips = inventory / cfg.clipQty();
        long ticks = clips * cfg.skewTicksPerClip();
        long clamp = cfg.maxSkewTicks();
        if (ticks > clamp) ticks = clamp;
        if (ticks < -clamp) ticks = -clamp;
        return ticks * Prices.TICK;
    }

    private static long roundToTick(long units) {
        long r = units % Prices.TICK;
        return (r == 0) ? units : units - r;   // floor to the one-cent tick
    }

    // Test visibility into resting state.
    long inventoryForTest() { return inventory; }
    long bidIdForTest() { return bidId; }
    long askIdForTest() { return askId; }
}
