/**
 * Pure candle-chart geometry (P17-6 to P17-10). No React, no SVG.
 *
 * Mirrors priceSeries.ts: the domain, the visible window and the hit test live here
 * in integer price units and plot-relative pixels; PriceChart.tsx owns the viewBox,
 * the scales and the markup.
 *
 * Slots (P17 D7). The chart shows the newest bars that fit: at most
 * floor(plotW / MIN_SLOT) slots, from the oldest visible bucket to the open bar. A
 * gap bucket takes its slot and stays empty (P17 D3). The slot width is
 * plotW / slotCount, capped at MAX_SLOT so a young session does not draw one
 * candle across most of the plot; when capped, the slots are right-aligned so the
 * newest bar always sits at the right edge, next to the price axis. Body width is
 * BODY_FRACTION of the slot, at least 1px.
 *
 * Price domain. The visible highs and lows only, padded 5% each side and then
 * snapped to round levels by priceAxis (which also guards a flat or one-bar
 * series with its minimum span). Reference lines and VWAP never widen it.
 *
 * Zoom and pan (Charts follow-up, decisions 7 and 8). An optional window fixes the
 * slot width and the right-edge bucket (chartViewport.ts). With a fixed slot width
 * the view spans exactly the slots that fit, ending at the right edge; when the
 * history is shorter it starts at the oldest bar and stays right-aligned. With no
 * window (or slotW null) it is the P17 auto fit above, following the newest bar.
 * The y axis always refits to the bars in view.
 */

import type { Bar, BarSeries } from "./bars";
import { bucketStart, seriesBars } from "./bars";
import { priceAxis } from "./priceSeries";
import { clampRightBucket, clampSlotW, slotsThatFit } from "./chartViewport";
import type { CandleWindow } from "./chartViewport";
import { pickTickStep } from "./timeTicks";

export const MIN_SLOT = 5;
export const MAX_SLOT = 16;
export const BODY_FRACTION = 0.7;

export interface CandleSlot {
    readonly slot: number;
    readonly bar: Bar;
}

export interface CandleView {
    readonly firstBucket: number;
    /** The right-edge bucket in view (the newest bar while following). */
    readonly lastBucket: number;
    /** The series' oldest and newest buckets, for the pan and zoom clamps. */
    readonly oldestBucket: number;
    readonly newestBucket: number;
    /** True when the right edge is the newest bar. */
    readonly following: boolean;
    readonly slotCount: number;
    readonly slotW: number;
    /** Plot-relative x where slot 0 starts (non-zero when slots are capped and right-aligned). */
    readonly offset: number;
    /** Visible bars, oldest first, each with its slot index. */
    readonly bars: readonly CandleSlot[];
    readonly dMin: number;
    readonly dMax: number;
    readonly levels: readonly number[];
    readonly maxVolume: number;
}

/**
 * The visible candle window for a plot `plotW` pixels wide, or null with no bars.
 * `win` is the zoom and pan state (chartViewport.ts); omitted, it is the auto fit.
 */
export function buildCandleView(
    series: BarSeries,
    plotW: number,
    yTarget = 6,
    win: CandleWindow = { slotW: null, rightBucket: null },
): CandleView | null {
    const all = seriesBars(series);
    if (all.length === 0 || !(plotW > 0)) return null;

    const oldestBucket = all[0].bucket;
    const newestBucket = all[all.length - 1].bucket;
    const extent = { oldest: oldestBucket, newest: newestBucket };
    const fixedW = win.slotW === null ? null : clampSlotW(win.slotW);
    const maxSlots = fixedW === null ? Math.max(1, Math.floor(plotW / MIN_SLOT)) : slotsThatFit(plotW, fixedW);
    const lastBucket =
        win.rightBucket === null ? newestBucket : (clampRightBucket(win.rightBucket, extent, maxSlots) ?? newestBucket);
    const oldestAllowed = lastBucket - maxSlots + 1;

    const bars: CandleSlot[] = [];
    let firstBucket = fixedW === null ? lastBucket : Math.max(oldestBucket, oldestAllowed);
    let carry: Bar | null = null;
    for (const b of all) {
        if (b.bucket > lastBucket) break;
        if (b.bucket < oldestAllowed) {
            carry = b;
            continue;
        }
        if (bars.length === 0 && fixedW === null) firstBucket = b.bucket;
        bars.push({ slot: b.bucket - firstBucket, bar: b });
    }
    const slotCount = lastBucket - firstBucket + 1;
    const slotW = fixedW ?? Math.min(MAX_SLOT, plotW / slotCount);
    const offset = plotW - slotCount * slotW;

    // The domain comes from the bars in view; a view that falls wholly in a gap
    // takes the last bar before it, so the axis never collapses.
    const domainBars = bars.length > 0 ? bars.map((x) => x.bar) : carry !== null ? [carry] : [all[0]];
    let lo = Infinity;
    let hi = -Infinity;
    let maxVolume = 0;
    for (const bar of domainBars) {
        if (bar.low < lo) lo = bar.low;
        if (bar.high > hi) hi = bar.high;
    }
    for (const { bar } of bars) {
        if (bar.volume > maxVolume) maxVolume = bar.volume;
    }
    const pad = Math.round((hi - lo) * 0.05);
    const axis = priceAxis(lo - pad, hi + pad, yTarget);

    return {
        firstBucket,
        lastBucket,
        oldestBucket,
        newestBucket,
        following: lastBucket === newestBucket,
        slotCount,
        slotW,
        offset,
        bars,
        dMin: axis.dMin,
        dMax: axis.dMax,
        levels: axis.levels,
        maxVolume,
    };
}

/** Plot-relative x of a slot's centre. */
export function slotCentre(view: CandleView, slot: number): number {
    return view.offset + (slot + 0.5) * view.slotW;
}

