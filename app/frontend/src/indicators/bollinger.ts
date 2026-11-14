/**
 * Pure Bollinger bands on bar closes (P19-9, Phase 19 D9). No React.
 *
 * Over the last `length` bars present (gap buckets skipped, as for the moving
 * averages): the middle line is the SMA of the closes, and the bands sit `width`
 * standard deviations either side, `width` stored in tenths (20 means 2.0, P19-7).
 * The standard deviation is the population SD over the window.
 *
 * The one float step (D9): the SD is computed in float at the render edge from the
 * integer closes, by two passes per window (the mean, then the squared deviations
 * from it). Two passes keep it accurate where a sum-of-squares shortcut would lose
 * precision on prices near 1.5e6 units, and a flat window gives exactly zero, so the
 * bands collapse onto the middle line with no NaN. Readout values round half up at
 * the read; none of them is ever an order price. Values before `length` bars exist
 * are null and never drawn.
 *
 * Memoised on (series reference, length, width) through movingAverages.memoOnSeries.
 */

import { seriesBars } from "../bars";
import type { BarSeries } from "../bars";
import { memoOnSeries } from "./movingAverages";

export interface BollingerPoint {
    readonly mid: number;
    readonly upper: number;
    readonly lower: number;
    /** The population standard deviation of the window. */
    readonly sd: number;
}

/** Half-up rounding of a non-negative statistic to a whole unit, for the readout. */
export function roundUnits(v: number): number {
    return Math.floor(v + 0.5);
}

/** Bollinger values of `closes` at each index; null until `length` closes exist. */
export function bollingerValues(closes: readonly number[], length: number, widthTenths: number): readonly (BollingerPoint | null)[] {
    const out: (BollingerPoint | null)[] = [];
    let sum = 0;
    for (let i = 0; i < closes.length; i++) {
        sum += closes[i];
        if (i >= length) sum -= closes[i - length];
        if (i + 1 < length) {
            out.push(null);
            continue;
        }
        const mean = sum / length;
        let sq = 0;
        for (let k = i - length + 1; k <= i; k++) {
            const d = closes[k] - mean;
            sq += d * d;
        }
        const sd = Math.sqrt(Math.max(0, sq / length));
        const half = (widthTenths * sd) / 10;
        out.push({ mid: mean, upper: mean + half, lower: mean - half, sd });
    }
    return out;
}

/** Bollinger values over a series, by bucket. Memoised on (series, length, width). */
export function bollinger(series: BarSeries, length: number, widthTenths: number): ReadonlyMap<number, BollingerPoint> {
    return memoOnSeries(series, `boll:${length}:${widthTenths}`, () => {
        const bars = seriesBars(series);
        const values = bollingerValues(
            bars.map((b) => b.close),
            length,
            widthTenths,
        );
        const out = new Map<number, BollingerPoint>();
        values.forEach((v, i) => {
            if (v !== null) out.set(bars[i].bucket, v);
        });
        return out;
    });
}
