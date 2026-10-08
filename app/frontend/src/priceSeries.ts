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
