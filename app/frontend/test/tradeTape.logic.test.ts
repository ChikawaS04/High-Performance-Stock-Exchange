import { describe, expect, it } from "vitest";

import { passesBlockFilter, tickDirections } from "../src/components/TradeTape";
import type { TapeEntry } from "../src/state/reducer";

function tapeEntry(tradeId: number, pricePx: number, quantity = 1): TapeEntry {
    return {
        tradeId,
        pricePx,
        quantity,
        aggressorOrderId: -1,
        passiveOrderId: -1,
        timestamp: tradeId,
        mine: false,
    };
}

/** newest-first list of prices -> TapeEntry[] (tradeId descends with age). */
function tapeOf(prices: readonly number[]): TapeEntry[] {
    return prices.map((p, i) => tapeEntry(prices.length - i, p));
}

describe("passesBlockFilter", () => {
    it("passes everything under 'all'", () => {
        for (const q of [0, 1, 200, 500, 5000]) {
            expect(passesBlockFilter(q, "all")).toBe(true);
        }
    });

    it("is strict-greater at the >200 boundary", () => {
        expect(passesBlockFilter(199, "gt200")).toBe(false);
        expect(passesBlockFilter(200, "gt200")).toBe(false);
        expect(passesBlockFilter(201, "gt200")).toBe(true);
    });

    it("is strict-greater at the >500 boundary", () => {
        expect(passesBlockFilter(500, "gt500")).toBe(false);
        expect(passesBlockFilter(501, "gt500")).toBe(true);
    });
});

describe("tickDirections", () => {
    it("marks up, down, and flat against the older neighbour (newest-first)", () => {
        // newest-first prices: 1200, 1100, 1100, 1300
        const dirs = tickDirections(tapeOf([1200, 1100, 1100, 1300]));
        // i0: 1200 vs 1100 -> up; i1: 1100 vs 1100 -> flat; i2: 1100 vs 1300 -> down; i3: oldest -> flat
        expect(dirs).toEqual(["up", "flat", "down", "flat"]);
    });

    it("treats equal consecutive prices as flat, with no zero-tick carry", () => {
        // newest-first: 1100, 1100, 1000
        const dirs = tickDirections(tapeOf([1100, 1100, 1000]));
        // i0: 1100 vs 1100 -> flat (a repeat is not carried up); i1: 1100 vs 1000 -> up; i2: oldest -> flat
        expect(dirs).toEqual(["flat", "up", "flat"]);
    });

    it("guards the oldest print and single / empty inputs", () => {
        expect(tickDirections(tapeOf([1500000]))).toEqual(["flat"]);
        expect(tickDirections([])).toEqual([]);
    });
});

describe("filtering preserves newest-first order and the cap", () => {
    it("returns an order-preserving subsequence, never reordering or growing", () => {
        const prices = [1506000, 1501000, 1508000, 1501000, 1509000];
        const quantities = [700, 100, 300, 50, 900];
        const tape = tapeOf(prices).map((e, i) => ({ ...e, quantity: quantities[i] }));

        const shown = tape.filter((e) => passesBlockFilter(e.quantity, "gt200"));

        // same relative order as the input (newest-first), and never longer than it
        expect(shown.map((e) => e.quantity)).toEqual([700, 300, 900]);
        expect(shown.length).toBeLessThanOrEqual(tape.length);
        expect(shown.map((e) => e.tradeId)).toEqual(
            tape.filter((e) => e.quantity > 200).map((e) => e.tradeId),
        );
    });
});
