import { describe, expect, it } from "vitest";

import {
    BAR_SIZE_IDS,
    FOOTPRINT_CAP,
    barSizeNs,
    emptyBars,
    emptySeries,
    foldBars,
    foldFill,
    foldLevel,
    hasFootprint,
    seriesBars,
    withoutLevels,
} from "../src/bars";
import type { BarSeries } from "../src/bars";
import { parseServerFrame } from "../src/protocol/messages";
import type { ExecFrame, Side } from "../src/protocol/messages";
import { initialState, reducer } from "../src/state/reducer";
import type { AppState } from "../src/state/reducer";

const NS_1M = barSizeNs("1m");
const T0 = 1_750_000_020 * NS_1M; // a clock-aligned minute, well inside the safe range
const S = 1_000_000_000;

function fold1m(series: BarSeries, t: number, price: number, qty: number, side: Side | null, cvd = 0): BarSeries {
    return foldFill(series, NS_1M, t, price, qty, price, side, cvd, true);
}

describe("aggressor volume split (P19-1, D2)", () => {
    it("buy and sell volume sum to volume when every fill is sided", () => {
        let s = emptySeries();
        s = fold1m(s, T0 + S, 1_000_000, 30, "BUY");
        s = fold1m(s, T0 + 2 * S, 1_000_100, 20, "SELL");
        s = fold1m(s, T0 + 3 * S, 1_000_000, 5, "BUY");
        expect(s.open).toMatchObject({ volume: 55, buyVolume: 35, sellVolume: 20 });
        expect(s.open!.buyVolume + s.open!.sellVolume).toBe(s.open!.volume);
    });

    it("an unsided fill counts in volume and in neither side", () => {
        let s = fold1m(emptySeries(), T0 + S, 1_000_000, 30, "BUY");
        s = fold1m(s, T0 + 2 * S, 1_000_000, 10, null);
        expect(s.open).toMatchObject({ volume: 40, buyVolume: 30, sellVolume: 0, trades: 2 });
        expect(s.open!.levels).toEqual([{ price: 1_000_000, buy: 30, sell: 0 }]);
    });

    it("a buy fill adds to buyVolume and to its price row's buy", () => {
        let s = fold1m(emptySeries(), T0 + S, 1_000_000, 10, "SELL");
        s = fold1m(s, T0 + 2 * S, 1_000_000, 25, "BUY");
        expect(s.open!.buyVolume).toBe(25);
        expect(s.open!.levels).toEqual([{ price: 1_000_000, buy: 25, sell: 10 }]);
    });

    it("stamps the session delta after the bar's last fill as cvd", () => {
        let s = fold1m(emptySeries(), T0 + S, 1_000_000, 10, "BUY", 10);
        s = fold1m(s, T0 + 2 * S, 1_000_000, 4, "SELL", 6);
        expect(s.open!.cvd).toBe(6);
        s = fold1m(s, T0 + NS_1M + S, 1_000_000, 1, "SELL", 5);
        expect(s.closed[0].cvd).toBe(6);
        expect(s.open!.cvd).toBe(5);
    });
});

