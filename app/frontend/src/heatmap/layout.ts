/**
 * Pure heatmap layout (P20-2, P20 D2 and D4, amended at P20-0 and its greenlight).
 * No canvas calls: the renderer (renderer.ts) replays what this module computes.
 *
 * Grid. The price domain [lo, hi] (tick prices) is the plot's y range. It is drawn as
 * rows of `rowTicks` ticks each: one tick a row up to HEATMAP_MAX_ROWS ticks, and past
 * that ceil(ticks / HEATMAP_MAX_ROWS) ticks a row, whose size is the SUM of its ticks'
 * sizes, the resting liquidity in that price range (amendment 4). The row cap is also
 * never more than the plot's height in device pixels (heatmapMaxRows), so a row is at
 * least one device pixel and a sparse level is summed into its row rather than averaged
 * away by a downscaled blit (P20-7 visual check). Row 0 is the lowest
 * price. The plot's price edges sit half a tick outside the first and last tick, so a
 * tick's cell is centred on its price.
 *
 * Domain (P20-0 F7). Fitted to the visible best bid and ask, padded by
 * HEATMAP_DOMAIN_PAD_TICKS, at least HEATMAP_MIN_SPAN_TICKS, snapped outward to
 * HEATMAP_DOMAIN_SNAP_TICKS. It is kept while both bests stay inside it and it could
 * not shrink by more than a quarter, so a full repaint is rare.
 *
 * Intensity (D2, amendment 3). log(1 + size) / log(1 + clip), clamped to 1, and any
 * non-zero size at least HEATMAP_MIN_INTENSITY so a 1-share level shows against an
 * empty cell. The clip is the visible window's 98th-percentile row size (as drawn,
 * so binned rows are summed first) divided by the contrast factor. The percentile
 * comes from a HEATMAP_CLIP_BINS-bin log histogram, so it is linear time and the clip
 * moves in discrete steps: a repaint happens only when it crosses a bin.
 *
 * Columns map to x through the visible time window (the line chart's window maths,
 * chartViewport): bucket b spans [b, b + 1) x 250 ms.
 */

import { TICK_PX } from "../prices";
import {
    COL_BOOK,
    sizeAt,
    HEATMAP_MAX_LEVELS,
    HEATMAP_STRIDE,
    HEATMAP_TICK_NS,
    bucketOfNs,
    bucketStartNs,
    oldestBucket,
    slotFor,
} from "./depthRing";
import type { DepthRing } from "./depthRing";
import { GROUND_PIXEL, rampIndex } from "./ramp";

export const HEATMAP_MIN_INTENSITY = 0.15;
export const HEATMAP_CLIP_PERCENTILE = 0.98;
export const HEATMAP_CLIP_BINS = 64;
export const HEATMAP_MAX_ROWS = 512;
export const HEATMAP_DOMAIN_PAD_TICKS = 10;
export const HEATMAP_MIN_SPAN_TICKS = 40;
export const HEATMAP_DOMAIN_SNAP_TICKS = 10;
/** The domain is refitted when it could shrink below this fraction of its span. */
export const HEATMAP_DOMAIN_SHRINK = 0.75;
/** The visible time span a heatmap opens with (P20-0: columns stay at 2px or more). */
export const HEATMAP_DEFAULT_SPAN_NS = 180_000_000_000;
export const BUBBLE_MIN_R = 2;
export const BUBBLE_MAX_R = 12;
/** Bubble radius per square-root share: area proportional to quantity. */
export const BUBBLE_K = 0.5;

export type HeatContrast = "low" | "normal" | "high";
export const HEAT_CONTRASTS: readonly HeatContrast[] = ["low", "normal", "high"];
export const CONTRAST_FACTOR: Readonly<Record<HeatContrast, number>> = { low: 0.5, normal: 1, high: 2 };

