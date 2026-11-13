/**
 * Pure zoom and pan maths for the price chart (Charts follow-up, decisions 7 to 9).
 * No React, no DOM: PriceChart owns the wheel and pointer events and calls these.
 *
 * Candle mode. The visible window is { slotW, rightBucket }:
 *  - slotW null is the P17 auto fit (newest bars at the 5px minimum slot, capped at
 *    MAX_SLOT). The first wheel notch or pan freezes it to the slot width on screen.
 *  - rightBucket null means following: the right edge is the newest bar, pinned at
 *    the price axis, and the chart scrolls as new bars open. A number is an absolute
 *    bucket index, so new bars do not move a panned view.
 * Zoom changes slotW by ZOOM_STEP per wheel notch, clamped to [ZOOM_MIN_SLOT,
 * ZOOM_MAX_SLOT]. It anchors on the newest bar while following and on the right
 * edge while panned. Pan moves the right edge in whole slots: dragging right goes
 * back in time. The right edge is clamped so the view never shows past the newest
 * bar nor starts before the oldest closed bar; reaching the newest bar again turns
 * following back on.
 *
 * Line mode. The visible window is a time window over the capped tape,
 * { spanNs, rightT }: spanNs null is the whole tape, rightT null is following
 * (right edge at the newest print). Zoom scales the span by the same factor per
 * notch, clamped to [min(full, LINE_MIN_SPAN_NS), full]; pan moves the right edge by
 * the dragged fraction of the span (dx / plotW x span), continuously.
 *
 * None of this persists (DESIGN_SYSTEM.md §13 excludes scroll positions).
 */

export const ZOOM_MIN_SLOT = 3;
export const ZOOM_MAX_SLOT = 40;
/** Slot width (or span) factor per wheel notch. */
export const ZOOM_STEP = 1.15;
/** A pointer must travel this far before a press becomes a drag. */
export const PAN_THRESHOLD_PX = 3;
/** Narrowest line-mode window, unless the whole tape is narrower. */
export const LINE_MIN_SPAN_NS = 10_000_000_000;

/**
 * Wheel notches from a WheelEvent's deltaY and deltaMode. Positive is wheel down
 * (zoom out). A mouse notch is about 100px in pixel mode, 3 lines in line mode or
 * one page; a trackpad's small pixel deltas give fractional notches, so the zoom
 * scales with deltaY.
 */
export function wheelNotches(deltaY: number, deltaMode = 0): number {
    if (!Number.isFinite(deltaY)) return 0;
    if (deltaMode === 1) return deltaY / 3;
    if (deltaMode === 2) return deltaY;
    return deltaY / 100;
}

/** True once a press has moved at least PAN_THRESHOLD_PX on either axis. */
export function isDrag(dx: number, dy: number): boolean {
    return Math.abs(dx) >= PAN_THRESHOLD_PX || Math.abs(dy) >= PAN_THRESHOLD_PX;
}

export function clampSlotW(w: number): number {
    return Math.max(ZOOM_MIN_SLOT, Math.min(ZOOM_MAX_SLOT, w));
}

// ---- Candles ----------------------------------------------------------------

export interface CandleWindow {
    readonly slotW: number | null;
    readonly rightBucket: number | null;
}

export const FOLLOW_AUTO: CandleWindow = { slotW: null, rightBucket: null };

/** The bucket range the series holds: oldest closed bar to the newest (open) bar. */
export interface BucketExtent {
    readonly oldest: number;
    readonly newest: number;
}

/** Whole slots that fit across the plot at `slotW`. */
export function slotsThatFit(plotW: number, slotW: number): number {
    return Math.max(1, Math.floor(plotW / slotW));
}

/**
 * Clamp a right-edge bucket: never past the newest bar, and never so far back that
 * the view (n slots) starts before the oldest bar. Null (following) at the newest.
 */
export function clampRightBucket(right: number, extent: BucketExtent, n: number): number | null {
    const minRight = Math.min(extent.newest, extent.oldest + n - 1);
    const r = Math.max(minRight, Math.min(extent.newest, right));
    return r >= extent.newest ? null : r;
}

