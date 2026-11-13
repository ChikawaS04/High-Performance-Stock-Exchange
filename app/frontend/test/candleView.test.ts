import { describe, expect, it } from "vitest";

import { emptySeries, foldFill } from "../src/bars";
import type { Bar, BarSeries } from "../src/bars";
import {
    MAX_SLOT,
    MIN_SLOT,
    bodyWidth,
    buildCandleView,
    candleTimeTicks,
    hitSlot,
    lineVwapSteps,
    nearestIndex,
    placeVwapTag,
    priceAt,
    slotCentre,
} from "../src/candleView";

const S = 1_000_000_000;
const NS = 15 * S;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);
const B0 = T0 / NS;

/** A series from (bucket offset, price) fills, each fill its own bar unless buckets repeat. */
function seriesOf(fills: readonly [number, number][]): BarSeries {
    let s = emptySeries();
    for (const [k, p] of fills) s = foldFill(s, NS, T0 + k * NS + S, p, 10, p);
    return s;
}

function bar(partial: Partial<Bar>): Bar {
    return { bucket: B0, open: 100, high: 100, low: 100, close: 100, volume: 1, trades: 1, vwap: 100, ...partial };
}

describe("buildCandleView (P17-6)", () => {
    it("is null for an empty series or a zero-width plot", () => {
        expect(buildCandleView(emptySeries(), 600)).toBeNull();
        expect(buildCandleView(seriesOf([[0, 1_000_000]]), 0)).toBeNull();
    });

    it("shows only the newest bars that fit at the 5px minimum slot", () => {
        const fills: [number, number][] = [];
        for (let i = 0; i < 300; i++) fills.push([i, 1_000_000 + (i % 7) * 100]);
        const view = buildCandleView(seriesOf(fills), 500)!;
        const maxSlots = Math.floor(500 / MIN_SLOT);
        expect(view.slotCount).toBe(maxSlots);
        expect(view.bars).toHaveLength(maxSlots);
        expect(view.lastBucket).toBe(B0 + 299);
        expect(view.firstBucket).toBe(B0 + 300 - maxSlots);
        expect(view.slotW).toBeCloseTo(MIN_SLOT, 9);
        expect(view.offset).toBeCloseTo(0, 9);
    });

    it("keeps a slot for every gap bucket, and no bar in it", () => {
        const view = buildCandleView(seriesOf([[0, 1_000_000], [3, 1_000_100]]), 600)!;
        expect(view.slotCount).toBe(4);
        expect(view.bars.map((b) => b.slot)).toEqual([0, 3]);
    });

    it("caps the slot width at MAX_SLOT and right-aligns the slots", () => {
        const view = buildCandleView(seriesOf([[0, 1_000_000], [1, 1_000_100]]), 600)!;
        expect(view.slotW).toBe(MAX_SLOT);
        expect(view.offset).toBe(600 - 2 * MAX_SLOT);
        // The newest slot's right edge is the plot's right edge.
        expect(slotCentre(view, 1) + view.slotW / 2).toBe(600);
        expect(bodyWidth(view)).toBeCloseTo(MAX_SLOT * 0.7, 9);
    });

    it("keeps the body at least 1px wide", () => {
        expect(bodyWidth({ slotW: 1 } as never)).toBe(1);
    });

    it("covers the visible highs and lows with padding", () => {
        let s = emptySeries();
        s = foldFill(s, NS, T0 + S, 1_000_000, 1, 0);
        s = foldFill(s, NS, T0 + 2 * S, 1_010_000, 1, 0);
        s = foldFill(s, NS, T0 + 3 * S, 990_000, 1, 0);
        const view = buildCandleView(s, 600)!;
        expect(view.dMin).toBeLessThan(990_000);
        expect(view.dMax).toBeGreaterThan(1_010_000);
        // 5% of the 20,000 span each side at least.
        expect(view.dMin).toBeLessThanOrEqual(989_000);
        expect(view.dMax).toBeGreaterThanOrEqual(1_011_000);
    });

    it("guards a single bar and an all-equal series (no NaN, a non-empty domain)", () => {
        for (const s of [seriesOf([[0, 1_000_000]]), seriesOf([[0, 1_000_000], [1, 1_000_000], [2, 1_000_000]])]) {
            const view = buildCandleView(s, 600)!;
            expect(Number.isFinite(view.dMin)).toBe(true);
            expect(Number.isFinite(view.dMax)).toBe(true);
            expect(view.dMax).toBeGreaterThan(view.dMin);
            expect(view.dMin).toBeLessThan(1_000_000);
            expect(view.dMax).toBeGreaterThan(1_000_000);
            expect(Number.isFinite(view.slotW)).toBe(true);
        }
    });

    it("reports the visible maximum volume", () => {
        let s = emptySeries();
        s = foldFill(s, NS, T0 + S, 100, 7, 100);
        s = foldFill(s, NS, T0 + NS + S, 100, 3, 100);
        s = foldFill(s, NS, T0 + NS + 2 * S, 100, 9, 100);
        expect(buildCandleView(s, 600)!.maxVolume).toBe(12);
    });
});