/** Upper end of the histogram's log range: a row sum of 2^40 shares. */
const LOG_MAX = Math.log1p(2 ** 40);
const BIN_W = LOG_MAX / HEATMAP_CLIP_BINS;

// ---- Intensity ---------------------------------------------------------------

/** Cell intensity in [0, 1]: 0 only for an empty cell. */
export function intensity(size: number, clip: number): number {
    if (!(size > 0)) return 0;
    const v = clip > 0 ? Math.log1p(size) / Math.log1p(clip) : 1;
    return Math.max(HEATMAP_MIN_INTENSITY, Math.min(1, v));
}

// ---- Domain and grid ------------------------------------------------------------

export interface PriceDomain {
    /** Lowest and highest tick price shown, in units. */
    readonly lo: number;
    readonly hi: number;
}

/**
 * The domain for bests spanning [bestLo, bestHi] (units), keeping `prev` while it
 * still fits (see the module comment). Null when there are no bests and no previous.
 */
export function nextDomain(prev: PriceDomain | null, bestLo: number, bestHi: number): PriceDomain | null {
    if (!(bestLo > 0) || !(bestHi > 0)) return prev;
    const snap = HEATMAP_DOMAIN_SNAP_TICKS * TICK_PX;
    let wantLo = bestLo - HEATMAP_DOMAIN_PAD_TICKS * TICK_PX;
    let wantHi = bestHi + HEATMAP_DOMAIN_PAD_TICKS * TICK_PX;
    const minSpan = HEATMAP_MIN_SPAN_TICKS * TICK_PX;
    if (wantHi - wantLo < minSpan) {
        const mid = (wantLo + wantHi) / 2;
        wantLo = mid - minSpan / 2;
        wantHi = mid + minSpan / 2;
    }
    const lo = Math.floor(wantLo / snap) * snap;
    const hi = Math.ceil(wantHi / snap) * snap;
    if (prev !== null && prev.lo <= bestLo && prev.hi >= bestHi && hi - lo >= HEATMAP_DOMAIN_SHRINK * (prev.hi - prev.lo)) {
        return prev;
    }
    return { lo, hi };
}

export interface RowGrid {
    /** Lowest tick price (row 0's first tick). */
    readonly lo: number;
    /** Ticks in the domain. */
    readonly ticks: number;
    /** Ticks per row: 1 up to the row cap (HEATMAP_MAX_ROWS or the plot's device px). */
    readonly rowTicks: number;
    readonly rows: number;
    /** Price edges of the plot: half a tick outside the first tick and the last row's last tick. */
    readonly edgeLo: number;
    readonly edgeHi: number;
}

/** The row cap for a plot `plotH` CSS px tall at `dpr`: at most one row a device pixel. */
export function heatmapMaxRows(plotH: number, dpr: number): number {
    const px = Math.floor(plotH * (dpr > 0 ? dpr : 1));
    return Math.max(1, Math.min(HEATMAP_MAX_ROWS, px));
}

export function gridFor(domain: PriceDomain, maxRows: number = HEATMAP_MAX_ROWS): RowGrid {
    const ticks = Math.round((domain.hi - domain.lo) / TICK_PX) + 1;
    const rowTicks = Math.max(1, Math.ceil(ticks / maxRows));
    const rows = Math.ceil(ticks / rowTicks);
    return {
        lo: domain.lo,
        ticks,
        rowTicks,
        rows,
        edgeLo: domain.lo - TICK_PX / 2,
        edgeHi: domain.lo + (rows * rowTicks - 0.5) * TICK_PX,
    };
}

export function sameGrid(a: RowGrid | null, b: RowGrid | null): boolean {
    return a !== null && b !== null && a.lo === b.lo && a.ticks === b.ticks && a.rowTicks === b.rowTicks;
}

/** The row holding a tick price, or -1 outside the grid. */
export function rowOf(grid: RowGrid, pricePx: number): number {
    const t = Math.round((pricePx - grid.lo) / TICK_PX);
    if (t < 0 || t >= grid.rows * grid.rowTicks) return -1;
    return Math.floor(t / grid.rowTicks);
}

