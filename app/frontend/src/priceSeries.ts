/**
 * Pure price-series helper for the session price chart (P12).
 *
 * Mirrors depth.ts: the maths and the domain live here, in integer units (epoch
 * nanoseconds for time, integer units of $0.0001 for price), and emit no pixels.
 * The component (PriceChart.tsx) owns the viewBox and the scales, exactly as
 * DepthCurve owns the scales over buildDepthCurve's domain points.
 *
 * The reducer tape is newest-first and capped at TAPE_CAP. A price line reads
 * left-to-right in time, so the points are reversed to oldest-first here. Only
 * pricePx and timestamp are read; the rest of TapeEntry is irrelevant to a
 * price line. Ordering within the EXEC stream is reliable (one ring, one
 * sequence), so reversing arrival order yields time order with no sort.
 */

import type { TapeEntry } from "./state/reducer";

/** One plotted print, in domain units (never pixels). */
export interface PricePoint {
    readonly t: number;       // epoch nanoseconds
    readonly pricePx: number; // integer units of $0.0001
}

/**
 * The oldest-first price series and its integer domain bounds. Returned together
 * so the degenerate cases (empty, single point, all-equal) are decided and
 * unit-tested here rather than in the component. Bounds are null when there is
 * nothing to plot.
 */
export interface PriceSeries {
    readonly points: readonly PricePoint[];
    readonly tMin: number | null;
    readonly tMax: number | null;
    readonly cMin: number | null;
    readonly cMax: number | null;
}

const EMPTY_SERIES: PriceSeries = {
    points: [],
    tMin: null,
    tMax: null,
    cMin: null,
    cMax: null,
};

/**
 * Build the oldest-first series and its domain from the newest-first tape. Pure
 * and unit-tested directly. An empty tape yields EMPTY_SERIES (null bounds, no
 * NaN). A single print yields one point with tMin === tMax and cMin === cMax;
 * all-equal prices yield cMin === cMax with distinct t bounds. The component's
 * scale guards key off exactly those equalities.
 */
export function buildPriceSeries(tape: readonly TapeEntry[]): PriceSeries {
    if (tape.length === 0) {
        return EMPTY_SERIES;
    }

    const points: PricePoint[] = [];
    for (let i = tape.length - 1; i >= 0; i--) {
        points.push({ t: tape[i].timestamp, pricePx: tape[i].pricePx });
    }

    let tMin = points[0].t;
    let tMax = points[0].t;
    let cMin = points[0].pricePx;
    let cMax = points[0].pricePx;
    for (const p of points) {
        if (p.t < tMin) tMin = p.t;
        if (p.t > tMax) tMax = p.t;
        if (p.pricePx < cMin) cMin = p.pricePx;
        if (p.pricePx > cMax) cMax = p.pricePx;
    }

    return { points, tMin, tMax, cMin, cMax };
}

/**
 * Axis helpers for the session price chart (P12 rework).
 *
 * Pure, integer-domain, pixel-free — the same contract as buildPriceSeries above.
 * priceAxis pads the actual price range out to round "nice" levels so a flat or
 * one-cent session reads flat instead of filling the panel, and yields the gridline
 * levels for the right-hand price axis. timeAxis yields sparse tick positions at
 * round elapsed intervals for the x-axis (the component labels them with
 * formatElapsed). The component owns the scales and the SVG; these own the maths.
 */

/** A "nice" number near `value`: 1 / 2 / 5 times a power of ten. */
export function niceNum(value: number, round: boolean): number {
    if (!Number.isFinite(value) || value <= 0) return 1;
    const exp = Math.floor(Math.log10(value));
    const f = value / Math.pow(10, exp);
    let nf: number;
    if (round) {
        nf = f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10;
    } else {
        nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
    }
    return nf * Math.pow(10, exp);
}

/** Padded price domain and round gridline levels, all in integer units. */
export interface PriceAxis {
    readonly dMin: number;              // padded domain min
    readonly dMax: number;              // padded domain max (always > dMin)
    readonly step: number;              // units between levels
    readonly levels: readonly number[]; // round price levels within [dMin, dMax]
}

/**
 * Build the y-axis domain and levels from the raw price bounds. Enforces a minimum
 * visible span (~0.1% of price, at least one cent) so a genuinely flat session does
 * not get auto-zoomed into a dramatic mountain, then snaps the domain outward to a
 * nice step so the line has headroom and the levels are round. `target` is the
 * approximate gridline count. A single-price session (cMin === cMax) centres a band
 * around the price. The component still never widens the domain for a reference
 * line: a reference outside [dMin, dMax] is simply not drawn.
 */
export function priceAxis(cMin: number, cMax: number, target = 6): PriceAxis {
    const mid = (cMin + cMax) / 2;
    const minSpan = Math.max(Math.round(Math.abs(mid) * 0.001), 100);
    let lo = cMin;
    let hi = cMax;
    if (hi - lo < minSpan) {
        lo = mid - minSpan / 2;
        hi = mid + minSpan / 2;
    }
    const step = Math.max(1, Math.round(niceNum((hi - lo) / Math.max(1, target - 1), true)));
    const dMin = Math.floor(lo / step) * step;
    const dMax = Math.ceil(hi / step) * step;
    const levels: number[] = [];
    for (let v = dMin; v <= dMax + step * 0.5; v += step) {
        levels.push(Math.round(v));
    }
    return { dMin, dMax: dMax > dMin ? dMax : dMin + step, step, levels };
}

/** Sparse time-axis ticks at round elapsed intervals. */
export interface TimeAxis {
    readonly stepNanos: number;        // interval between ticks, in nanoseconds
    readonly ticks: readonly number[]; // absolute timestamps (epoch/monotonic nanos)
}

// Round elapsed intervals in seconds, coarsening as the session lengthens.
const TIME_STEPS_SEC: readonly number[] = [
    1, 2, 5, 10, 15, 20, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200,
];

/**
 * Build x-axis ticks from the time bounds. Picks the smallest round interval that
 * keeps the tick count near `target`, then places ticks at that interval from tMin.
 * Ticks are absolute timestamps for the component's xOf scale; the component labels
 * each with formatElapsed(tick - tMin). A zero or inverted span yields a single tick
 * at tMin (the degenerate single-timestamp case the component centres on x).
 */
export function timeAxis(tMin: number, tMax: number, target = 5): TimeAxis {
    const spanNanos = tMax - tMin;
    if (!Number.isFinite(spanNanos) || spanNanos <= 0) {
        return { stepNanos: 0, ticks: [tMin] };
    }
    const idealSec = spanNanos / 1_000_000_000 / target;
    let stepSec = TIME_STEPS_SEC[TIME_STEPS_SEC.length - 1];
    for (const s of TIME_STEPS_SEC) {
        if (s >= idealSec) {
            stepSec = s;
            break;
        }
    }
    const stepNanos = stepSec * 1_000_000_000;
    const ticks: number[] = [];
    for (let e = 0; e <= spanNanos + 1; e += stepNanos) {
        ticks.push(tMin + e);
    }
    return { stepNanos, ticks };
}
