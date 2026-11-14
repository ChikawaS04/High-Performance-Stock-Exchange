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

/** True when a price is a positive whole number of ticks. */
export function isOnTick(px: number): boolean {
    return Number.isSafeInteger(px) && px > 0 && px % TICK_PX === 0;
}