/** Body width for the view's slot, at least 1px. */
export function bodyWidth(view: CandleView): number {
    return Math.max(1, view.slotW * BODY_FRACTION);
}

export interface CandleTick {
    readonly slot: number;
    /** Epoch nanoseconds of the bucket start, for the clock label. */
    readonly t: number;
}

/**
 * The candle time-axis step in seconds (Charts follow-up, decision 4): the smallest
 * round clock step that is a whole number of bars and puts labels at least
 * `minGapPx` apart at the view's slot width.
 */
export function candleTickStep(view: CandleView, sizeNs: number, minGapPx?: number): number {
    const sizeSec = sizeNs / 1_000_000_000;
    return pickTickStep(view.slotW / sizeSec, sizeSec, minGapPx);
}

/**
 * Time-axis ticks at bar boundaries, on the candleTickStep. A bucket is a tick when
 * its index is a multiple of the bars per step, which on clock-aligned buckets means
 * its start is a round clock time.
 */
export function candleTimeTicks(view: CandleView, sizeNs: number, minGapPx?: number): readonly CandleTick[] {
    const sizeSec = sizeNs / 1_000_000_000;
    const stepSec = candleTickStep(view, sizeNs, minGapPx);
    const per = Math.max(1, Math.round(stepSec / sizeSec));
    const ticks: CandleTick[] = [];
    const start = Math.ceil(view.firstBucket / per) * per;
    for (let b = start; b <= view.lastBucket; b += per) {
        ticks.push({ slot: b - view.firstBucket, t: bucketStart(b, sizeNs) });
    }
    return ticks;
}

/** Price at a y inside a price rectangle, or null outside it. Inverse of the y scale. */
export function priceAt(y: number, top: number, height: number, dMin: number, dMax: number): number | null {
    if (!(height > 0) || y < top || y > top + height) return null;
    return dMin + ((top + height - y) / height) * (dMax - dMin);
}

export interface CandleHit {
    readonly slot: number;
    readonly bucket: number;
    /** The bar in the slot, or null for a gap. */
    readonly bar: Bar | null;
}

/**
 * The slot under a plot-relative x, or null outside the slots. Slot edges belong to
 * the slot on their right; the far right edge belongs to the last slot.
 */
export function hitSlot(view: CandleView, x: number): CandleHit | null {
    const span = view.slotCount * view.slotW;
    const rel = x - view.offset;
    if (rel < 0 || rel > span) return null;
    const slot = Math.min(view.slotCount - 1, Math.floor(rel / view.slotW));
    const bucket = view.firstBucket + slot;
    let bar: Bar | null = null;
    for (const s of view.bars) {
        if (s.slot === slot) {
            bar = s.bar;
            break;
        }
    }
    return { slot, bucket, bar };
}

/** Index of the x nearest to `x`, or -1 for an empty list. Ties go to the earlier one. */
export function nearestIndex(xs: readonly number[], x: number): number {
    let best = -1;
    let bestD = Infinity;
    for (let i = 0; i < xs.length; i++) {
        const d = Math.abs(xs[i] - x);
        if (d < bestD) {
            bestD = d;
            best = i;
        }
    }
    return best;
}

export interface VwapPoint {
    readonly t: number;
    readonly vwap: number;
}

/**
 * Line-mode VWAP as step points on the tape's epoch time domain (P17 D9 amended).
 * Each 5s bar contributes a step at its bucket start; the line is clipped to
 * [tMin, tMax]. It starts at tMin with the latest bar that began at or before tMin
 * (or the first bar inside the window) and ends at tMax with the newest value.
 * Empty when no bar falls in or before the window.
 */
export function lineVwapSteps(bars5s: readonly Bar[], sizeNs: number, tMin: number, tMax: number): readonly VwapPoint[] {
    let start: Bar | null = null;
    const inside: Bar[] = [];
    for (const b of bars5s) {
        const t = bucketStart(b.bucket, sizeNs);
        if (t <= tMin) start = b;
        else if (t <= tMax) inside.push(b);
    }
    const head = start ?? inside.shift() ?? null;
    if (head === null || head.vwap < 0) return [];
    const pts: VwapPoint[] = [{ t: tMin, vwap: head.vwap }];
    for (const b of inside) {
        if (b.vwap >= 0) pts.push({ t: bucketStart(b.bucket, sizeNs), vwap: b.vwap });
    }
    pts.push({ t: tMax, vwap: pts[pts.length - 1].vwap });
    return pts;
}

/**
 * Centre y of the VWAP axis tag. Pinned inside [top + h/2, bottom - h/2] so an
 * off-scale VWAP shows at the nearest axis edge; moved a full tag height away from
 * the last-price tag when the two would overlap, to the side the VWAP is on, and
 * flipped to the other side when that would leave the axis.
 */
export function placeVwapTag(lastY: number, vwapY: number, top: number, bottom: number, h = 18): number {
    const lo = top + h / 2;
    const hi = bottom - h / 2;
    const clamp = (y: number): number => Math.max(lo, Math.min(hi, y));
    const last = clamp(lastY);
    let y = clamp(vwapY);
    if (Math.abs(y - last) < h) {
        const above = last - h;
        const below = last + h;
        const preferAbove = vwapY <= lastY;
        if (preferAbove) y = above >= lo ? above : below;
        else y = below <= hi ? below : above;
    }
    return y;
}

/**
 * True when a price-axis label at `y` keeps at least `gap` from every blocker (the
 * volume pane's separator and its axis labels), so labels never overlap (Charts
 * follow-up, decision 3).
 */
export function labelClear(y: number, blockers: readonly number[], gap = 12): boolean {
    return blockers.every((b) => Math.abs(y - b) >= gap);
}
