import { describe, expect, it } from "vitest";

import { cvdDomain, cvdVertices, cvdY, formatDelta } from "../src/indicators/cvd";
import { layoutPanes } from "../src/indicators/layoutPanes";
import { INDICATORS } from "../src/indicators/registry";
import type { IndicatorDescriptor } from "../src/indicators/registry";
import { formatQty } from "../src/format";

describe("CVD vertices (P19-4, D5)", () => {
    it("draws each sample at its slot", () => {
        expect(cvdVertices([{ slot: 0, cvd: 5 }, { slot: 1, cvd: -3 }])).toEqual([
            { slot: 0, value: 5 },
            { slot: 1, value: -3 },
        ]);
    });

    it("holds flat across a gap, then moves at the next bar", () => {
        expect(cvdVertices([{ slot: 2, cvd: 10 }, { slot: 6, cvd: 4 }])).toEqual([
            { slot: 2, value: 10 },
            { slot: 5, value: 10 },
            { slot: 6, value: 4 },
        ]);
    });

    it("is empty for no samples", () => {
        expect(cvdVertices([])).toEqual([]);
    });
});

describe("CVD scale (P19-4, amended at greenlight)", () => {
    const s = (...v: number[]) => v.map((cvd, slot) => ({ slot, cvd }));

    it("an all-positive view runs from zero to its maximum", () => {
        expect(cvdDomain(s(10, 40, 25))).toEqual({ lo: 0, hi: 40 });
    });

    it("an all-negative view runs from its minimum to zero", () => {
        expect(cvdDomain(s(-5, -60, -20))).toEqual({ lo: -60, hi: 0 });
    });

    it("a mixed view spans both extremes without centring zero", () => {
        expect(cvdDomain(s(30, -10, 5))).toEqual({ lo: -10, hi: 30 });
    });

    it("an empty or all-zero view takes [0, 1]", () => {
        expect(cvdDomain([])).toEqual({ lo: 0, hi: 1 });
        expect(cvdDomain(s(0, 0))).toEqual({ lo: 0, hi: 1 });
    });

    it("puts hi at the top edge, lo at the bottom and zero between", () => {
        const d = { lo: -10, hi: 30 };
        expect(cvdY(30, d, 100, 40)).toBe(100);
        expect(cvdY(-10, d, 100, 40)).toBe(140);
        expect(cvdY(0, d, 100, 40)).toBe(130);
        // One-way session: zero sits on the bottom edge and the line uses the full height.
        expect(cvdY(0, { lo: 0, hi: 40 }, 100, 40)).toBe(140);
    });

    it("formats axis values with a sign", () => {
        expect(formatDelta(1200, formatQty)).toBe("+1,200");
        expect(formatDelta(-350, formatQty)).toBe("-350");
        expect(formatDelta(0, formatQty)).toBe("0");
    });
});

describe("CVD pane layout (P19-4)", () => {
    it("is registered as a page-only pane, bar charts only, off by default", () => {
        const d = INDICATORS.find((x) => x.id === "cvd")!;
        expect(d).toMatchObject({ placement: "pane", surfaces: ["page"], chartTypes: ["candles", "footprint"] });
        expect(d.defaultOn).toBeUndefined();
    });

    it("lays out below volume, each pane taking 20%", () => {
        const l = layoutPanes("page", ["volume", "cvd"], 300);
        expect(l.panes.map((p) => [p.id, p.height])).toEqual([
            ["volume", 60],
            ["cvd", 60],
        ]);
        expect(l.price.height).toBe(180);
    });

    it("counts toward the page's cap of three panes", () => {
        const fixture: readonly IndicatorDescriptor[] = [
            ...INDICATORS,
            { id: "p3", label: "P3", placement: "pane", surfaces: ["page"], chartTypes: ["candles"] },
            { id: "p4", label: "P4", placement: "pane", surfaces: ["page"], chartTypes: ["candles"] },
        ];
        const l = layoutPanes("page", ["volume", "cvd", "p3", "p4"], 300, fixture);
        expect(l.panes.map((p) => p.id)).toEqual(["volume", "cvd", "p3"]);
    });
});
