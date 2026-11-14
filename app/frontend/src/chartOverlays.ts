/**
 * Pure placement for the chart's own-trading overlays (Phase 18). No React, no
 * pixels beyond what the caller hands in: the chart maps an anchor (a candle slot,
 * or a time in line mode) and a price to its own coordinates.
 *
 * Fill markers (P18 D2). A fill is a side, not a direction (DESIGN_SYSTEM.md §2), so
 * a buy is an up-pointing triangle just below its price in the bid blue and a sell
 * a down-pointing triangle just above it in the ask red. Fills are grouped by
 * anchor and side: in candle mode the anchor is the fill's bar slot, in line mode
 * its timestamp (every fill of one aggressor shares its command timestamp). A
 * group of up to MARKER_STACK_MAX fills draws one marker per fill, each at its own
 * price, stacked outward (buys down, sells up) by MARKER_STACK_PX per fill. A
 * larger group draws one marker with a count, at the group's quantity-weighted
 * average price.
 *
 * Working order lines (P18 D3). One line per own resting order: a limit at its own
 * price (dashed), a midpoint peg at the current venue mid (dotted). The mid is the
 * exact mean of the lit best bid and best ask, as the engine computes it; both tops
 * are on the one-cent tick, so it is a whole number of units (a multiple of 50) and
 * may sit on a half cent. It is never snapped. With either side of the book empty
 * there is no mid, so a peg has no line. A line whose price is outside the visible
 * price domain is pinned to the nearer edge as a tag with an arrow, so a working
 * order is never invisible; order lines never widen the domain. Only a limit line
 * is draggable (P18-4); a peg has no price of its own.
 *
 * Bid/ask lines (P18 D7). Two step lines: a quote holds until the next change, so a
 * stretch with no change carries the last quote forward, and only an explicit -1
 * (an empty side, or the disconnect marker) breaks a line. Candle mode steps per
 * slot from the bucketed series (each bucket's last quote spans its slot, edge to
 * edge, so the steps line up with the candles); line mode steps on the time domain
 * from the raw ring. A quote that changed before the view starts is carried in.
 */

import { bucketOf, vwapUnits } from "./bars";
import { formatQty } from "./format";
import type { OwnFill } from "./state/reducer";
import type { WorkingOrder } from "./ownExecutions";
import type { Side } from "./protocol/messages";
import type { QuoteBucket, QuotePoint } from "./quotes";

/** Outward offset between stacked markers in one group, in drawing units. */
export const MARKER_STACK_PX = 3;
/** Most markers drawn for one group; past it the group is one marker with a count. */
export const MARKER_STACK_MAX = 3;
/** Pointer distance, in drawing units, within which a marker counts as hovered. */
export const MARKER_HIT_PX = 7;

export interface FillMarker {
    /** Stable React key. */
    readonly key: string;
    readonly side: Side;
    /** A candle slot index (candle mode) or an epoch-nanosecond time (line mode). */
    readonly anchor: number;
    /** The marker's price in units: the fill's own, or the group's average for a count marker. */
    readonly pricePx: number;
    /** 0 for the first marker of a group, then 1, 2: how far it sits outward. */
    readonly stack: number;
    /** Fills this marker stands for: 1, or the group size for a count marker. */
    readonly count: number;
    /** Shares this marker stands for. */
    readonly qty: number;
}

interface Group {
    readonly anchor: number;
    readonly side: Side;
    readonly fills: OwnFill[];
}

/** Group fills by anchor and side, each group oldest first, groups in anchor order. */
function groupFills(fills: readonly OwnFill[], anchorOf: (f: OwnFill) => number | null): Group[] {
    const groups = new Map<string, Group>();
    // myFills is newest first; walk it backwards so each group is oldest first.
    for (let i = fills.length - 1; i >= 0; i--) {
        const f = fills[i];
        const anchor = anchorOf(f);
        if (anchor === null) continue;
        const key = `${anchor}:${f.side}`;
        let g = groups.get(key);
        if (g === undefined) {
            g = { anchor, side: f.side, fills: [] };
            groups.set(key, g);
        }
        g.fills.push(f);
    }
    return [...groups.values()].sort((a, b) => a.anchor - b.anchor || (a.side === "BUY" ? -1 : 1));
}

