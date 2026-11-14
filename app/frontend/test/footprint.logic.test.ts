import { describe, expect, it } from "vitest";

import { foldLevel } from "../src/bars";
import type { Bar, PriceLevel } from "../src/bars";
import { buildCandleView } from "../src/candleView";
import { CANDLE_SLOT, clampSlotW, zoomCandles } from "../src/chartViewport";
import {
    FOOTPRINT_SLOT,
    IMBALANCE_MIN_QTY,
    IMBALANCE_RATIO,
    footprintBar,
    footprintColumns,
    footprintRowStep,
    groupRows,
    imbalances,
    isImbalance,
    rowAt,
    rowLoOf,
} from "../src/footprint";
import { emptySeries, foldFill } from "../src/bars";

const BASE = 1_000_000;
const p = (k: number): number => BASE + k * 100;

function bar(levels: readonly PriceLevel[] | undefined): Bar {
    const base: Bar = {
        bucket: 1,
        open: BASE,
        high: BASE,
        low: BASE,
        close: BASE,
        volume: 0,
        trades: 0,
        vwap: BASE,
        buyVolume: 0,
        sellVolume: 0,
        cvd: 0,
    };
    return levels === undefined ? base : { ...base, levels };
}

function levels(...rows: readonly (readonly [k: number, sell: number, buy: number])[]): readonly PriceLevel[] {
    let lv: readonly PriceLevel[] = [];
    for (const [k, sell, buy] of rows) {
        if (sell > 0) lv = foldLevel(lv, p(k), sell, "SELL");
        if (buy > 0) lv = foldLevel(lv, p(k), buy, "BUY");
    }
    return lv;
}

describe("footprint rows (P19-5, D6)", () => {
    it("one row per tick, contiguous, row index ascending with price", () => {
        const rows = footprintBar(bar(levels([0, 5, 1], [2, 0, 7])), 1).rows;
        expect(rows.map((r) => [r.r, r.lo, r.sell, r.buy])).toEqual([
            [0, p(0), 5, 1],
            [1, p(1), 0, 0],
            [2, p(2), 0, 7],
        ]);
    });

    it("groups ticks into aligned rows and folds a half-cent key into its row", () => {
        const lv = [...levels([3, 1, 0], [4, 0, 2], [7, 3, 0])];
        lv.splice(1, 0, { price: p(3) + 50, buy: 10, sell: 0 });
        const rows = groupRows(lv, 5);
        expect(rows.map((r) => [r.lo, r.hi, r.sell, r.buy])).toEqual([
            [p(0), p(5), 1, 12],
            [p(5), p(10), 3, 0],
        ]);
        expect(rowLoOf(p(3) + 50, 5)).toBe(p(0));
    });

    it("picks the smallest step giving rows at least 13px tall", () => {
        expect(footprintRowStep(20)).toBe(1);
        expect(footprintRowStep(13)).toBe(1);
        expect(footprintRowStep(6.5)).toBe(2);
        expect(footprintRowStep(2.7)).toBe(5);
        expect(footprintRowStep(1)).toBe(20);
    });

    it("finds the row holding a price", () => {
        const fb = footprintBar(bar(levels([0, 5, 1], [2, 0, 7])), 1);
        expect(rowAt(fb, p(2) + 30)?.r).toBe(2);
        expect(rowAt(fb, p(5))).toBeNull();
    });
});

