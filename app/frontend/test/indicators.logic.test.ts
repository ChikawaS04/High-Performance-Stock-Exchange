import { describe, expect, it } from "vitest";

import { availableIndicators, drawableIds, INDICATORS, onSurface, PANE_CAP } from "../src/indicators/registry";
import type { IndicatorDescriptor } from "../src/indicators/registry";
import { layoutPanes } from "../src/indicators/layoutPanes";

// Three panes and an overlay, so the caps can actually be reached (P17-0 F13).
const FIXTURE: IndicatorDescriptor[] = [
    { id: "p1", label: "P1", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
    { id: "p2", label: "P2", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
    { id: "p3", label: "P3", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
    { id: "p4", label: "P4", placement: "pane", surfaces: ["page"], chartTypes: ["candles"] },
    { id: "ov", label: "OV", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["line", "candles"] },
];

describe("indicator registry (P17-5)", () => {
    it("registers volume (pane, candles only) and VWAP (overlay, both types) on both surfaces", () => {
        expect(INDICATORS.map((d) => d.id)).toEqual(["volume", "vwap", "executions", "orders"]);
        expect(onSurface("page").map((d) => d.id)).toEqual(["volume", "vwap", "executions", "orders"]);
        expect(onSurface("window").map((d) => d.id)).toEqual(["volume", "vwap", "executions", "orders"]);
    });

    it("volume is not available in line mode; VWAP is", () => {
        expect(availableIndicators("page", "line").map((d) => d.id)).toEqual(["vwap", "executions", "orders"]);
        expect(availableIndicators("window", "candles").map((d) => d.id)).toEqual(["volume", "vwap", "executions", "orders"]);
    });

    it("drawableIds keeps enabled ids the chart type can draw", () => {
        expect(drawableIds("page", "line", ["volume", "vwap"])).toEqual(["vwap"]);
        expect(drawableIds("page", "candles", ["vwap"])).toEqual(["vwap"]);
    });

    it("caps panes at 1 on the window and 3 on the page", () => {
        expect(PANE_CAP).toEqual({ window: 1, page: 3 });
    });
});

describe("layoutPanes (P17-5)", () => {
    function check(surface: "page" | "window", ids: string[], h: number): ReturnType<typeof layoutPanes> {
        const l = layoutPanes(surface, ids, h, FIXTURE);
        // Tiles exactly: price on top, then panes, no gap, no overlap, sums to h.
        let y = 0;
        expect(l.price.top).toBe(0);
        y += l.price.height;
        for (const p of l.panes) {
            expect(p.top).toBe(y);
            y += p.height;
        }
        expect(y).toBe(h);
        expect(l.price.height).toBeGreaterThanOrEqual(0.6 * h);
        return l;
    }

    it("gives the full height to price with no panes (overlays take no pane)", () => {
        const l = check("page", ["ov"], 400);
        expect(l.panes).toHaveLength(0);
        expect(l.price.height).toBe(400);
    });

    it("lays out one, two and three panes with equal heights and price at least 60%", () => {
        expect(check("page", ["p1"], 400).panes.map((p) => p.height)).toEqual([80]);
        expect(check("page", ["p1", "p2"], 400).panes.map((p) => p.height)).toEqual([80, 80]);
        const three = check("page", ["p1", "p2", "p3"], 400);
        expect(three.panes.map((p) => p.height)).toEqual([53, 53, 53]);
        expect(three.price.height).toBe(241);
    });

    it("does not lay out enabled panes beyond the cap", () => {
        expect(check("window", ["p1", "p2"], 300).panes.map((p) => p.id)).toEqual(["p1"]);
        expect(check("page", ["p1", "p2", "p3", "p4"], 300).panes.map((p) => p.id)).toEqual(["p1", "p2", "p3"]);
    });

    it("orders panes by registry order and handles odd heights", () => {
        expect(check("page", ["p2", "p1"], 333).panes.map((p) => p.id)).toEqual(["p1", "p2"]);
        check("page", ["p1", "p2", "p3"], 101);
    });
});
