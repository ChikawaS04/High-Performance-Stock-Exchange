/**
 * The own-execution model (P18 D1). Pure: no React, no socket. One model feeds the
 * chart overlays (fill markers, working order lines) and the Executions tab, so no
 * second ownership test is ever written.
 *
 * Ownership is decided once, in the reducer (isOwnOrder: the id names a row in
 * myOrders, an order this terminal sent). Bot orders and another terminal's orders
 * never reach myOrders, so neither their fills nor their orders appear here. A
 * passive fill of one of our resting orders arrives on the aggressor's EXEC via
 * passiveOrderId; the reducer records it as a "passive" OwnFill (P18-1).
 *
 * - fills: one entry per own fill, newest first, from AppState.myFills (capped at
 *   OWN_FILLS_CAP for the markers; metrics read the exact MyOrder.exec instead).
 * - working: one entry per own order resting at the venue (OPEN or
 *   PARTIALLY_FILLED), newest first. PENDING is left out because the venue has not
 *   accepted it yet, so there is nothing on the book to draw or reprice; IOC and
 *   FOK orders never rest, so they never appear.
 */

import { isCancellable } from "./state/reducer";
import type { AppState, OwnFill } from "./state/reducer";
import type { OrdType, Side } from "./protocol/messages";

export type { OwnFill } from "./state/reducer";

export interface WorkingOrder {
    readonly clOrdId: number;
    readonly side: Side;
    readonly ordType: OrdType;
    /** Limit price in units; null for a midpoint peg, which has no price of its own. */
    readonly limitPx: number | null;
    readonly remainingQty: number;
    /** An iceberg's displayed size; null for an order that is not an iceberg. */
    readonly displayQty: number | null;
}

export interface OwnExecutions {
    readonly fills: readonly OwnFill[];
    readonly working: readonly WorkingOrder[];
}

export function buildOwnExecutions(state: Pick<AppState, "myOrders" | "myFills">): OwnExecutions {
    const working: WorkingOrder[] = [];
    for (const o of state.myOrders) {
        if (!isCancellable(o.status)) continue;
        working.push({
            clOrdId: o.clOrdId,
            side: o.side,
            ordType: o.ordType,
            limitPx: o.ordType === "PEG_MID" ? null : o.pricePx,
            remainingQty: o.remainingQty,
            displayQty: o.displayQty > 0 ? o.displayQty : null,
        });
    }
    return { fills: state.myFills, working };
}
