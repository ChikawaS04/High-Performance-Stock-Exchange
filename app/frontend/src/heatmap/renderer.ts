/**
 * Heatmap canvas renderer (P20-3, P20 D3 as amended at P20-0 F7 and its greenlight).
 *
 * Two canvases. The data image is an offscreen canvas HEATMAP_COLS pixels wide and one
 * row per grid row tall, addressed as a ring: column `bucket` lives at x = bucket mod
 * HEATMAP_COLS. The visible canvas shows it by blitting the visible columns onto the
 * plot (layout.blitPieces), at most two drawImage calls where the ring wraps.
 *
 *  - An append paints only the new columns into the data image, plus the previous
 *    head when the ring rewrote it (a frame in its bucket, or a late frame), and puts
 *    only those pixels.
 *  - Panning or zooming in time changes only the blit's source columns: no repaint.
 *  - A full repaint happens only when the grid (price domain or binning), the clip,
 *    the contrast, the canvas size or the device pixel ratio changes, or the ring
 *    moved on by a whole ring since the last paint.
 *
 * Smoothing by scale (amendment 5): imageSmoothingEnabled is off when the blit scales
 * up or is 1:1 (fewer than one column per device pixel), so cells keep hard edges,
 * and on when it scales down, so zooming out averages columns instead of dropping
 * them.
 *
 * The renderer only replays what layout.ts computes; it holds no market logic. It
 * uses document.createElement("canvas") and ctx.createImageData rather than
 * OffscreenCanvas or the ImageData constructor, which jsdom lacks (P20-0 F5).
 */

import { HEATMAP_COLS, oldestBucket } from "./depthRing";
import type { DepthRing } from "./depthRing";
import { buildHeatmapFrame, createRowScratch, paintColumn, sameGrid } from "./layout";
import type { HeatContrast, HeatView, HeatmapFrame, RowGrid, RowScratch } from "./layout";
import { HEAT_GROUND, buildRampLut } from "./ramp";

export type PaintMode = "full" | "append" | "none";

export interface PaintStats {
    readonly mode: PaintMode;
    /** Data-image columns painted this render. */
    readonly columns: number;
}

/** Draws on top of the cells (trades and quote lines, P20-4), in plot CSS pixels. */
export type OverlayDraw = (ctx: CanvasRenderingContext2D) => void;

/** Smoothing for a blit of `sw` source columns onto `dw` CSS px at `dpr`. */
export function smoothingFor(sw: number, dw: number, dpr: number): boolean {
    return dw * dpr < sw;
}

export class HeatmapRenderer {
    private readonly ctx: CanvasRenderingContext2D;
    private readonly data: HTMLCanvasElement;
    private readonly dataCtx: CanvasRenderingContext2D;
    private readonly lut = buildRampLut();
    private readonly scratch: RowScratch = createRowScratch();
    private image: ImageData | null = null;
    private pixels: Uint32Array | null = null;
    private cssW = 0;
    private cssH = 0;
    private dpr = 1;
    private stale = true;
    // What the data image holds.
    private grid: RowGrid | null = null;
    private clip = -1;
    private contrast: HeatContrast | null = null;
    private paintedHead = -1;
    private paintedWrites = 0;

    constructor(
        private readonly canvas: HTMLCanvasElement,
        createCanvas: () => HTMLCanvasElement = () => document.createElement("canvas"),
    ) {
        const ctx = canvas.getContext("2d");
        this.data = createCanvas();
        const dataCtx = this.data.getContext("2d");
        if (ctx === null || dataCtx === null) throw new Error("2D canvas unavailable");
        this.ctx = ctx;
        this.dataCtx = dataCtx;
        this.data.width = HEATMAP_COLS;
    }

    /** Size the visible canvas to the plot at the device pixel ratio. A change forces a full repaint. */
    resize(cssW: number, cssH: number, dpr: number): void {
        if (cssW === this.cssW && cssH === this.cssH && dpr === this.dpr && !this.stale) return;
        this.cssW = cssW;
        this.cssH = cssH;
        this.dpr = dpr;
        this.canvas.width = Math.max(1, Math.round(cssW * dpr));
        this.canvas.height = Math.max(1, Math.round(cssH * dpr));
        this.canvas.style.width = `${cssW}px`;
        this.canvas.style.height = `${cssH}px`;
        this.stale = true;
    }

