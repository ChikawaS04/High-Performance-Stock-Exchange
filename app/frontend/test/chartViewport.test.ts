import { describe, expect, it } from "vitest";

import {
    FOLLOW_AUTO,
    LINE_FULL,
    LINE_MIN_SPAN_NS,
    PAN_THRESHOLD_PX,
    ZOOM_MAX_SLOT,
    ZOOM_MIN_SLOT,
    ZOOM_STEP,
    clampRightBucket,
    isDrag,
    panCandles,
    panLine,
    resolveLineWindow,
    slotsThatFit,
    wheelNotches,
    zoomCandles,
    zoomLine,
} from "../src/chartViewport";
import { buildCandleView } from "../src/candleView";
import { foldFill, emptySeries } from "../src/bars";
import type { BarSeries } from "../src/bars";

// Pure zoom and pan maths (Charts follow-up, decisions 7 to 9).

const EXTENT = { oldest: 1000, newest: 1499 }; // 500 buckets
const PLOT_W = 600;

describe("wheel notches", () => {
    it("reads a mouse notch as one in every delta mode, and a trackpad as a fraction", () => {
        expect(wheelNotches(100, 0)).toBe(1);
        expect(wheelNotches(-100, 0)).toBe(-1);
        expect(wheelNotches(3, 1)).toBe(1);
        expect(wheelNotches(1, 2)).toBe(1);
        expect(wheelNotches(-12, 0)).toBeCloseTo(-0.12, 9);
        expect(wheelNotches(Number.NaN, 0)).toBe(0);
    });
});

describe("candle zoom", () => {
    it("changes the slot width by the factor per notch: wheel up wider, wheel down narrower", () => {
        const zin = zoomCandles(FOLLOW_AUTO, 10, -1, PLOT_W, EXTENT);
        expect(zin.slotW).toBeCloseTo(10 * ZOOM_STEP, 9);
        const zout = zoomCandles(FOLLOW_AUTO, 10, 1, PLOT_W, EXTENT);
        expect(zout.slotW).toBeCloseTo(10 / ZOOM_STEP, 9);
        expect(ZOOM_STEP).toBeCloseTo(1.15, 9);
    });

    it("clamps the slot width to 3px and 40px", () => {
        expect(ZOOM_MIN_SLOT).toBe(3);
        expect(ZOOM_MAX_SLOT).toBe(40);
        expect(zoomCandles(FOLLOW_AUTO, 39, -5, PLOT_W, EXTENT).slotW).toBe(40);
        expect(zoomCandles(FOLLOW_AUTO, 3.2, 5, PLOT_W, EXTENT).slotW).toBe(3);
    });

    it("keeps the newest bar pinned while following", () => {
        const z = zoomCandles(FOLLOW_AUTO, 10, -2, PLOT_W, EXTENT);
        expect(z.rightBucket).toBeNull();
    });

    it("keeps the right edge put while panned back", () => {
        const panned = { slotW: 10, rightBucket: 1300 };
        expect(zoomCandles(panned, 10, -1, PLOT_W, EXTENT).rightBucket).toBe(1300);
        expect(zoomCandles(panned, 10, 1, PLOT_W, EXTENT).rightBucket).toBe(1300);
    });

    it("zooming out near the oldest bar pushes the right edge forward rather than show past it", () => {
        const panned = { slotW: 10, rightBucket: 1060 }; // 60 slots: buckets 1001..1060
        const z = zoomCandles(panned, 10, 5, PLOT_W, EXTENT); // slotW ~5, 120 slots
        const n = slotsThatFit(PLOT_W, z.slotW!);
        expect(z.rightBucket).toBe(EXTENT.oldest + n - 1);
    });
});

describe("candle pan", () => {
    const start = { window: { slotW: 10, rightBucket: null }, slotW: 10, rightBucket: 1499 };

    it("converts a drag to whole slots: right goes back in time, left forward", () => {
        expect(panCandles(start, 35, PLOT_W, EXTENT).rightBucket).toBe(1496); // 3.5 slots -> 3
        const back = { window: { slotW: 10, rightBucket: 1400 }, slotW: 10, rightBucket: 1400 };
        expect(panCandles(back, -20, PLOT_W, EXTENT).rightBucket).toBe(1402);
    });

    it("under one slot keeps the window the drag started from", () => {
        const auto = { window: FOLLOW_AUTO, slotW: 10, rightBucket: 1499 };
        expect(panCandles(auto, 9, PLOT_W, EXTENT)).toBe(FOLLOW_AUTO);
    });

    it("clamps at both ends", () => {
        // Cannot go newer than the latest bar: following.
        expect(panCandles(start, -500, PLOT_W, EXTENT).rightBucket).toBeNull();
        // Cannot go older than the oldest closed bar: the left edge stops at it.
        const n = slotsThatFit(PLOT_W, 10);
        expect(panCandles(start, 100_000, PLOT_W, EXTENT).rightBucket).toBe(EXTENT.oldest + n - 1);
    });

    it("turns following off once the newest bar leaves the view and back on at the edge", () => {
        const back = panCandles(start, 10, PLOT_W, EXTENT);
        expect(back.rightBucket).toBe(1498);
        const from = { window: back, slotW: 10, rightBucket: 1498 };
        expect(panCandles(from, -10, PLOT_W, EXTENT).rightBucket).toBeNull();
    });

    it("never pans when all the bars fit", () => {
        expect(clampRightBucket(5, { oldest: 0, newest: 10 }, 60)).toBeNull();
    });
});