/** The lowest and highest tick price a row covers. */
export function rowRange(grid: RowGrid, row: number): { readonly lo: number; readonly hi: number } {
    const lo = grid.lo + row * grid.rowTicks * TICK_PX;
    return { lo, hi: lo + (grid.rowTicks - 1) * TICK_PX };
}

// ---- Row sums -----------------------------------------------------------------

/** Reused scratch for summing a column into rows; one per renderer. */
export interface RowScratch {
    readonly sums: Float64Array;
    readonly touched: Int32Array;
    /** Rows touched by the last columnRows call. */
    n: number;
}

export function createRowScratch(maxRows: number = HEATMAP_MAX_ROWS): RowScratch {
    return { sums: new Float64Array(maxRows), touched: new Int32Array(HEATMAP_STRIDE), n: 0 };
}

/**
 * Sum one column's levels into rows: afterwards scratch.touched[0, n) lists the rows
 * with a size and scratch.sums holds them. Call clearRows before the next column.
 */
export function columnRows(ring: DepthRing, slot: number, grid: RowGrid, scratch: RowScratch): number {
    scratch.n = 0;
    if ((ring.flags[slot] & COL_BOOK) === 0) return 0;
    const anchor = ring.anchor[slot];
    if (anchor < 0) return 0;
    const base = slot * HEATMAP_STRIDE;
    const sides: readonly (readonly [number, number])[] = [
        [0, ring.bidCount[slot]],
        [HEATMAP_MAX_LEVELS, ring.askCount[slot]],
    ];
    for (const [start, count] of sides) {
        for (let i = 0; i < count; i++) {
            const k = base + start + i;
            const row = rowOf(grid, anchor + ring.offsets[k] * TICK_PX);
            if (row < 0) continue;
            if (scratch.sums[row] === 0) scratch.touched[scratch.n++] = row;
            scratch.sums[row] += ring.sizes[k];
        }
    }
    return scratch.n;
}

export function clearRows(scratch: RowScratch): void {
    for (let i = 0; i < scratch.n; i++) scratch.sums[scratch.touched[i]] = 0;
    scratch.n = 0;
}

// ---- Clip ---------------------------------------------------------------------

function binOf(size: number): number {
    return Math.min(HEATMAP_CLIP_BINS - 1, Math.floor(Math.log1p(size) / BIN_W));
}

/**
 * The 98th percentile of the non-zero values, from the log histogram: the upper edge
 * of the bin holding it. 1 when there are none. Exported for the outlier test.
 */
export function percentileClip(values: ArrayLike<number>, p: number = HEATMAP_CLIP_PERCENTILE): number {
    const hist = new Uint32Array(HEATMAP_CLIP_BINS);
    let n = 0;
    for (let i = 0; i < values.length; i++) {
        if (values[i] > 0) {
            hist[binOf(values[i])]++;
            n++;
        }
    }
    return clipFromHistogram(hist, n, p);
}

function clipFromHistogram(hist: Uint32Array, n: number, p: number): number {
    if (n === 0) return 1;
    const target = Math.ceil(p * n);
    let cum = 0;
    for (let k = 0; k < HEATMAP_CLIP_BINS; k++) {
        cum += hist[k];
        if (cum >= target) return Math.expm1((k + 1) * BIN_W);
    }
    return Math.expm1(LOG_MAX);
}

/**
 * The clip for the visible buckets [b0, b1]: the 98th-percentile row size as drawn,
 * divided by the contrast factor.
 */
