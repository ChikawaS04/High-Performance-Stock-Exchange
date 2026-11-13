import { describe, expect, it } from "vitest";

import {
    BAR_CAP,
    barSizeNs,
    bucketOf,
    emptyBars,
    emptySeries,
    foldBars,
    foldFill,
    seriesBars,
    vwapUnits,
    VWAP_UNSET,
} from "../src/bars";
import type { BarSeries } from "../src/bars";

const S = 1_000_000_000;
const NS_15 = barSizeNs("15s");
// A clock-aligned base: an exact multiple of 5m, so every size's bucket starts here.
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

function fold(series: BarSeries, t: number, price: number, qty = 10, vwap = price): BarSeries {
    return foldFill(series, NS_15, t, price, qty, vwap);
}

describe("foldFill (P17-2)", () => {
    it("opens a bar on the first fill with open = high = low = close", () => {
        const s = fold(emptySeries(), T0 + 2 * S, 1_000_000, 20);
        expect(s.open).toEqual({
            bucket: bucketOf(T0 + 2 * S, NS_15),
            open: 1_000_000,
            high: 1_000_000,
            low: 1_000_000,
            close: 1_000_000,
            volume: 20,
            trades: 1,
            vwap: 1_000_000,
        });
        expect(s.closed).toEqual([]);
    });

    it("moves high and low and sets close, volume and trades inside one bucket", () => {
        let s = fold(emptySeries(), T0 + 1 * S, 1_000_000, 10);
        s = fold(s, T0 + 3 * S, 1_000_300, 5);
        s = fold(s, T0 + 5 * S, 999_800, 7);
        expect(s.open).toMatchObject({ open: 1_000_000, high: 1_000_300, low: 999_800, close: 999_800, volume: 22, trades: 3 });
        expect(s.closed).toHaveLength(0);
    });

    it("an in-bucket fill does not copy closed (same array reference)", () => {
        let s = fold(emptySeries(), T0, 1_000_000);
        s = fold(s, T0 + 15 * S, 1_000_100);
        const closedBefore = s.closed;
        s = fold(s, T0 + 16 * S, 1_000_200);
        expect(s.closed).toBe(closedBefore);
    });

    it("a fill in the next bucket closes the open bar and opens a new one", () => {
        let s = fold(emptySeries(), T0 + 1 * S, 1_000_000);
        s = fold(s, T0 + 16 * S, 1_000_500);
        expect(s.closed).toHaveLength(1);
        expect(s.closed[0].close).toBe(1_000_000);
        expect(s.open?.open).toBe(1_000_500);
        expect(s.open!.bucket - s.closed[0].bucket).toBe(1);
    });

    it("aligns buckets to the clock: :14.999 and :15.000 fall in different 15s bars", () => {
        let s = fold(emptySeries(), T0 + 14.999 * S, 1_000_000);
        s = fold(s, T0 + 15 * S, 1_000_100);
        expect(s.closed).toHaveLength(1);
        expect(s.open!.bucket * NS_15).toBe(T0 + 15 * S);
    });

    it("a fill several buckets later leaves a gap (no synthetic bars)", () => {
        let s = fold(emptySeries(), T0, 1_000_000);
        s = fold(s, T0 + 4 * 15 * S, 1_000_100);
        expect(s.closed).toHaveLength(1);
        expect(s.open!.bucket - s.closed[0].bucket).toBe(4);
    });

    it("folds an older-bucket fill into the open bar rather than reopening a closed one", () => {
        let s = fold(emptySeries(), T0, 1_000_000);
        s = fold(s, T0 + 30 * S, 1_000_100);
        const openBucket = s.open!.bucket;
        s = fold(s, T0 + 1 * S, 999_000, 3);
        expect(s.closed).toHaveLength(1);
        expect(s.open).toMatchObject({ bucket: openBucket, low: 999_000, close: 999_000, trades: 2 });
    });

    it("caps closed at BAR_CAP, dropping the oldest at 501", () => {
        let s = emptySeries();
        for (let i = 0; i <= BAR_CAP + 1; i++) s = fold(s, T0 + i * 15 * S, 1_000_000 + i);
        // BAR_CAP + 2 fills: BAR_CAP + 1 closed bars were made, the oldest dropped.
        expect(s.closed).toHaveLength(BAR_CAP);
        expect(s.closed[0].open).toBe(1_000_001);
        expect(s.open!.open).toBe(1_000_000 + BAR_CAP + 1);
    });
});

describe("foldBars (P17-2)", () => {
    it("gives consistent OHLCV across the four sizes", () => {
        let set = emptyBars();
        const fills: [number, number, number][] = [];
        for (let i = 0; i < 24; i++) fills.push([T0 + i * 2.5 * S, 1_000_000 + ((i * 7) % 5) * 100, 1 + (i % 4)]);
        for (const [t, p, q] of fills) set = foldBars(set, t, p, q, p);

        const one = seriesBars(set["1m"]);
        expect(one).toHaveLength(1);
        const fives = seriesBars(set["5s"]);
        const sum = fives.reduce((a, b) => a + b.volume, 0);
        expect(one[0].volume).toBe(sum);
        expect(one[0].trades).toBe(fives.reduce((a, b) => a + b.trades, 0));
        expect(one[0].high).toBe(Math.max(...fives.map((b) => b.high)));
        expect(one[0].low).toBe(Math.min(...fives.map((b) => b.low)));
        expect(one[0].open).toBe(fives[0].open);
        expect(one[0].close).toBe(fives[fives.length - 1].close);
        expect(seriesBars(set["5m"])[0].volume).toBe(sum);
        expect(seriesBars(set["15s"]).reduce((a, b) => a + b.volume, 0)).toBe(sum);
    });
});

describe("vwapUnits (P17-2, D4)", () => {
    it("is VWAP_UNSET at zero volume", () => {
        expect(vwapUnits(0n, 0)).toBe(VWAP_UNSET);
    });

    it("rounds half up to the nearest unit", () => {
        // (100 * 1 + 101 * 1) / 2 = 100.5 -> 101
        expect(vwapUnits(201n, 2)).toBe(101);
        // 100.333... -> 100
        expect(vwapUnits(301n, 3)).toBe(100);
        // 100.666... -> 101
        expect(vwapUnits(302n, 3)).toBe(101);
    });

    it("stays exact with a notional above Number.MAX_SAFE_INTEGER", () => {
        const price = 14_466_800n;
        const qty = 10_000;
        const fills = 100_000; // notional 1.4e16, past 9.0e15
        const notional = price * BigInt(qty) * BigInt(fills);
        expect(notional > BigInt(Number.MAX_SAFE_INTEGER)).toBe(true);
        expect(vwapUnits(notional + 1n, qty * fills)).toBe(14_466_800);
    });
});