/**
 * Zoom by `notches` (negative zooms in). `onScreenSlotW` is the slot width the view
 * draws now (the auto fit's, before the first zoom).
 */
export function zoomCandles(
    win: CandleWindow,
    onScreenSlotW: number,
    notches: number,
    plotW: number,
    extent: BucketExtent,
): CandleWindow {
    const slotW = clampSlotW(onScreenSlotW * Math.pow(ZOOM_STEP, -notches));
    if (win.rightBucket === null) return { slotW, rightBucket: null };
    return { slotW, rightBucket: clampRightBucket(win.rightBucket, extent, slotsThatFit(plotW, slotW)) };
}

/** Where a drag started: the window, and the slot width and right edge then on screen. */
export interface CandlePanStart {
    readonly window: CandleWindow;
    readonly slotW: number;
    readonly rightBucket: number;
}

/**
 * Pan by a horizontal drag of `dx` pixels from the start, in whole slots. Dragging
 * right (dx > 0) goes back in time. Under one whole slot the window is the one the
 * drag started from (so an auto fit stays auto).
 */
export function panCandles(start: CandlePanStart, dx: number, plotW: number, extent: BucketExtent): CandleWindow {
    const slots = Math.trunc(dx / start.slotW);
    if (slots === 0) return start.window;
    const right = clampRightBucket(start.rightBucket - slots, extent, slotsThatFit(plotW, start.slotW));
    return { slotW: start.slotW, rightBucket: right };
}

// ---- Line ---------------------------------------------------------------------

export interface LineWindow {
    readonly spanNs: number | null;
    readonly rightT: number | null;
}

export const LINE_FULL: LineWindow = { spanNs: null, rightT: null };

export interface ResolvedLineWindow {
    readonly lo: number;
    readonly hi: number;
    readonly span: number;
    readonly following: boolean;
}

function lineSpanBounds(full: number): { readonly min: number; readonly max: number } {
    return { min: Math.min(full, LINE_MIN_SPAN_NS), max: full };
}

/** The window's [lo, hi] over a tape spanning [tMin, tMax], clamped. */
export function resolveLineWindow(win: LineWindow, tMin: number, tMax: number): ResolvedLineWindow {
    const full = tMax - tMin;
    if (!(full > 0)) return { lo: tMin, hi: tMax, span: 0, following: true };
    const { min, max } = lineSpanBounds(full);
    const span = win.spanNs === null ? full : Math.max(min, Math.min(max, win.spanNs));
    const hi = win.rightT === null ? tMax : Math.max(tMin + span, Math.min(tMax, win.rightT));
    return { lo: hi - span, hi, span, following: hi >= tMax };
}

/** Zoom a line window by `notches` (negative zooms in), anchored on its right edge. */
export function zoomLine(win: LineWindow, notches: number, tMin: number, tMax: number): LineWindow {
    const r = resolveLineWindow(win, tMin, tMax);
    const full = tMax - tMin;
    if (!(full > 0)) return win;
    const { min, max } = lineSpanBounds(full);
    const span = Math.max(min, Math.min(max, r.span * Math.pow(ZOOM_STEP, notches)));
    const spanNs = span >= full ? null : span;
    if (r.following) return { spanNs, rightT: null };
    const hi = Math.max(tMin + span, Math.min(tMax, r.hi));
    return { spanNs, rightT: hi >= tMax ? null : hi };
}

/** Pan a line window by a drag of `dx` pixels from where it started. */
export function panLine(
    start: ResolvedLineWindow,
    dx: number,
    plotW: number,
    tMin: number,
    tMax: number,
): LineWindow {
    const full = tMax - tMin;
    const spanNs = start.span >= full ? null : start.span;
    if (!(plotW > 0) || !(start.span > 0)) return { spanNs, rightT: null };
    const hi = Math.max(tMin + start.span, Math.min(tMax, start.hi - (dx / plotW) * start.span));
    return { spanNs, rightT: hi >= tMax ? null : hi };
}