describe("click versus drag", () => {
    it("is a click under 3px on both axes and a drag at 3px on either", () => {
        expect(PAN_THRESHOLD_PX).toBe(3);
        expect(isDrag(2, 2)).toBe(false);
        expect(isDrag(-2.9, 0)).toBe(false);
        expect(isDrag(3, 0)).toBe(true);
        expect(isDrag(0, -3)).toBe(true);
    });
});

describe("buildCandleView with a window", () => {
    const S = 1_000_000_000;
    const NS = 15 * S;
    function series(n: number): BarSeries {
        let s = emptySeries();
        const B0 = Math.floor(1.75e18 / NS);
        for (let i = 0; i < n; i++) s = foldFill(s, NS, (B0 + i) * NS + S, 1_000_000 + i * 100, 1, 1_000_000);
        return s;
    }

    it("spans exactly the slots that fit at a fixed slot width, ending at the right edge", () => {
        const s = series(200);
        const auto = buildCandleView(s, PLOT_W)!;
        const right = auto.newestBucket - 50;
        const v = buildCandleView(s, PLOT_W, 6, { slotW: 20, rightBucket: right })!;
        expect(v.slotW).toBe(20);
        expect(v.slotCount).toBe(30);
        expect(v.lastBucket).toBe(right);
        expect(v.following).toBe(false);
        expect(v.bars[v.bars.length - 1].bar.bucket).toBe(right);
        expect(v.offset).toBe(0);
    });

    it("refits the y axis to the bars in view", () => {
        const s = series(200);
        const newest = buildCandleView(s, PLOT_W, 6, { slotW: 20, rightBucket: null })!;
        const old = buildCandleView(s, PLOT_W, 6, { slotW: 20, rightBucket: newest.newestBucket - 150 })!;
        expect(old.dMax).toBeLessThan(newest.dMin);
    });

    it("right-aligns a short history at a fixed slot width", () => {
        const v = buildCandleView(series(5), PLOT_W, 6, { slotW: 20, rightBucket: null })!;
        expect(v.slotCount).toBe(5);
        expect(v.offset).toBe(PLOT_W - 100);
        expect(v.following).toBe(true);
    });
});

describe("line window", () => {
    const T0 = 1.75e18;
    const T1 = T0 + 200e9; // a 200s tape

    it("is the whole tape by default, following", () => {
        expect(resolveLineWindow(LINE_FULL, T0, T1)).toEqual({ lo: T0, hi: T1, span: 200e9, following: true });
    });

    it("zooms the span by the factor per notch, anchored on the newest print while following", () => {
        const z = zoomLine(LINE_FULL, -1, T0, T1);
        expect(z.rightT).toBeNull();
        expect(z.spanNs!).toBeCloseTo(200e9 / ZOOM_STEP, -3);
    });

    it("never zooms out past the whole tape, nor in past the minimum span", () => {
        expect(zoomLine(LINE_FULL, 5, T0, T1)).toEqual(LINE_FULL);
        const deep = zoomLine(LINE_FULL, -100, T0, T1);
        expect(deep.spanNs).toBe(LINE_MIN_SPAN_NS);
    });

    it("pans by the dragged fraction of the span and clamps at the tape's ends", () => {
        const zoomed = resolveLineWindow({ spanNs: 50e9, rightT: null }, T0, T1);
        // Dragging right a quarter of a 600px plot moves back a quarter of the span.
        const p = panLine(zoomed, 150, PLOT_W, T0, T1);
        expect(p.rightT).toBeCloseTo(T1 - 12.5e9, -4);
        expect(resolveLineWindow(p, T0, T1).following).toBe(false);
        expect(panLine(zoomed, -150, PLOT_W, T0, T1).rightT).toBeNull();
        expect(resolveLineWindow(panLine(zoomed, 100_000, PLOT_W, T0, T1), T0, T1).lo).toBe(T0);
    });

    it("keeps the right edge put when zooming a panned window", () => {
        const panned = { spanNs: 50e9, rightT: T0 + 100e9 };
        expect(zoomLine(panned, -1, T0, T1).rightT).toBe(T0 + 100e9);
    });
});
