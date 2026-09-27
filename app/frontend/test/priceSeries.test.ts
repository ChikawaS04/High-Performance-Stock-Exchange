import { describe, it, expect } from "vitest";

import { buildPriceSeries } from "../src/priceSeries";
import type { TapeEntry } from "../src/state/reducer";

/**
 * Minimal TapeEntry fixture. buildPriceSeries reads only `timestamp` and
 * `priceCents`; every other field is given a fixed default here and overridden
 * per case, so a test can vary the ignored fields to prove they are ignored.
 * Built by hand, never via IDGenerator (P12-1: local fixtures only).
 */
function entry(partial: Partial<TapeEntry>): TapeEntry {
    return {
        tradeId: 0,
        priceCents: 10000,
        quantity: 1,
        aggressorOrderId: 0,
        passiveOrderId: 0,
        timestamp: 0,
        mine: false,
        ...partial,
    };
}

// A newest-first tape of three prints (as the reducer holds it). The newest
// print (10050) is deliberately neither the min nor the max price, so the bounds
// cannot come from the first or last element by luck.
const NEWEST_FIRST: TapeEntry[] = [
    entry({ timestamp: 300, priceCents: 10050 }), // newest
    entry({ timestamp: 200, priceCents: 10200 }), // highest price
    entry({ timestamp: 100, priceCents: 9900 }),  // oldest, lowest price
];

describe("buildPriceSeries (pure)", () => {
    it("reverses the newest-first tape into oldest-first points, without sorting", () => {
        const series = buildPriceSeries(NEWEST_FIRST);
        expect(series.points.map((p) => p.t)).toEqual([100, 200, 300]);
        expect(series.points.map((p) => p.priceCents)).toEqual([9900, 10200, 10050]);
    });

    it("computes the integer domain bounds over all prints", () => {
        const series = buildPriceSeries(NEWEST_FIRST);
        expect(series.tMin).toBe(100);
        expect(series.tMax).toBe(300);
        expect(series.cMin).toBe(9900);
        expect(series.cMax).toBe(10200);
    });

    it("yields empty points and null bounds for an empty tape, with no NaN", () => {
        const series = buildPriceSeries([]);
        expect(series.points).toEqual([]);
        expect(series.tMin).toBeNull();
        expect(series.tMax).toBeNull();
        expect(series.cMin).toBeNull();
        expect(series.cMax).toBeNull();
        // null, never NaN: a degenerate domain must not poison the scales.
        expect(Number.isNaN(series.tMin as unknown as number)).toBe(false);
        expect(Number.isNaN(series.cMin as unknown as number)).toBe(false);
    });

    it("yields one point with collapsed bounds for a single print", () => {
        const series = buildPriceSeries([entry({ timestamp: 500, priceCents: 12345 })]);
        expect(series.points).toEqual([{ t: 500, priceCents: 12345 }]);
        expect(series.tMin).toBe(500);
        expect(series.tMax).toBe(500);
        expect(series.tMin).toBe(series.tMax);
        expect(series.cMin).toBe(12345);
        expect(series.cMax).toBe(12345);
        expect(series.cMin).toBe(series.cMax);
    });

    it("collapses only the price bounds when every print shares a price", () => {
        const flat: TapeEntry[] = [
            entry({ timestamp: 300, priceCents: 10000 }),
            entry({ timestamp: 200, priceCents: 10000 }),
            entry({ timestamp: 100, priceCents: 10000 }),
        ];
        const series = buildPriceSeries(flat);
        expect(series.cMin).toBe(10000);
        expect(series.cMax).toBe(10000);
        expect(series.cMin).toBe(series.cMax);
        // time still spans a real range
        expect(series.tMin).toBe(100);
        expect(series.tMax).toBe(300);
        expect(series.tMin as number).toBeLessThan(series.tMax as number);
    });

    it("reads only priceCents and timestamp; other TapeEntry fields do not affect the result", () => {
        const plain: TapeEntry[] = [
            entry({ timestamp: 200, priceCents: 10100 }),
            entry({ timestamp: 100, priceCents: 10000 }),
        ];
        const decorated: TapeEntry[] = [
            entry({
                timestamp: 200,
                priceCents: 10100,
                tradeId: 77,
                quantity: 999,
                aggressorOrderId: 5,
                passiveOrderId: 6,
                mine: true,
                aggressorSide: "SELL",
            }),
            entry({
                timestamp: 100,
                priceCents: 10000,
                tradeId: 42,
                quantity: 3,
                aggressorOrderId: 1,
                passiveOrderId: 2,
                mine: true,
                aggressorSide: "BUY",
            }),
        ];
        expect(buildPriceSeries(decorated)).toEqual(buildPriceSeries(plain));
    });
});