    /** Paint the data image as needed, then present the visible window. */
    render(ring: DepthRing, view: HeatView, grid: RowGrid, contrast: HeatContrast, overlay?: OverlayDraw): PaintStats {
        const frame = buildHeatmapFrame(ring, view, grid, contrast, this.scratch);
        const stats = this.paint(ring, frame, contrast);
        this.present(frame, view, overlay);
        return stats;
    }

    private paint(ring: DepthRing, frame: HeatmapFrame, contrast: HeatContrast): PaintStats {
        const full =
            this.stale ||
            !sameGrid(this.grid, frame.grid) ||
            frame.clip !== this.clip ||
            contrast !== this.contrast ||
            this.paintedHead < 0 ||
            ring.head - this.paintedHead >= ring.cols ||
            ring.head < this.paintedHead;
        if (full) return this.paintAll(ring, frame, contrast);
        const fresh = ring.head - this.paintedHead;
        const rewroteHead = ring.writes - this.paintedWrites > fresh;
        const from = rewroteHead ? this.paintedHead : this.paintedHead + 1;
        if (from > ring.head) {
            this.paintedWrites = ring.writes;
            return { mode: "none", columns: 0 };
        }
        this.paintRange(ring, frame, from, ring.head);
        this.paintedHead = ring.head;
        this.paintedWrites = ring.writes;
        return { mode: "append", columns: ring.head - from + 1 };
    }

    private paintAll(ring: DepthRing, frame: HeatmapFrame, contrast: HeatContrast): PaintStats {
        const rows = frame.grid.rows;
        if (this.image === null || this.image.height !== rows) {
            this.data.height = rows;
            this.image = this.dataCtx.createImageData(HEATMAP_COLS, rows);
            this.pixels = new Uint32Array(this.image.data.buffer);
        }
        this.grid = frame.grid;
        this.clip = frame.clip;
        this.contrast = contrast;
        this.stale = false;
        let columns = 0;
        if (ring.count > 0) {
            const from = oldestBucket(ring);
            for (let b = from; b <= ring.head; b++) {
                paintColumn(ring, b, frame.grid, frame.clip, this.lut, this.pixels!, HEATMAP_COLS, this.xOf(b), this.scratch);
                columns++;
            }
        }
        this.dataCtx.putImageData(this.image, 0, 0);
        this.paintedHead = ring.head;
        this.paintedWrites = ring.writes;
        return { mode: "full", columns };
    }

    private paintRange(ring: DepthRing, frame: HeatmapFrame, from: number, to: number): void {
        const pixels = this.pixels!;
        let runStart = this.xOf(from);
        let runLen = 0;
        for (let b = from; b <= to; b++) {
            const x = this.xOf(b);
            paintColumn(ring, b, frame.grid, frame.clip, this.lut, pixels, HEATMAP_COLS, x, this.scratch);
            if (runLen > 0 && x !== runStart + runLen) {
                this.dataCtx.putImageData(this.image!, 0, 0, runStart, 0, runLen, frame.grid.rows);
                runStart = x;
                runLen = 0;
            }
            runLen++;
        }
        if (runLen > 0) this.dataCtx.putImageData(this.image!, 0, 0, runStart, 0, runLen, frame.grid.rows);
    }

    private xOf(bucket: number): number {
        return ((bucket % HEATMAP_COLS) + HEATMAP_COLS) % HEATMAP_COLS;
    }

    private present(frame: HeatmapFrame, view: HeatView, overlay?: OverlayDraw): void {
        const ctx = this.ctx;
        ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
        ctx.fillStyle = HEAT_GROUND;
        ctx.fillRect(0, 0, this.cssW, this.cssH);
        for (const p of frame.pieces) {
            ctx.imageSmoothingEnabled = smoothingFor(p.sw, p.dw, this.dpr);
            ctx.drawImage(this.data, p.sx, 0, p.sw, frame.grid.rows, p.dx - view.plotLeft, 0, p.dw, view.plotH);
        }
        if (overlay !== undefined) overlay(ctx);
    }
}
