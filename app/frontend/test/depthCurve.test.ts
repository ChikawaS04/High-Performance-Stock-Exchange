import { describe, it, expect } from "vitest";

import { buildDepthCurve } from "../src/depth";
import type { BookState } from "../src/state/reducer";
import type { Level } from "../src/protocol/messages";

function book(partial: Partial<BookState>): BookState {
    return { bestBid: -1, bestAsk: -1, bids: [], asks: [], timestamp: 0, ...partial };
}

const BIDS: Level[] = [
    [1500000, 10],
    [1499000, 4],
    [1498000, 6],
];
const ASKS: Level[] = [
    [1502500, 5],
    [1505000, 3],
];

function nonDecreasing(xs: readonly number[]): boolean {
    for (let i = 1; i < xs.length; i++) {
        if (xs[i] < xs[i - 1]) return false;
    }
    return true;
}

function nonIncreasing(xs: readonly number[]): boolean {
    for (let i = 1; i < xs.length; i++) {
        if (xs[i] > xs[i - 1]) return false;
    }
    return true;
}

describe("buildDepthCurve (pure)", () => {
    it("emits 2n-1 step-corner points per non-empty side", () => {
        const m = buildDepthCurve(book({ bids: BIDS, asks: ASKS }));
        expect(m.bids).toHaveLength(2 * BIDS.length - 1); // 3 levels -> 5
        expect(m.asks).toHaveLength(2 * ASKS.length - 1); // 2 levels -> 3
    });

    it("builds the exact staircase, touch outward, on the bid side", () => {
        const m = buildDepthCurve(book({ bids: BIDS, asks: ASKS }));
        // cum(BIDS) = [10, 14, 20]; corners walk price down, stepping depth up
        expect(m.bids.map((p) => p.pricePx)).toEqual([1500000, 1499000, 1499000, 1498000, 1498000]);
        expect(m.bids.map((p) => p.cumQty)).toEqual([10, 10, 14, 14, 20]);
    });

    it("builds the exact staircase, touch outward, on the ask side", () => {
        const m = buildDepthCurve(book({ bids: BIDS, asks: ASKS }));
        // cum(ASKS) = [5, 8]
        expect(m.asks.map((p) => p.pricePx)).toEqual([1502500, 1505000, 1505000]);
        expect(m.asks.map((p) => p.cumQty)).toEqual([5, 5, 8]);
    });

    it("starts each side at the touch and ends at the side total", () => {
        const m = buildDepthCurve(book({ bids: BIDS, asks: ASKS }));
        expect(m.bids[0]).toEqual({ pricePx: 1500000, cumQty: 10 }); // best bid, its own qty
        expect(m.bids[m.bids.length - 1].cumQty).toBe(20); // full bid depth
        expect(m.asks[0]).toEqual({ pricePx: 1502500, cumQty: 5 }); // best ask, its own qty
        expect(m.asks[m.asks.length - 1].cumQty).toBe(8); // full ask depth
    });

    it("keeps cumulation monotonic and prices monotonic per side", () => {
        const m = buildDepthCurve(book({ bids: BIDS, asks: ASKS }));
        expect(nonDecreasing(m.bids.map((p) => p.cumQty))).toBe(true);
        expect(nonDecreasing(m.asks.map((p) => p.cumQty))).toBe(true);
        expect(nonIncreasing(m.bids.map((p) => p.pricePx))).toBe(true); // bids highest-first
        expect(nonDecreasing(m.asks.map((p) => p.pricePx))).toBe(true); // asks lowest-first
    });

    it("caps each side to the depth window, nearest the touch", () => {
        const m = buildDepthCurve(book({ bids: BIDS, asks: ASKS }), 2);
        // best two bids only: cum [10, 14] -> corners (1500000,10)(1499000,10)(1499000,14)
        expect(m.bids.map((p) => p.pricePx)).toEqual([1500000, 1499000, 1499000]);
        expect(m.bids.map((p) => p.cumQty)).toEqual([10, 10, 14]);
        expect(m.asks).toHaveLength(3); // asks already within the cap
    });

    it("handles a one-sided book with no NaN and an empty opposite side", () => {
        const asksOnly = buildDepthCurve(book({ bestAsk: 1502500, asks: ASKS }));
        expect(asksOnly.bids).toEqual([]);
        expect(asksOnly.asks).toHaveLength(3);
        for (const p of asksOnly.asks) {
            expect(Number.isNaN(p.cumQty)).toBe(false);
        }

        const bidsOnly = buildDepthCurve(book({ bestBid: 1500000, bids: [[1500000, 7]] }));
        expect(bidsOnly.asks).toEqual([]);
        expect(bidsOnly.bids).toEqual([{ pricePx: 1500000, cumQty: 7 }]); // single level -> one point
    });

    it("handles an empty book as two empty sides", () => {
        const m = buildDepthCurve(book({}));
        expect(m.bids).toEqual([]);
        expect(m.asks).toEqual([]);
    });
});
