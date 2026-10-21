import { describe, expect, it } from "vitest";

import { passesBlockFilter, tickDirections, priceChanges } from "../src/components/TradeTape";
import { EMPTY_PRICE } from "../src/format";
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

describe("priceChanges", () => {
    it("computes signed dollar and percent change against the older neighbour", () => {
        // newest-first units: 1000000 ($100.00), 990000 ($99.00)
        //   i0: 1000000 vs 990000 -> +10000 units (+$1.00), +1.01%
        //   i1: oldest -> no previous
        const changes = priceChanges(tapeOf([1_000_000, 990_000]));
        expect(changes[0]).toEqual({ dollar: "+1.00", percent: "+1.01%" });
        expect(changes[1]).toEqual({ dollar: EMPTY_PRICE, percent: EMPTY_PRICE });
    });

    it("signs a down move negative and reads equal prices as a flat zero change", () => {
        // newest-first: 990000, 1000000, 1000000
        //   i0: 990000 vs 1000000 -> -10000 units (-$1.00), -1.00%
        //   i1: 1000000 vs 1000000 -> 0 ("0.00" / "0.00%")
        //   i2: oldest -> empty
        const changes = priceChanges(tapeOf([990_000, 1_000_000, 1_000_000]));
        expect(changes[0]).toEqual({ dollar: "-1.00", percent: "-1.00%" });
        expect(changes[1]).toEqual({ dollar: "0.00", percent: "0.00%" });
        expect(changes[2]).toEqual({ dollar: EMPTY_PRICE, percent: EMPTY_PRICE });
    });

    it("guards single and empty inputs", () => {
        expect(priceChanges(tapeOf([1_000_000]))).toEqual([
            { dollar: EMPTY_PRICE, percent: EMPTY_PRICE },
        ]);
        expect(priceChanges([])).toEqual([]);
    });

    it("compares each visible row to the previous VISIBLE row after filtering", () => {
        // Filtering to >200 leaves [1000000(q700), 1008000(q300), 1009000(q900)]
        // newest-first, so i0 1000000 vs 1008000 -> down, i1 1008000 vs 1009000 -> down.
        const prices = [1_000_000, 1_001_000, 1_008_000, 1_001_000, 1_009_000];
        const quantities = [700, 100, 300, 50, 900];
        const tape = tapeOf(prices).map((e, i) => ({ ...e, quantity: quantities[i] }));
        const shown = tape.filter((e) => passesBlockFilter(e.quantity, "gt200"));
        const changes = priceChanges(shown);
        expect(changes[0].dollar).toBe("-0.80"); // 1000000 vs 1008000 = -8000 units
        expect(changes[1].dollar).toBe("-0.10"); // 1008000 vs 1009000 = -1000 units
        expect(changes[2]).toEqual({ dollar: EMPTY_PRICE, percent: EMPTY_PRICE });
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
