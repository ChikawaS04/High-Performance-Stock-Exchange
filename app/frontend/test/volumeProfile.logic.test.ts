import { describe, expect, it } from "vitest";

import {
    pocIndex,
    profileRows,
    profileStats,
    rowStepTicks,
    tickLadder,
    tickRowOf,
    valueAreaIndices,
} from "../src/indicators/volumeProfile";

const BASE = 1_000_000;
const p = (k: number): number => BASE + k * 100;
const vap = (entries: readonly (readonly [number, number])[]): ReadonlyMap<number, number> =>
    new Map(entries.map(([k, q]) => [p(k), q]));

describe("tick ladder (P19-3, D3)", () => {
    it("is null when nothing has traded", () => {
        expect(tickLadder(new Map())).toBeNull();
        expect(profileStats(new Map())).toBeNull();
    });

    it("fills untraded ticks between the extremes with zero", () => {
        const l = tickLadder(vap([[0, 5], [3, 7]]))!;
        expect(l.base).toBe(p(0));
        expect(l.volumes).toEqual([5, 0, 0, 7]);
        expect(l.total).toBe(12);
    });

    it("folds a half-cent key into the tick row below it", () => {
        expect(tickRowOf(BASE + 50)).toBe(BASE);
        const l = tickLadder(new Map([[BASE, 4], [BASE + 50, 6], [BASE + 100, 1]]))!;
        expect(l.volumes).toEqual([10, 1]);
    });
});

describe("point of control (P19-3, D4)", () => {
    it("is the single peak", () => {
        expect(pocIndex([5, 30, 100, 20, 10])).toBe(2);
    });

    it("breaks a tie between two equal peaks toward the middle of the range", () => {
        // Indices 0 and 2 of four: 2 is nearer the middle (1.5).
        expect(pocIndex([50, 10, 50, 10])).toBe(2);
    });

    it("takes the lower price when two equal peaks are equally far from the middle", () => {
        expect(pocIndex([50, 10, 10, 50])).toBe(0);
        expect(pocIndex([0, 40, 1, 1, 40, 0])).toBe(1);
    });

    it("is the middle tick of a flat distribution", () => {
        expect(pocIndex([10, 10, 10, 10, 10])).toBe(2);
    });
});

describe("value area (P19-3, D4)", () => {
    it("adds the larger pair of rows first", () => {
        // POC 100; below 30 + 5 = 35 beats above 20 + 10 = 30; 135 of 165 >= 70%.
        expect(valueAreaIndices([5, 30, 100, 20, 10], 2, 165)).toEqual({ lo: 0, hi: 2 });
    });

    it("sends a tied pair up", () => {
        expect(valueAreaIndices([10, 20, 100, 20, 10], 2, 160)).toEqual({ lo: 2, hi: 4 });
    });

    it("stops at exactly 70% (the POC alone here)", () => {
        expect(valueAreaIndices([15, 70, 15], 1, 100)).toEqual({ lo: 1, hi: 1 });
    });

    it("takes the remaining single row at an edge", () => {
        // POC at the top edge: only rows below remain.
        expect(valueAreaIndices([30, 10, 60], 2, 100)).toEqual({ lo: 0, hi: 2 });
        // One row above, two below: above 10 against below 25, below wins.
        expect(valueAreaIndices([20, 5, 40, 10], 2, 75)).toEqual({ lo: 0, hi: 2 });
    });

    it("covers the whole of a flat distribution", () => {
        expect(valueAreaIndices([10, 10, 10, 10, 10], 2, 50)).toEqual({ lo: 0, hi: 4 });
    });

    it("profileStats reports POC and value area as prices", () => {
        const s = profileStats(vap([[0, 5], [1, 30], [2, 100], [3, 20], [4, 10]]))!;
        expect(s.poc).toBe(p(2));
        expect(s.vaLow).toBe(p(0));
        expect(s.vaHigh).toBe(p(2));
    });
});

describe("display rows (P19-3, D4)", () => {
    it("picks the smallest step whose row is at least 2px", () => {
        expect(rowStepTicks(10)).toBe(1);
        expect(rowStepTicks(2)).toBe(1);
        expect(rowStepTicks(1)).toBe(2);
        expect(rowStepTicks(0.3)).toBe(10);
        expect(rowStepTicks(0)).toBe(5000);
    });

    it("keeps one row per tick when there is room", () => {
        const s = profileStats(vap([[0, 5], [1, 30], [2, 100]]))!;
        const l = profileRows(s, p(0), p(10), 1000);
        expect(l.stepTicks).toBe(1);
        expect(l.rows.map((r) => [r.lo, r.volume])).toEqual([
            [p(0), 5],
            [p(1), 30],
            [p(2), 100],
        ]);
        expect(l.maxVolume).toBe(100);
    });

    it("groups ticks into aligned rows at small heights", () => {
        // 100 ticks over 30px: 0.3 px per tick, so rows of 10 ticks.
        const s = profileStats(vap([[3, 5], [9, 5], [10, 20], [25, 1]]))!;
        const l = profileRows(s, p(0), p(100), 30);
        expect(l.stepTicks).toBe(10);
        expect(l.rows.map((r) => [r.lo, r.hi, r.volume])).toEqual([
            [p(0), p(10), 10],
            [p(10), p(20), 20],
            [p(20), p(30), 1],
        ]);
        expect(l.rows.map((r) => r.poc)).toEqual([false, true, false]);
    });

    it("flags rows overlapping the value area", () => {
        // POC 40 of 82 is under 70%; the tied pairs (20 each) send the area up to ticks 7
        // and 8 (8 untraded): 60 of 82.
        const s = profileStats(vap([[0, 1], [5, 20], [6, 40], [7, 20], [12, 1]]))!;
        expect([s.vaLow, s.vaHigh]).toEqual([p(6), p(8)]);
        const l = profileRows(s, p(0), p(20), 1000);
        expect(l.rows.filter((r) => r.inValue).map((r) => r.lo)).toEqual([p(6), p(7)]);
        expect(l.rows.filter((r) => r.poc).map((r) => r.lo)).toEqual([p(6)]);
    });

    it("returns only rows inside the domain but scales by the whole session", () => {
        const s = profileStats(vap([[0, 10], [50, 200]]))!;
        const l = profileRows(s, p(0), p(10), 1000);
        expect(l.rows.map((r) => r.lo)).toEqual([p(0)]);
        expect(l.maxVolume).toBe(200);
    });
});