export function clipFor(
    ring: DepthRing,
    grid: RowGrid,
    b0: number,
    b1: number,
    contrast: HeatContrast,
    scratch: RowScratch,
    hist: Uint32Array = new Uint32Array(HEATMAP_CLIP_BINS),
): number {
    hist.fill(0);
    let n = 0;
    const from = Math.max(b0, oldestBucket(ring));
    const to = Math.min(b1, ring.head);
    for (let b = from; b <= to && ring.count > 0; b++) {
        const slot = slotFor(ring, b);
        if (slot < 0) continue;
        const k = columnRows(ring, slot, grid, scratch);
        for (let i = 0; i < k; i++) {
            hist[binOf(scratch.sums[scratch.touched[i]])]++;
            n++;
        }
        clearRows(scratch);
    }
    return clipFromHistogram(hist, n, HEATMAP_CLIP_PERCENTILE) / CONTRAST_FACTOR[contrast];
}

// ---- Pixels -------------------------------------------------------------------

/**
 * Paint one column into a data-space image `width` pixels wide and grid.rows tall
 * (row 0 at the bottom): ground everywhere, then each non-empty row from the lookup
 * table. Unknown depth stays ground.
 */
export function paintColumn(
    ring: DepthRing,
    bucket: number,
    grid: RowGrid,
    clip: number,
    lut: Uint32Array,
    pixels: Uint32Array,
    width: number,
    x: number,
    scratch: RowScratch,
): void {
    const rows = grid.rows;
    for (let r = 0; r < rows; r++) pixels[r * width + x] = GROUND_PIXEL;
    const slot = slotFor(ring, bucket);
    if (slot < 0) return;
    const n = columnRows(ring, slot, grid, scratch);
    for (let i = 0; i < n; i++) {
        const row = scratch.touched[i];
        pixels[(rows - 1 - row) * width + x] = lut[rampIndex(intensity(scratch.sums[row], clip))];
    }
    clearRows(scratch);
}

// ---- View mapping ---------------------------------------------------------------

export interface HeatView {
    /** The visible time window, epoch ns. */
    readonly lo: number;
    readonly hi: number;
    readonly plotLeft: number;
    readonly plotTop: number;
    readonly plotW: number;
    readonly plotH: number;
}

export function xOfNs(view: HeatView, t: number): number {
    const span = view.hi - view.lo;
    return span > 0 ? view.plotLeft + ((t - view.lo) / span) * view.plotW : view.plotLeft;
}

/** y of a price on the grid's edges (the plot top is edgeHi). */
export function yOfPx(view: HeatView, grid: RowGrid, pricePx: number): number {
    return view.plotTop + view.plotH - ((pricePx - grid.edgeLo) / (grid.edgeHi - grid.edgeLo)) * view.plotH;
}

/** The buckets the window touches. */
export function visibleBuckets(view: HeatView): { readonly b0: number; readonly b1: number } {
    return { b0: bucketOfNs(view.lo), b1: bucketOfNs(Math.max(view.lo, view.hi - 1)) };
}

/** A cell's rectangle in plot pixels. Adjacent cells share edges exactly. */
export function cellRect(view: HeatView, grid: RowGrid, bucket: number, row: number) {
    const x0 = xOfNs(view, bucketStartNs(bucket));
    const x1 = xOfNs(view, bucketStartNs(bucket + 1));
    const r = rowRange(grid, row);
    const yTop = yOfPx(view, grid, r.hi + TICK_PX / 2);
    const yBot = yOfPx(view, grid, r.lo - TICK_PX / 2);
    return { x: x0, y: yTop, w: x1 - x0, h: yBot - yTop };
}

/** One blit from the ring-addressed data image to the plot: source columns to a pixel span. */
export interface BlitPiece {
    readonly sx: number;
    readonly sw: number;
    readonly dx: number;
    readonly dw: number;
}

/**
 * The blits that draw the held, visible columns: one, or two where the ring wraps.
 * Column `bucket` lives at x = bucket mod cols in the data image.
 */
