/**
 * Drag to reprice, the gesture (P18 D4). Pure: no React, no pointer events.
 *
 * Pressing a working limit order's tag starts a drag at the order's price. Each move
 * reads the pointer as a price and snaps it to the one-cent tick (prices.snapToTick)
 * for the ghost line. Releasing on the old price cancels the gesture; releasing
 * anywhere else asks for confirmation in the Modify order popup. A peg has no price
 * of its own and can never be dragged, and an order with a reprice already in
 * flight cannot be dragged again until that one ends (P18-5).
 */

import { snapToTick } from "./prices";
import type { Side } from "./protocol/messages";

export interface RepriceDrag {
    readonly clOrdId: number;
    readonly side: Side;
    /** The order's limit price when the drag started. */
    readonly oldPx: number;
    /** The snapped price under the pointer: where the ghost line sits. */
    readonly ghostPx: number;
}

export type RepriceDrop =
    | { readonly kind: "cancel" }
    | { readonly kind: "confirm"; readonly clOrdId: number; readonly side: Side; readonly oldPx: number; readonly newPx: number };

/** What a drag needs to know about the order under the pointer. */
export interface DragTarget {
    readonly clOrdId: number;
    readonly side: Side;
    readonly pricePx: number;
    readonly draggable: boolean;
}

/** A new drag on `target`, or null when it cannot be dragged (a peg, or a reprice in flight). */
export function startDrag(target: DragTarget, inFlight: readonly number[]): RepriceDrag | null {
    if (!target.draggable || inFlight.includes(target.clOrdId)) return null;
    return { clOrdId: target.clOrdId, side: target.side, oldPx: target.pricePx, ghostPx: target.pricePx };
}

/** The drag with its ghost at the on-tick price nearest the pointer. Same object when unchanged. */
export function moveDrag(drag: RepriceDrag, pointerPx: number): RepriceDrag {
    const ghostPx = snapToTick(pointerPx);
    return ghostPx === drag.ghostPx ? drag : { ...drag, ghostPx };
}

/** Release: back on the old price cancels, anywhere else asks to confirm. */
export function dropDrag(drag: RepriceDrag): RepriceDrop {
    if (drag.ghostPx === drag.oldPx) return { kind: "cancel" };
    return { kind: "confirm", clOrdId: drag.clOrdId, side: drag.side, oldPx: drag.oldPx, newPx: drag.ghostPx };
}

/**
 * True when the new price would trade on arrival: a buy at or above the best ask, a
 * sell at or below the best bid (greenlight amendment 8). An empty opposite side
 * (-1) is never crossed.
 */
export function isMarketable(side: Side, newPx: number, bestBid: number, bestAsk: number): boolean {
    return side === "BUY" ? bestAsk > 0 && newPx >= bestAsk : bestBid > 0 && newPx <= bestBid;
}