function markersOf(groups: readonly Group[]): FillMarker[] {
    const out: FillMarker[] = [];
    for (const g of groups) {
        const base = `${g.anchor}:${g.side}`;
        if (g.fills.length > MARKER_STACK_MAX) {
            let qty = 0;
            let notional = 0n;
            for (const f of g.fills) {
                qty += f.qty;
                notional += BigInt(f.pricePx) * BigInt(f.qty);
            }
            // The average is a display position and readout statistic, never an order
            // price, so rounding it half up (as VWAP is) is allowed.
            out.push({ key: `${base}:n`, side: g.side, anchor: g.anchor, pricePx: vwapUnits(notional, qty), stack: 0, count: g.fills.length, qty });
            continue;
        }
        g.fills.forEach((f, i) => {
            out.push({ key: `${base}:${f.tradeId}:${f.clOrdId}`, side: g.side, anchor: g.anchor, pricePx: f.pricePx, stack: i, count: 1, qty: f.qty });
        });
    }
    return out;
}

/**
 * Markers for the candle view: each fill anchored to its bar's slot. Only fills
 * whose bucket lies in [firstBucket, lastBucket] are placed; the slot is
 * bucket - firstBucket, exactly as the candle view numbers its slots.
 */
export function fillMarkersForCandles(
    fills: readonly OwnFill[],
    firstBucket: number,
    lastBucket: number,
    sizeNs: number,
): readonly FillMarker[] {
    return markersOf(
        groupFills(fills, (f) => {
            const b = bucketOf(f.timestamp, sizeNs);
            return b >= firstBucket && b <= lastBucket ? b - firstBucket : null;
        }),
    );
}

/** Markers for the line chart: each fill anchored to its own time, inside [lo, hi]. */
export function fillMarkersForLine(fills: readonly OwnFill[], lo: number, hi: number): readonly FillMarker[] {
    return markersOf(groupFills(fills, (f) => (f.timestamp >= lo && f.timestamp <= hi ? f.timestamp : null)));
}

/**
 * The marker point's offset from its price, in drawing units: below the price for a
 * buy (positive y), above it for a sell, further out per stack step.
 */
export function markerOffset(side: Side, stack: number): number {
    const out = 5 + stack * MARKER_STACK_PX;
    return side === "BUY" ? out : -out;
}

/**
 * Index of the marker point nearest (x, y) within `r`, or -1. Ties go to the earlier
 * point. Pure, so the hover hit test is unit tested without a DOM.
 */
export function nearestPoint(points: readonly { readonly x: number; readonly y: number }[], x: number, y: number, r = MARKER_HIT_PX): number {
    let best = -1;
    let bestD = r * r;
    points.forEach((p, i) => {
        const d = (p.x - x) ** 2 + (p.y - y) ** 2;
        if (d <= bestD && (best === -1 || d < bestD)) {
            best = i;
            bestD = d;
        }
    });
    return best;
}

/** The readout text for a hovered marker: "200 @ 1446.66", plus "(4 fills)" for a count marker. */
export function markerReadout(m: FillMarker, formatPrice: (px: number) => string, formatQty: (q: number) => string): string {
    const base = `${formatQty(m.qty)} @ ${formatPrice(m.pricePx)}`;
    return m.count > 1 ? `${base} (${m.count} fills)` : base;
}

// ---- Working order lines (P18 D3) ----------------------------------------------

export type OrderPin = "top" | "bottom";

export interface OrderLine {
    readonly clOrdId: number;
    readonly side: Side;
    readonly kind: "limit" | "peg";
    /** The line's price in units: the limit, or the current mid for a peg. */
    readonly pricePx: number;
    /** Set when the price is outside the visible domain: the edge the tag pins to. */
    readonly pin: OrderPin | null;
    /** Tag text: "BUY 200 LMT", an iceberg "BUY 100/500 LMT", a peg "SELL 50 PEG". */
    readonly label: string;
    /** Only a limit line can be dragged to reprice (P18-4). */
    readonly draggable: boolean;
}

// The venue mid lives in prices.ts since P18-8 (the arrival stamp uses it too).
export { venueMid } from "./prices";

/** The tag text for a working order (see OrderLine.label). */
export function orderLabel(w: WorkingOrder): string {
    if (w.ordType === "PEG_MID") return `${w.side} ${formatQty(w.remainingQty)} PEG`;
    const qty =
        w.displayQty !== null ? `${formatQty(Math.min(w.displayQty, w.remainingQty))}/${formatQty(w.remainingQty)}` : formatQty(w.remainingQty);
    return `${w.side} ${qty} LMT`;
}

