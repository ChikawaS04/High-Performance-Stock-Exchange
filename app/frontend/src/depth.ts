/**
 * Pure depth-book helpers shared across the depth panels.
 *
 * `cumulate` (with `CumLevel`) began as a private function in DepthLadder.tsx;
 * P7-5 lifts it here so the ladder and the depth curve share one definition of
 * running cumulative quantity rather than each carrying its own copy. Both sides
 * of the book arrive best-first (bids highest-first, asks lowest-first), so a
 * plain left-to-right fold accumulates outward from the touch on either side.
 *
 * `buildDepthCurve` is the P7-5 curve model: a cumulative-depth STEP function per
 * side, in domain units (integer units of $0.0001, integer cumulative quantity).
 * It emits no pixels; the component maps points to an SVG viewBox at the render
 * edge, exactly as buildLadder emits a 0..1 fraction and lets the component size
 * the bar. Prices stay integers here; only the SVG scales touch float, later, in
 * the component.
 */

import type { BookState } from "./state/reducer";
import type { Level } from "./protocol/messages";

/** One level with its running cumulative quantity, best-first from the touch. */
export interface CumLevel {
    readonly pricePx: number;
    readonly qty: number;
    readonly cumQty: number;
}

/** Running cumulative quantity, best-first (outward from the touch). */
export function cumulate(levels: readonly Level[]): CumLevel[] {
    const rows: CumLevel[] = [];
    let running = 0;
    for (const level of levels) {
        running += level[1];
        rows.push({ pricePx: level[0], qty: level[1], cumQty: running });
    }
    return rows;
}

/** One vertex of a side's depth curve, in domain units (never pixels). */
export interface CurvePoint {
    readonly pricePx: number;
    readonly cumQty: number;
}

/** Both sides' step-curve vertices, each in touch-outward order. */
export interface DepthCurveModel {
    readonly bids: readonly CurvePoint[];
    readonly asks: readonly CurvePoint[];
}

/**
 * Expand one cumulated side into an explicit STEP polyline.
 *
 * Depth is constant across the price interval a level owns, then jumps at the
 * next level's price; drawing one diagonal vertex per level would smooth that
 * staircase into a continuous curve and misreport discrete L2 depth. So each
 * further level contributes two vertices: a horizontal move to the new price at
 * the OLD cumulative depth, then a vertical step up to the new depth. A side of
 * n levels yields 2n - 1 vertices (1 for the first level, 2 for each after it);
 * an empty side yields none. Prices stay monotonic and cumQty non-decreasing.
 */
function stepCorners(cum: readonly CumLevel[]): CurvePoint[] {
    if (cum.length === 0) {
        return [];
    }
    const points: CurvePoint[] = [{ pricePx: cum[0].pricePx, cumQty: cum[0].cumQty }];
    for (let i = 1; i < cum.length; i++) {
        // horizontal: advance to this level's price while depth is still the prior total
        points.push({ pricePx: cum[i].pricePx, cumQty: cum[i - 1].cumQty });
        // vertical: step up to this level's own cumulative total
        points.push({ pricePx: cum[i].pricePx, cumQty: cum[i].cumQty });
    }
    return points;
}

/**
 * Pure cumulative-depth curve model from a BOOK slice. Unit-tested directly.
 *
 * Each side is optionally capped to `depth` levels nearest the touch (omitting it
 * plots every level the server sent, its own trimmed prefix), cumulated outward,
 * then expanded to step corners. The curve is intentionally NOT coupled to the
 * DepthLadder depth selector: it owns its optional cap and defaults to all levels.
 * The mid marker and the two axes are the component's job; this helper is domain
 * only, so empty and one-sided books yield empty point arrays with no NaN.
 */
export function buildDepthCurve(book: BookState, depth?: number): DepthCurveModel {
    const limit = depth ?? Number.POSITIVE_INFINITY;
    const bids = stepCorners(cumulate(book.bids.slice(0, limit)));
    const asks = stepCorners(cumulate(book.asks.slice(0, limit)));
    return { bids, asks };
}
