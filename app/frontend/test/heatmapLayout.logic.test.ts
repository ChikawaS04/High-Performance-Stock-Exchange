import { describe, expect, it } from "vitest";

import {
    BUBBLE_MAX_R,
    BUBBLE_MIN_R,
    HEATMAP_DOMAIN_SNAP_TICKS,
    HEATMAP_MAX_ROWS,
    heatmapMaxRows,
    HEATMAP_MIN_INTENSITY,
    HEATMAP_MIN_SPAN_TICKS,
    blitPieces,
    bubbleRadius,
    buildHeatmapFrame,
    cellRect,
    clipFor,
    columnRows,
    clearRows,
    createRowScratch,
    gridFor,
    intensity,
    nextDomain,
    paintColumn,
    percentileClip,
    rowOf,
    rowReadout,
} from "../src/heatmap/layout";
import type { HeatView } from "../src/heatmap/layout";
import { HEATMAP_COLS, HEATMAP_TICK_NS, createDepthRing, ingestBook, slotFor } from "../src/heatmap/depthRing";
import { GROUND_PIXEL, HEAT_STOPS, RAMP_SIZE, buildRampLut, hexRgb, packRgb, rampIndex, rampRgb } from "../src/heatmap/ramp";
import type { BookFrame, Level } from "../src/protocol/messages";

const T = 100;
const PX = 1_500_000;
const B0 = 7_000_000_000;
const tAt = (b: number): number => b * HEATMAP_TICK_NS + 1_000_000;

function book(t: number, bids: readonly Level[], asks: readonly Level[]): BookFrame {
    return { type: "BOOK", bestBid: bids[0]?.[0] ?? -1, bestAsk: asks[0]?.[0] ?? -1, bids, asks, timestamp: t };
}

describe("intensity (D2, amendment 3)", () => {
    it("is 0 for an empty cell, the floor for one share, 1 at and above the clip", () => {
        expect(intensity(0, 500)).toBe(0);
        expect(intensity(1, 500)).toBe(HEATMAP_MIN_INTENSITY);
        expect(intensity(500, 500)).toBe(1);
        expect(intensity(50_000, 500)).toBe(1);
        const mid = intensity(30, 500);
        expect(mid).toBeCloseTo(Math.log1p(30) / Math.log1p(500), 10);
        expect(mid).toBeGreaterThan(HEATMAP_MIN_INTENSITY);
    });

    it("draws nothing for size 0 and the floor colour for size 1", () => {
        const ring = createDepthRing();
        ingestBook(ring, book(tAt(B0), [[PX, 1]], []));
        const grid = gridFor({ lo: PX - 5 * T, hi: PX + 5 * T });
        const lut = buildRampLut();
        const pixels = new Uint32Array(grid.rows);
        paintColumn(ring, B0, grid, 1000, lut, pixels, 1, 0, createRowScratch());
        const row = rowOf(grid, PX);
        expect(pixels[grid.rows - 1 - row]).toBe(lut[rampIndex(HEATMAP_MIN_INTENSITY)]);
        expect(pixels.filter((p) => p === GROUND_PIXEL).length).toBe(grid.rows - 1);
    });
});

describe("percentile clip and contrast", () => {
    it("ignores one outlier", () => {
        const values = [...Array.from({ length: 99 }, () => 100), 1_000_000];
        const clip = percentileClip(values);
        expect(clip).toBeGreaterThanOrEqual(100);
        expect(clip).toBeLessThan(150);
        expect(intensity(1_000_000, clip)).toBe(1);
        expect(intensity(100, clip)).toBeGreaterThan(0.9);
    });

    it("scales with the contrast setting", () => {
        const ring = createDepthRing();
        for (let i = 0; i < 20; i++) ingestBook(ring, book(tAt(B0 + i), [[PX, 100 + i]], [[PX + T, 300]]));
        const grid = gridFor({ lo: PX - 10 * T, hi: PX + 10 * T });
        const s = createRowScratch();
        const normal = clipFor(ring, grid, B0, B0 + 19, "normal", s);
        expect(clipFor(ring, grid, B0, B0 + 19, "high", s)).toBeCloseTo(normal / 2, 9);
        expect(clipFor(ring, grid, B0, B0 + 19, "low", s)).toBeCloseTo(normal * 2, 9);
    });

    it("is 1 for an empty window", () => {
        expect(percentileClip([])).toBe(1);
    });
});