/**
 * One line per working order, in the working list's order. A peg is left out when
 * there is no mid. `dMin` and `dMax` are the visible price domain in units.
 */
export function placeOrderLines(
    working: readonly WorkingOrder[],
    mid: number | null,
    dMin: number,
    dMax: number,
): readonly OrderLine[] {
    const out: OrderLine[] = [];
    for (const w of working) {
        const isPeg = w.limitPx === null;
        const pricePx = isPeg ? mid : w.limitPx;
        if (pricePx === null) continue;
        const pin: OrderPin | null = pricePx > dMax ? "top" : pricePx < dMin ? "bottom" : null;
        out.push({
            clOrdId: w.clOrdId,
            side: w.side,
            kind: isPeg ? "peg" : "limit",
            pricePx,
            pin,
            label: orderLabel(w),
            draggable: !isPeg,
        });
    }
    return out;
}

/**
 * The y of each order tag: its line's y when in domain; pinned tags stack inward
 * from their edge, one tag height apart, so several off-screen orders all show.
 * `yOf` maps a price to drawing units; `top` and `bottom` bound the price area.
 */
export function orderTagYs(lines: readonly OrderLine[], yOf: (px: number) => number, top: number, bottom: number, tagH: number): readonly number[] {
    let topN = 0;
    let bottomN = 0;
    return lines.map((l) => {
        if (l.pin === "top") return top + tagH / 2 + tagH * topN++;
        if (l.pin === "bottom") return bottom - tagH / 2 - tagH * bottomN++;
        return yOf(l.pricePx);
    });
}

// ---- Bid/ask step lines (P18 D7) ------------------------------------------------

/** One flat stretch of a step line, from position a0 to a1, at price px. */
export interface StepSegment {
    readonly a0: number;
    readonly a1: number;
    readonly px: number;
}

/** A side's line as unbroken runs of contiguous segments. */
export type StepRuns = readonly (readonly StepSegment[])[];

export interface QuoteSteps {
    readonly bid: StepRuns;
    readonly ask: StepRuns;
}

interface Change {
    readonly at: number;
    readonly px: number;
}

/**
 * Step runs over [from, to] from changes sorted by position. The value in force at
 * `from` is the last change at or before it (carried in); each later change inside
 * the window starts a new segment; the last segment runs to `to`. A value <= 0
 * (the -1 empty side) is a gap, which ends the current run.
 */
export function stepRuns(changes: readonly Change[], from: number, to: number): StepRuns {
    if (!(to > from)) return [];
    let i = 0;
    let current: number | null = null;
    while (i < changes.length && changes[i].at <= from) {
        current = changes[i].px;
        i++;
    }
    const runs: StepSegment[][] = [];
    let run: StepSegment[] = [];
    let start = from;
    // Close the stretch [start, end): a quote adds a segment, a gap ends the run.
    const emit = (end: number): void => {
        if (current !== null && current > 0) {
            if (end > start) run.push({ a0: start, a1: end, px: current });
        } else if (run.length > 0) {
            runs.push(run);
            run = [];
        }
    };
    for (; i < changes.length && changes[i].at < to; i++) {
        const c = changes[i];
        if (c.px === current) continue;
        emit(c.at);
        current = c.px;
        start = c.at;
    }
    emit(to);
    if (run.length > 0) runs.push(run);
    return runs;
}

/**
 * Candle mode: steps in slot units for the visible buckets [firstBucket, lastBucket].
 * Positions are slot indices: a bucket's quote spans [slot, slot + 1).
 */
export function candleQuoteSteps(buckets: readonly QuoteBucket[], firstBucket: number, lastBucket: number): QuoteSteps {
    const side = (pick: (b: QuoteBucket) => number): StepRuns =>
        stepRuns(
            buckets.map((b) => ({ at: b.bucket - firstBucket, px: pick(b) })),
            0,
            lastBucket - firstBucket + 1,
        );
    return { bid: side((b) => b.bid), ask: side((b) => b.ask) };
}

/** Line mode: steps on the epoch-nanosecond time window [lo, hi] from the raw ring. */
export function lineQuoteSteps(ring: readonly QuotePoint[], lo: number, hi: number): QuoteSteps {
    const side = (pick: (p: QuotePoint) => number): StepRuns =>
        stepRuns(
            ring.map((p) => ({ at: p.t, px: pick(p) })),
            lo,
            hi,
        );
    return { bid: side((p) => p.bid), ask: side((p) => p.ask) };
}