describe("diagonal imbalance (P19-5, D6)", () => {
    it("flags a buy at exactly 3x the sell one row below, and not just under", () => {
        expect(IMBALANCE_RATIO).toBe(3);
        expect(isImbalance(30, 10)).toBe(true);
        expect(isImbalance(29, 10)).toBe(false);
        // buy[1] = 30 against sell[0] = 10: a buy imbalance at row 1.
        expect(imbalances([{ sell: 10, buy: 0 }, { sell: 0, buy: 30 }, { sell: 0, buy: 0 }])).toEqual([null, "buy", null]);
        expect(imbalances([{ sell: 10, buy: 0 }, { sell: 0, buy: 29 }])).toEqual([null, null]);
    });

    it("flags a sell at 3x the buy one row above", () => {
        // sell[0] = 60 against buy[1] = 20.
        expect(imbalances([{ sell: 60, buy: 0 }, { sell: 0, buy: 20 }])).toEqual(["sell", null]);
    });

    it("compares diagonally, not across the same row", () => {
        // Same-row 30 x 10 is not an imbalance when the diagonal opposites are large.
        expect(imbalances([{ sell: 40, buy: 0 }, { sell: 10, buy: 30 }, { sell: 0, buy: 25 }])).toEqual([null, null, null]);
    });

    it("an empty opposite flags only at IMBALANCE_MIN_QTY", () => {
        expect(isImbalance(IMBALANCE_MIN_QTY, 0)).toBe(true);
        expect(isImbalance(IMBALANCE_MIN_QTY - 1, 0)).toBe(false);
        // The bar's lowest row has no row below: its buy side faces an empty opposite.
        expect(imbalances([{ sell: 0, buy: IMBALANCE_MIN_QTY }, { sell: 0, buy: 0 }])).toEqual(["buy", null]);
        expect(imbalances([{ sell: 0, buy: IMBALANCE_MIN_QTY - 1 }])).toEqual([null]);
    });

    it("never flags a side with no shares, and with an infinite minimum never flags an empty opposite", () => {
        expect(isImbalance(0, 0)).toBe(false);
        expect(isImbalance(500, 0, 3, Infinity)).toBe(false);
    });

    it("a row imbalanced both ways takes the larger side", () => {
        expect(imbalances([{ sell: 250, buy: 300 }])).toEqual(["buy"]);
    });

    it("tags rows in footprintBar", () => {
        const fb = footprintBar(bar(levels([0, 10, 0], [1, 0, 30])), 1);
        expect(fb.rows.map((r) => r.imbalance)).toEqual([null, "buy"]);
    });
});

describe("footprint columns and detail (P19-5)", () => {
    it("bars without levels are noDetail and have no rows", () => {
        const fb = footprintBar(bar(undefined), 1);
        expect(fb.noDetail).toBe(true);
        expect(fb.rows).toEqual([]);
        expect(footprintBar(bar([]), 1)).toMatchObject({ noDetail: false, rows: [] });
    });

    it("column count from the width at the 64px minimum", () => {
        expect(FOOTPRINT_SLOT).toEqual({ min: 64, max: 160 });
        expect(footprintColumns(640)).toBe(10);
        expect(footprintColumns(639)).toBe(9);
        expect(footprintColumns(640, 160)).toBe(4);
        expect(footprintColumns(640, 20)).toBe(10);
        expect(footprintColumns(30)).toBe(1);
    });
});

describe("slot bounds (P19-5)", () => {
    it("candle bounds are unchanged by default", () => {
        expect(CANDLE_SLOT).toEqual({ min: 3, max: 40 });
        expect(clampSlotW(100)).toBe(40);
        expect(clampSlotW(100, FOOTPRINT_SLOT)).toBe(100);
        expect(clampSlotW(20, FOOTPRINT_SLOT)).toBe(64);
        expect(clampSlotW(500, FOOTPRINT_SLOT)).toBe(160);
    });

    it("zoom clamps to the bounds it is given", () => {
        const extent = { oldest: 0, newest: 10 };
        expect(zoomCandles({ slotW: 64, rightBucket: null }, 64, 1, 640, extent, FOOTPRINT_SLOT).slotW).toBe(64);
        expect(zoomCandles({ slotW: 150, rightBucket: null }, 150, -5, 640, extent, FOOTPRINT_SLOT).slotW).toBe(160);
    });

    it("buildCandleView lays out footprint-width slots", () => {
        const NS = 60_000_000_000;
        let s = emptySeries();
        for (let k = 0; k < 30; k++) s = foldFill(s, NS, (1000 + k) * NS + 1, BASE + k * 100, 1, BASE);
        const v = buildCandleView(s, 640, 6, { slotW: 64, rightBucket: null }, FOOTPRINT_SLOT)!;
        expect(v.slotW).toBe(64);
        expect(v.slotCount).toBe(10);
        expect(v.bars[v.bars.length - 1].bar.bucket).toBe(1029);
    });
});