export function blitPieces(ring: DepthRing, view: HeatView): readonly BlitPiece[] {
    if (ring.count === 0) return [];
    const { b0, b1 } = visibleBuckets(view);
    const from = Math.max(b0, oldestBucket(ring));
    const to = Math.min(b1, ring.head);
    if (to < from) return [];
    const out: BlitPiece[] = [];
    let b = from;
    while (b <= to) {
        const sx = ((b % ring.cols) + ring.cols) % ring.cols;
        const n = Math.min(to - b + 1, ring.cols - sx);
        const dx = xOfNs(view, bucketStartNs(b));
        out.push({ sx, sw: n, dx, dw: xOfNs(view, bucketStartNs(b + n)) - dx });
        b += n;
    }
    return out;
}

/** The visible best bid and ask range over the window's held columns: [lowest best, highest best], or null. */
export function bestsRange(ring: DepthRing, b0: number, b1: number): { readonly lo: number; readonly hi: number } | null {
    let lo = Infinity;
    let hi = -Infinity;
    const from = Math.max(b0, oldestBucket(ring));
    const to = Math.min(b1, ring.head);
    for (let b = from; b <= to && ring.count > 0; b++) {
        const slot = slotFor(ring, b);
        if (slot < 0 || ring.anchor[slot] < 0) continue;
        const base = slot * HEATMAP_STRIDE;
        const anchor = ring.anchor[slot];
        if (ring.bidCount[slot] > 0) {
            const px = anchor + ring.offsets[base] * TICK_PX;
            lo = Math.min(lo, px);
            hi = Math.max(hi, px);
        }
        if (ring.askCount[slot] > 0) {
            const px = anchor + ring.offsets[base + HEATMAP_MAX_LEVELS] * TICK_PX;
            lo = Math.min(lo, px);
            hi = Math.max(hi, px);
        }
    }
    return lo === Infinity ? null : { lo, hi };
}

/** A trade bubble's radius: area proportional to quantity, at least BUBBLE_MIN_R, capped at BUBBLE_MAX_R. */
export function bubbleRadius(qty: number): number {
    return Math.min(BUBBLE_MAX_R, Math.max(BUBBLE_MIN_R, BUBBLE_K * Math.sqrt(Math.max(0, qty))));
}

/** The column time span for a bucket, as [start, end) epoch ns. */
export function bucketSpanNs(bucket: number): { readonly t0: number; readonly t1: number } {
    return { t0: bucketStartNs(bucket), t1: bucketStartNs(bucket) + HEATMAP_TICK_NS };
}

// ---- Frame and readout -----------------------------------------------------------

/** What one paint needs: the grid, the clip and the blits for the visible window. */
export interface HeatmapFrame {
    readonly grid: RowGrid;
    readonly clip: number;
    readonly b0: number;
    readonly b1: number;
    readonly pieces: readonly BlitPiece[];
}

export function buildHeatmapFrame(
    ring: DepthRing,
    view: HeatView,
    grid: RowGrid,
    contrast: HeatContrast,
    scratch: RowScratch,
): HeatmapFrame {
    const { b0, b1 } = visibleBuckets(view);
    return { grid, clip: clipFor(ring, grid, b0, b1, contrast, scratch), b0, b1, pieces: blitPieces(ring, view) };
}

/** The readout's cell: the row's price range and its summed size (null when unknown or no book). */
export interface RowReadout {
    readonly lo: number;
    readonly hi: number;
    readonly size: number | null;
}

/** The cell under a price in the column holding `bucket` (amendment 4: a binned row reads its range and sum). */
export function rowReadout(ring: DepthRing, grid: RowGrid, bucket: number, pricePx: number): RowReadout | null {
    const row = rowOf(grid, pricePx);
    if (row < 0) return null;
    const { lo, hi } = rowRange(grid, row);
    let size: number | null = 0;
    for (let px = lo; px <= hi; px += TICK_PX) {
        const s = sizeAt(ring, bucket, px);
        if (s === null) {
            size = null;
            break;
        }
        size += s;
    }
    return { lo, hi, size };
}
