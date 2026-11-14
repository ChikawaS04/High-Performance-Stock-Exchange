import { describe, expect, it } from "vitest";

import { emptySeries, foldFill } from "../src/bars";
import type { BarSeries } from "../src/bars";
import { emaValues, movingAverage, smaValues } from "../src/indicators/movingAverages";
import { INDICATORS } from "../src/indicators/registry";

const NS = 15_000_000_000;

function series(buckets: readonly number[], closes: readonly number[]): BarSeries {
    let s = emptySeries();
    buckets.forEach((b, i) => {
        s = foldFill(s, NS, b * NS + 1, closes[i], 1, closes[i]);
    });
    return s;
}

describe("SMA (P19-8, D8)", () => {
    it("matches hand computation and is null before the window fills", () => {
        const v = smaValues([10, 20, 30, 40, 50], 3);
        expect(v.map((p) => (p === null ? null : p.value))).toEqual([null, null, 20, 30, 40]);
    });

    it("rounds the readout half up from the exact integer sum", () => {
        const v = smaValues([1_000_000, 1_000_001], 2);
        expect(v[1]).toEqual({ value: 1_000_000.5, units: 1_000_001 });
        expect(smaValues([10, 10, 11], 3)[2]!.units).toBe(10); // 31 / 3 = 10.33
        expect(smaValues([10, 11, 11], 3)[2]!.units).toBe(11); // 32 / 3 = 10.67
    });
});

describe("EMA (P19-8, D8)", () => {
    it("seeds with the first window's SMA, then alpha = 2 / (length + 1)", () => {
        // Seed (10 + 20 + 30) / 3 = 20; alpha 0.5: 20 + 0.5 * (40 - 20) = 30, then 30 + 0.5 * (10 - 30) = 20.
        const v = emaValues([10, 20, 30, 40, 10], 3);
        expect(v.map((p) => (p === null ? null : p.value))).toEqual([null, null, 20, 30, 20]);
    });

    it("rounds the readout half up at the read", () => {
        // Seed (1 + 2) / 2 = 1.5, so the readout shows 2.
        expect(emaValues([1, 2], 2)[1]).toEqual({ value: 1.5, units: 2 });
    });

    it("draws nothing for fewer closes than the length", () => {
        expect(emaValues([10, 20], 3)).toEqual([null, null]);
        expect(smaValues([], 3)).toEqual([]);
    });
});

describe("moving averages over a series (P19-8)", () => {
    it("averages the bars present, skipping gap buckets", () => {
        const s = series([0, 1, 5], [100, 200, 600]);
        const sma = movingAverage(s, "sma", 2);
        expect([...sma.entries()]).toEqual([
            [1, { value: 150, units: 150 }],
            [5, { value: 400, units: 400 }],
        ]);
    });

    it("memoises on the series reference, kind and length", () => {
        const s = series([0, 1, 2], [100, 200, 300]);
        expect(movingAverage(s, "sma", 2)).toBe(movingAverage(s, "sma", 2));
        expect(movingAverage(s, "sma", 3)).not.toBe(movingAverage(s, "sma", 2));
        expect(movingAverage(s, "ema", 2)).not.toBe(movingAverage(s, "sma", 2));
        const next = foldFill(s, NS, 3 * NS + 1, 400, 1, 400);
        expect(movingAverage(next, "sma", 2)).not.toBe(movingAverage(s, "sma", 2));
        expect(movingAverage(next, "sma", 2).get(3)!.value).toBe(350);
    });
});

describe("moving average registry (P19-8)", () => {
    it("registers two SMA and two EMA instances with lengths 20 and 50, off by default", () => {
        const mas = INDICATORS.filter((d) => ["sma", "sma2", "ema", "ema2"].includes(d.id));
        expect(mas.map((d) => [d.id, d.label, d.params![0].default])).toEqual([
            ["sma", "SMA", 20],
            ["sma2", "SMA 2", 50],
            ["ema", "EMA", 20],
            ["ema2", "EMA 2", 50],
        ]);
        for (const d of mas) {
            expect(d).toMatchObject({ placement: "overlay", surfaces: ["page", "window"], chartTypes: ["candles", "footprint"] });
            expect(d.defaultOn).toBeUndefined();
            expect(d.params![0]).toMatchObject({ id: "length", min: 2, max: 200 });
        }
    });
});
