/**
 * Pure moving averages on bar closes (P19-8, Phase 19 D8 as amended). No React.
 *
 * Both run over the bars present in the selected series, oldest first: a gap bucket
 * has no bar and is skipped, so a length-20 average is over the last 20 bars, not the
 * last 20 buckets. Values before `length` bars exist are null and are never drawn.
 *
 *  - SMA is exact: an integer rolling sum of integer closes, divided by the length.
 *    Its readout value is rounded half up from the integer sum, so nothing rounds on
 *    the way.
 *  - EMA is a float step, like Bollinger's standard deviation (D9): seeded with the
 *    SMA of the first `length` closes, then ema += alpha * (close - ema) with
 *    alpha = 2 / (length + 1), in float from integer closes. Its readout rounds half
 *    up at the read. It is a statistic, never an order price.
 *
 * Recomputed over the retained series. The bars keep at most BAR_CAP closed bars, so
 * when the oldest ages out the EMA's seed moves forward one bar: with a length near
 * BAR_CAP its values shift slightly as bars age out. An SMA never depends on more
 * than its window, so it does not shift.
 *
 * Memoised on (series reference, kind, length) in a WeakMap, so a render with no new
 * fill recomputes nothing, and a series no longer referenced is collected with its
 * cache.
 */

import { seriesBars } from "../bars";
import type { BarSeries } from "../bars";

export type MaKind = "sma" | "ema";

export interface MaPoint {
    /** The average in units as a float, for drawing. */
    readonly value: number;
    /** The average rounded half up to a whole unit, for the readout. */
    readonly units: number;
}

/** Half-up rounding of n / d for a non-negative integer n and positive integer d. */
function divHalfUp(n: number, d: number): number {
    return Math.floor((2 * n + d) / (2 * d));
}

/** SMA of `closes` at each index; null until `length` closes exist. */
export function smaValues(closes: readonly number[], length: number): readonly (MaPoint | null)[] {
    const out: (MaPoint | null)[] = [];
    let sum = 0;
    for (let i = 0; i < closes.length; i++) {
        sum += closes[i];
        if (i >= length) sum -= closes[i - length];
        out.push(i + 1 >= length ? { value: sum / length, units: divHalfUp(sum, length) } : null);
    }
    return out;
}

/** EMA of `closes` at each index, seeded with the first window's SMA; null before it. */
export function emaValues(closes: readonly number[], length: number): readonly (MaPoint | null)[] {
    const out: (MaPoint | null)[] = [];
    const alpha = 2 / (length + 1);
    let seedSum = 0;
    let ema = 0;
    for (let i = 0; i < closes.length; i++) {
        if (i + 1 < length) {
            seedSum += closes[i];
            out.push(null);
            continue;
        }
        if (i + 1 === length) {
            seedSum += closes[i];
            ema = seedSum / length;
        } else {
            ema += alpha * (closes[i] - ema);
        }
        out.push({ value: ema, units: Math.floor(ema + 0.5) });
    }
    return out;
}

/** A per-bucket lookup of one average over a series. */
export type MaByBucket = ReadonlyMap<number, MaPoint>;

const cache = new WeakMap<BarSeries, Map<string, unknown>>();

/**
 * Memoise `compute` on (series reference, key). Shared by every bar indicator
 * (Bollinger keys on its length and width too); keys must be unique per computation.
 */
export function memoOnSeries<T>(series: BarSeries, key: string, compute: () => T): T {
    let bySeries = cache.get(series);
    if (bySeries === undefined) {
        bySeries = new Map();
        cache.set(series, bySeries);
    }
    const hit = bySeries.get(key);
    if (hit !== undefined) return hit as T;
    const v = compute();
    bySeries.set(key, v);
    return v;
}

/** The average of `kind` and `length` over a series, by bucket. Memoised. */
export function movingAverage(series: BarSeries, kind: MaKind, length: number): MaByBucket {
    return memoOnSeries(series, `${kind}:${length}`, () => {
        const bars = seriesBars(series);
        const closes = bars.map((b) => b.close);
        const values = kind === "sma" ? smaValues(closes, length) : emaValues(closes, length);
        const out = new Map<number, MaPoint>();
        values.forEach((v, i) => {
            if (v !== null) out.set(bars[i].bucket, v);
        });
        return out;
    });
}