describe("per-price levels (P19-1, D2)", () => {
    it("keeps levels sorted by price ascending, inserting new prices in order", () => {
        let lv = foldLevel([], 1_000_200, 5, "BUY");
        lv = foldLevel(lv, 1_000_000, 3, "SELL");
        lv = foldLevel(lv, 1_000_100, 7, "BUY");
        lv = foldLevel(lv, 1_000_200, 2, "SELL");
        expect(lv).toEqual([
            { price: 1_000_000, buy: 0, sell: 3 },
            { price: 1_000_100, buy: 7, sell: 0 },
            { price: 1_000_200, buy: 5, sell: 2 },
        ]);
    });

    it("never mutates the input list", () => {
        const before = foldLevel([], 1_000_000, 5, "BUY");
        const snapshot = JSON.stringify(before);
        foldLevel(before, 1_000_000, 5, "SELL");
        foldLevel(before, 1_000_100, 5, "SELL");
        expect(JSON.stringify(before)).toBe(snapshot);
    });

    it("keeps a half-cent print under its exact key (grouping is the read's job)", () => {
        let s = fold1m(emptySeries(), T0 + S, 1_000_050, 10, "BUY");
        s = fold1m(s, T0 + 2 * S, 1_000_000, 10, "SELL");
        expect(s.open!.levels!.map((l) => l.price)).toEqual([1_000_000, 1_000_050]);
    });

    it("keeps levels on the 1m and 5m series only", () => {
        const set = foldBars(emptyBars(), T0 + S, 1_000_000, 10, 1_000_000, "BUY", 10);
        expect(BAR_SIZE_IDS.filter((id) => set[id].open!.levels !== undefined)).toEqual(["1m", "5m"]);
        expect(BAR_SIZE_IDS.filter(hasFootprint)).toEqual(["1m", "5m"]);
        for (const id of BAR_SIZE_IDS) {
            expect(set[id].open).toMatchObject({ buyVolume: 10, sellVolume: 0, cvd: 10 });
        }
    });

    it("drops levels past FOOTPRINT_CAP while totals stay", () => {
        let s = emptySeries();
        const n = FOOTPRINT_CAP + 5;
        for (let k = 0; k < n; k++) {
            s = fold1m(s, T0 + k * NS_1M + S, 1_000_000 + k * 100, 10 + k, k % 2 === 0 ? "BUY" : "SELL");
        }
        const all = seriesBars(s);
        expect(all.length).toBe(n);
        const withDetail = all.filter((b) => b.levels !== undefined);
        expect(withDetail.length).toBe(FOOTPRINT_CAP);
        // The newest FOOTPRINT_CAP bars (open included) keep detail; every older one lost it.
        expect(all.slice(0, n - FOOTPRINT_CAP).every((b) => b.levels === undefined)).toBe(true);
        expect(all.slice(n - FOOTPRINT_CAP).every((b) => b.levels !== undefined)).toBe(true);
        // Totals survive on the aged bars.
        expect(all[0]).toMatchObject({ volume: 10, buyVolume: 10, sellVolume: 0, open: 1_000_000 });
        expect(all[1]).toMatchObject({ volume: 11, buyVolume: 0, sellVolume: 11 });
    });

    it("withoutLevels keeps every total and returns a bar with no detail unchanged", () => {
        const s = fold1m(emptySeries(), T0 + S, 1_000_000, 10, "BUY", 10);
        const bare = withoutLevels(s.open!);
        expect(bare.levels).toBeUndefined();
        expect(bare).toMatchObject({ volume: 10, buyVolume: 10, cvd: 10, vwap: 1_000_000 });
        expect(withoutLevels(bare)).toBe(bare);
    });
});

describe("order flow through the reducer from raw frames (P19-1)", () => {
    function wire(over: Record<string, unknown>): ExecFrame {
        const frame = parseServerFrame(
            JSON.stringify({
                type: "EXEC",
                seq: 1,
                execType: "ORDER_PARTIALLY_FILLED",
                orderId: 7,
                tradeId: 1,
                price: 1_000_000,
                filledQuantity: 10,
                remainingQuantity: 5,
                aggressorOrderId: 7,
                passiveOrderId: 8,
                aggressorParticipantId: 1,
                passiveParticipantId: 2,
                timestamp: T0 + S,
                ...over,
            }),
        );
        if (frame === null || frame.type !== "EXEC") throw new Error("frame did not parse");
        return frame;
    }

    function apply(state: AppState, frame: ExecFrame): AppState {
        return reducer(state, { type: "FRAME", frame });
    }

    it("a wire aggressorSide reaches every bar and the session delta", () => {
        let s = apply(initialState, wire({ aggressorSide: "BUY", filledQuantity: 30 }));
        s = apply(s, wire({ aggressorSide: "SELL", filledQuantity: 12, tradeId: 2, timestamp: T0 + 2 * S }));
        expect(s.sessionDelta).toBe(18);
        for (const id of BAR_SIZE_IDS) {
            expect(s.bars[id].open).toMatchObject({ volume: 42, buyVolume: 30, sellVolume: 12, cvd: 18 });
        }
        expect(s.bars["1m"].open!.levels).toEqual([{ price: 1_000_000, buy: 30, sell: 12 }]);
    });

    it("a fill without aggressorSide from a foreign trade changes no side and no delta", () => {
        const s = apply(initialState, wire({}));
        expect(s.sessionDelta).toBe(0);
        expect(s.bars["1m"].open).toMatchObject({ volume: 10, buyVolume: 0, sellVolume: 0, cvd: 0 });
    });
});