describe("grid binning (amendment 4)", () => {
    const span = (ticks: number) => ({ lo: PX, hi: PX + (ticks - 1) * T });

    it("keeps one tick a row up to the cap", () => {
        const g = gridFor(span(HEATMAP_MAX_ROWS));
        expect(g.rowTicks).toBe(1);
        expect(g.rows).toBe(HEATMAP_MAX_ROWS);
    });

    it("bins 513 ticks two to a row", () => {
        const g = gridFor(span(513));
        expect(g.rowTicks).toBe(2);
        expect(g.rows).toBe(257);
        expect(rowOf(g, PX + 512 * T)).toBe(256);
    });

    it("caps the rows at the plot's device-pixel height", () => {
        expect(heatmapMaxRows(250, 1)).toBe(250);
        expect(heatmapMaxRows(300, 2)).toBe(HEATMAP_MAX_ROWS);
        expect(heatmapMaxRows(180.6, 1.5)).toBe(270);
        expect(heatmapMaxRows(0, 1)).toBe(1);
        expect(heatmapMaxRows(250, 0)).toBe(250);
    });

    it("bins the 850-tick visual-check run to at least a device pixel a row", () => {
        const g = gridFor(span(850), heatmapMaxRows(250, 1));
        expect(g.rowTicks).toBe(4);
        expect(g.rows).toBe(213);
        expect(g.rows).toBeLessThanOrEqual(250);
    });

    it("bins 2,256 ticks five to a row", () => {
        const g = gridFor(span(2256));
        expect(g.rowTicks).toBe(5);
        expect(g.rows).toBe(452);
    });

    it("sums the sizes in a binned row and preserves the total", () => {
        const ring = createDepthRing();
        const bids: Level[] = [];
        for (let i = 0; i < 12; i++) bids.push([PX - i * T, 10 + i]);
        ingestBook(ring, book(tAt(B0), bids, [[PX + T, 7]]));
        const g = gridFor({ lo: PX - 1000 * T, hi: PX + 1255 * T }); // 2,256 ticks, 5 a row
        const s = createRowScratch();
        const n = columnRows(ring, slotFor(ring, B0), g, s);
        let total = 0;
        for (let i = 0; i < n; i++) total += s.sums[s.touched[i]];
        clearRows(s);
        const expected = bids.reduce((a, l) => a + l[1], 0) + 7;
        expect(total).toBe(expected);
        expect(n).toBeLessThan(13);
    });

    it("reads a binned row's price range and summed size", () => {
        const ring = createDepthRing();
        ingestBook(ring, book(tAt(B0), [[PX, 10], [PX - T, 20], [PX - 2 * T, 30]], [[PX + T, 5]]));
        const g = gridFor({ lo: PX - 1000 * T, hi: PX + 1255 * T });
        const r = rowReadout(ring, g, B0, PX - T);
        expect(r).not.toBeNull();
        expect(r!.hi - r!.lo).toBe(4 * T);
        expect(r!.lo).toBeLessThanOrEqual(PX - T);
        expect(r!.hi).toBeGreaterThanOrEqual(PX - T);
        // Every level inside the row is summed.
        const inRow = [
            [PX, 10],
            [PX - T, 20],
            [PX - 2 * T, 30],
            [PX + T, 5],
        ].filter(([p]) => p >= r!.lo && p <= r!.hi);
        expect(r!.size).toBe(inRow.reduce((a, [, q]) => a + q, 0));
        const single = rowReadout(ring, gridFor({ lo: PX - 20 * T, hi: PX + 20 * T }), B0, PX - T);
        expect(single).toEqual({ lo: PX - T, hi: PX - T, size: 20 });
    });
});

describe("price domain hysteresis", () => {
    it("pads, keeps the minimum span and snaps outward", () => {
        const d = nextDomain(null, PX, PX + T)!;
        expect(d.hi - d.lo).toBeGreaterThanOrEqual(HEATMAP_MIN_SPAN_TICKS * T);
        expect(d.lo % (HEATMAP_DOMAIN_SNAP_TICKS * T)).toBe(0);
        expect(d.hi % (HEATMAP_DOMAIN_SNAP_TICKS * T)).toBe(0);
        expect(d.lo).toBeLessThan(PX);
        expect(d.hi).toBeGreaterThan(PX + T);
    });

    it("keeps the domain while the bests stay inside", () => {
        const d = nextDomain(null, PX, PX + T)!;
        expect(nextDomain(d, PX + 3 * T, PX + 4 * T)).toBe(d);
    });

    it("refits when a best leaves, or when it could shrink by more than a quarter", () => {
        const d = nextDomain(null, PX, PX + T)!;
        const moved = nextDomain(d, d.hi + T, d.hi + 2 * T)!;
        expect(moved).not.toBe(d);
        expect(moved.hi).toBeGreaterThan(d.hi);
        const wide = nextDomain(null, PX - 300 * T, PX + 300 * T)!;
        const narrowed = nextDomain(wide, PX, PX + T)!;
        expect(narrowed.hi - narrowed.lo).toBeLessThan(0.75 * (wide.hi - wide.lo));
    });

    it("holds the previous domain when there are no bests", () => {
        const d = nextDomain(null, PX, PX + T);
        expect(nextDomain(d, -1, -1)).toBe(d);
        expect(nextDomain(null, -1, -1)).toBeNull();
    });
});

