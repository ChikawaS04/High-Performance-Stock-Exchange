package participant;

import model.Prices;
import model.Side;
import model.TimeInForce;

import java.util.List;

/**
 * Aggressor. On a timer it crosses the spread with a marketable limit IOC order in the current
 * direction, eating resting liquidity and walking the last-trade price along the ladder; it flips
 * direction on a schedule so the price path goes up for a leg, then down, giving a clean,
 * attributable move rather than a random jitter.
 *
 * <p>IOC (not GTC): a marketable remainder that cannot fill expires instead of resting, so the
 * taker never leaves orders in the book and stays a pure liquidity taker. The order is priced a
 * configurable number of ticks <i>through</i> the opposing best so it sweeps several levels in one
 * shot. clOrdIds come from its own {@link Bots.IdSequence}, so every sweep is attributable to the
 * taker on the wire (as the aggressor side of each resulting trade).
 *
 * <p>Stateless except for direction and timers; touched only on the client executor thread.
 */
public final class MomentumTakerStrategy implements Strategy {

    public record Config(
            long takeIntervalMillis,   // how often to cross
            long takeQty,              // size per sweep
            int throughTicks,          // cross this many ticks beyond the opposing best
            long flipMillis,           // flip direction this often
            Side startDir) {           // initial direction (BUY pushes price up first)
    }

    private final Config cfg;
    private final Bots.IdSequence ids;

    private Side dir;
    private long lastTakeMillis = 0L;   // 0 reads as "long ago" without the Long.MIN_VALUE subtraction overflow
    private long lastFlipMillis;

    public MomentumTakerStrategy(Config cfg, Bots.IdSequence ids) {
        this.cfg = cfg;
        this.ids = ids;
        this.dir = cfg.startDir();
        this.lastFlipMillis = 0L;
    }

    @Override public String name() { return "TAKER"; }

    @Override public long rangeBase() { return Bots.TAKER_BASE; }

    @Override
    public List<Action> onTick(MarketView view, long nowMillis) {
        // Direction flip is time-based; anchor the first flip window to the first tick.
        if (lastFlipMillis == 0L) {
            lastFlipMillis = nowMillis;
        } else if (nowMillis - lastFlipMillis >= cfg.flipMillis()) {
            dir = (dir == Side.BUY) ? Side.SELL : Side.BUY;
            lastFlipMillis = nowMillis;
        }

        if (nowMillis - lastTakeMillis < cfg.takeIntervalMillis()) {
            return List.of();
        }

        long through = (long) cfg.throughTicks() * Prices.TICK;
        long px;
        if (dir == Side.BUY) {
            if (view.bestAsk() <= 0) {
                return List.of();   // no asks to lift; try again next tick
            }
            px = roundToTick(view.bestAsk() + through);
        } else {
            if (view.bestBid() <= 0) {
                return List.of();   // no bids to hit
            }
            px = roundToTick(view.bestBid() - through);
            if (px <= 0) {
                px = Prices.TICK;   // floor at one cent; still crosses everything down to bestBid
            }
        }

        lastTakeMillis = nowMillis;
        return List.of(Action.newLimit(ids.next(), dir, TimeInForce.IOC, px, cfg.takeQty()));
    }

    private static long roundToTick(long units) {
        long r = units % Prices.TICK;
        return (r == 0) ? units : units - r;
    }

    // Test visibility.
    Side dirForTest() { return dir; }
}
