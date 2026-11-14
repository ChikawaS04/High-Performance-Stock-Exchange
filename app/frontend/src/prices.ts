/**
 * Price constants and the one pixel-to-price snap (P18-4). Pure.
 *
 * Prices are integer units of $0.0001 end to end (backend model.Prices: SCALE =
 * 10_000, TICK = 100). A limit price must be on the one-cent tick, and nothing ever
 * rounds an order's price. snapToTick does not break that rule: it never takes an
 * order price as input. It turns a pointer position, read as a price, into the
 * nearest on-tick price for a drag to reprice, which is choosing a price, not
 * rounding one.
 */

/** One cent, in units of $0.0001: the on-tick spacing every limit price keeps. */
export const TICK_PX = 100;

/**
 * The on-tick price nearest a pointer price, never below one tick. A half-tick
 * position rounds up (Math.round), so the snap is the same for every pointer.
 */
export function snapToTick(pointerPx: number): number {
    if (!Number.isFinite(pointerPx)) return TICK_PX;
    return Math.max(TICK_PX, Math.round(pointerPx / TICK_PX) * TICK_PX);
}

/**
 * The venue mid in units, or null when either side of the lit book is empty. The
 * exact integer mean, as MatchingEngine.mid() computes it: both tops are on the
 * tick, so it is a multiple of 50 units and may sit on a half cent. Never rounded
 * to the tick: it is a reference price, not an order price.
 */
export function venueMid(bestBid: number, bestAsk: number): number | null {
    if (bestBid <= 0 || bestAsk <= 0) return null;
    return Math.floor((bestBid + bestAsk) / 2);
}

/** True when a price is a positive whole number of ticks. */
export function isOnTick(px: number): boolean {
    return Number.isSafeInteger(px) && px > 0 && px % TICK_PX === 0;
}