describe("cell geometry", () => {
    const view: HeatView = {
        lo: B0 * HEATMAP_TICK_NS,
        hi: (B0 + 8) * HEATMAP_TICK_NS,
        plotLeft: 10,
        plotTop: 24,
        plotW: 640,
        plotH: 300,
    };

    it("tiles the plot with no gap or overlap", () => {
        const g = gridFor({ lo: PX, hi: PX + 9 * T });
        let width = 0;
        for (let b = B0; b < B0 + 8; b++) {
            const a = cellRect(view, g, b, 0);
            const next = cellRect(view, g, b + 1, 0);
            expect(next.x).toBeCloseTo(a.x + a.w, 9);
            width += a.w;
        }
        expect(width).toBeCloseTo(view.plotW, 9);
        let height = 0;
        for (let r = 0; r < g.rows; r++) {
            const c = cellRect(view, g, B0, r);
            if (r > 0) expect(c.y + c.h).toBeCloseTo(cellRect(view, g, B0, r - 1).y, 9);
            height += c.h;
        }
        expect(height).toBeCloseTo(view.plotH, 9);
        expect(cellRect(view, g, B0, 0).x).toBe(view.plotLeft);
    });

    it("blits the held columns, split where the ring wraps", () => {
        const ring = createDepthRing();
        const start = Math.ceil(B0 / HEATMAP_COLS) * HEATMAP_COLS - 3; // three slots before a wrap
        for (let i = 0; i < 6; i++) ingestBook(ring, book(tAt(start + i), [[PX, 10]], [[PX + T, 10]]));
        const v: HeatView = { ...view, lo: start * HEATMAP_TICK_NS, hi: (start + 6) * HEATMAP_TICK_NS };
        const pieces = blitPieces(ring, v);
        expect(pieces.map((p) => [p.sx, p.sw])).toEqual([
            [HEATMAP_COLS - 3, 3],
            [0, 3],
        ]);
        expect(pieces[0].dx).toBe(v.plotLeft);
        expect(pieces[1].dx).toBeCloseTo(pieces[0].dx + pieces[0].dw, 9);
        expect(pieces[0].dw + pieces[1].dw).toBeCloseTo(v.plotW, 9);
        const frame = buildHeatmapFrame(ring, v, gridFor({ lo: PX - 10 * T, hi: PX + 10 * T }), "normal", createRowScratch());
        expect(frame.pieces).toEqual(pieces);
        expect(frame.clip).toBeGreaterThan(0);
    });
});

describe("trade bubbles", () => {
    it("caps the radius and keeps a minimum", () => {
        expect(bubbleRadius(0)).toBe(BUBBLE_MIN_R);
        expect(bubbleRadius(100)).toBe(5);
        expect(bubbleRadius(1_000_000)).toBe(BUBBLE_MAX_R);
        // Area proportional to quantity below the cap: four times the shares, twice the radius.
        expect(bubbleRadius(400) / bubbleRadius(100)).toBeCloseTo(2, 9);
    });
});

describe("ramp", () => {
    it("starts at a dark blue-grey, ends near white and hits every stop", () => {
        for (const s of HEAT_STOPS) expect(rampRgb(s.at)).toEqual(hexRgb(s.hex));
        const lut = buildRampLut();
        expect(lut.length).toBe(RAMP_SIZE);
        expect(lut[0]).toBe(packRgb(hexRgb("#2e3844")));
        expect(lut[RAMP_SIZE - 1]).toBe(packRgb(hexRgb("#ebebeb")));
        expect(GROUND_PIXEL).toBe(packRgb([0, 0, 0]));
    });

    it("puts the floor intensity at 2:1 or more against the black ground", () => {
        // WCAG relative luminance against #000000.
        const lin = (c: number) => (c / 255 <= 0.04045 ? c / 255 / 12.92 : ((c / 255 + 0.055) / 1.055) ** 2.4);
        const [r, g, b] = rampRgb(HEATMAP_MIN_INTENSITY);
        const lum = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
        expect((lum + 0.05) / 0.05).toBeGreaterThanOrEqual(2);
    });

    it("brightens monotonically", () => {
        const lum = (v: number) => rampRgb(v).reduce((a, c) => a + c, 0);
        for (let i = 1; i <= 20; i++) expect(lum(i / 20)).toBeGreaterThanOrEqual(lum((i - 1) / 20));
    });
});