describe("candleTimeTicks (P17-6)", () => {
    it("ticks bucket starts on round clock steps that are whole bars", () => {
        const fills: [number, number][] = [];
        for (let i = 0; i < 40; i++) fills.push([i, 1_000_000]);
        const view = buildCandleView(seriesOf(fills), 600)!;
        const ticks = candleTimeTicks(view, NS, 5);
        expect(ticks.length).toBeGreaterThan(0);
        expect(ticks.length).toBeLessThanOrEqual(6);
        const step = ticks.length > 1 ? ticks[1].t - ticks[0].t : 0;
        expect(step % NS).toBe(0);
        for (const k of ticks) {
            // Round clock times: a whole number of steps from the epoch.
            expect(Math.round(k.t / NS) % Math.round(step / NS || 1)).toBe(0);
            expect(k.slot).toBe(Math.round(k.t / NS) - view.firstBucket);
        }
    });
});

describe("hitSlot and priceAt (P17-10)", () => {
    const view = buildCandleView(seriesOf([[0, 1_000_000], [2, 1_000_100]]), 300)!;
    // 3 slots at MAX_SLOT, right-aligned: offset 300 - 48 = 252.

    it("maps x to the right slot at its left edge, centre and right edge", () => {
        expect(hitSlot(view, view.offset)!.slot).toBe(0);
        expect(hitSlot(view, slotCentre(view, 0))!.slot).toBe(0);
        expect(hitSlot(view, view.offset + view.slotW)!.slot).toBe(1);
        expect(hitSlot(view, view.offset + 3 * view.slotW)!.slot).toBe(2);
    });

    it("returns null outside the slots", () => {
        expect(hitSlot(view, view.offset - 1)).toBeNull();
        expect(hitSlot(view, 301)).toBeNull();
    });

    it("returns the gap, with no bar, for an empty slot", () => {
        const hit = hitSlot(view, slotCentre(view, 1))!;
        expect(hit.slot).toBe(1);
        expect(hit.bucket).toBe(B0 + 1);
        expect(hit.bar).toBeNull();
        expect(hitSlot(view, slotCentre(view, 2))!.bar?.close).toBe(1_000_100);
    });

    it("maps y to the domain top and bottom of the price area, and null outside", () => {
        expect(priceAt(20, 20, 200, 1000, 2000)).toBe(2000);
        expect(priceAt(220, 20, 200, 1000, 2000)).toBe(1000);
        expect(priceAt(120, 20, 200, 1000, 2000)).toBe(1500);
        expect(priceAt(19, 20, 200, 1000, 2000)).toBeNull();
        expect(priceAt(221, 20, 200, 1000, 2000)).toBeNull();
    });

    it("nearestIndex picks the closest x, -1 for none", () => {
        expect(nearestIndex([10, 20, 40], 33)).toBe(2);
        expect(nearestIndex([10, 20, 40], 14)).toBe(0);
        expect(nearestIndex([], 5)).toBe(-1);
    });
});

describe("lineVwapSteps (P17-8)", () => {
    const FIVE = 5 * S;
    const b5 = (k: number, vwap: number): Bar => bar({ bucket: T0 / FIVE + k, vwap });

    it("places steps at 5s bucket starts on the epoch domain, clipped to the window", () => {
        const bars = [b5(0, 100), b5(1, 110), b5(2, 120), b5(5, 130)];
        const tMin = T0 + FIVE + 2 * S; // inside bucket 1
        const tMax = T0 + 3 * FIVE; // before bucket 5
        expect(lineVwapSteps(bars, FIVE, tMin, tMax)).toEqual([
            { t: tMin, vwap: 110 },
            { t: T0 + 2 * FIVE, vwap: 120 },
            { t: tMax, vwap: 120 },
        ]);
    });

    it("starts at the first bar inside the window when none began before it", () => {
        const pts = lineVwapSteps([b5(3, 90)], FIVE, T0, T0 + 10 * FIVE);
        expect(pts[0]).toEqual({ t: T0, vwap: 90 });
        expect(pts[pts.length - 1]).toEqual({ t: T0 + 10 * FIVE, vwap: 90 });
    });

    it("is empty with no bars", () => {
        expect(lineVwapSteps([], FIVE, T0, T0 + FIVE)).toEqual([]);
    });
});

describe("placeVwapTag (P17-8)", () => {
    it("keeps the VWAP tag where it is when it clears the last-price tag", () => {
        expect(placeVwapTag(100, 150, 0, 300, 18)).toBe(150);
    });

    it("offsets a full tag height away when the two tags would overlap", () => {
        expect(placeVwapTag(100, 95, 0, 300, 18)).toBe(82);
        expect(placeVwapTag(100, 108, 0, 300, 18)).toBe(118);
    });

    it("pins an off-scale VWAP to the nearest axis edge", () => {
        expect(placeVwapTag(150, -500, 0, 300, 18)).toBe(9);
        expect(placeVwapTag(150, 900, 0, 300, 18)).toBe(291);
    });

    it("flips to the other side when the offset would leave the axis", () => {
        expect(placeVwapTag(10, 5, 0, 300, 18)).toBe(10 + 18);
        expect(placeVwapTag(290, 296, 0, 300, 18)).toBe(290 - 18);
    });
});
