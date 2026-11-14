import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render } from "@testing-library/react";

import { installFakeCanvas } from "./fakeCanvas";
import type { FakeContext } from "./fakeCanvas";
import { HeatmapRenderer, smoothingFor } from "../src/heatmap/renderer";
import { HEATMAP_COLS, HEATMAP_TICK_NS, createDepthRing, ingestBook } from "../src/heatmap/depthRing";
import type { DepthRing } from "../src/heatmap/depthRing";
import { gridFor } from "../src/heatmap/layout";
import type { HeatView } from "../src/heatmap/layout";
import { HeatmapLayer } from "../src/components/HeatmapLayer";
import type { BookFrame } from "../src/protocol/messages";

const T = 100;
const PX = 1_500_000;
const B0 = 7_000_000_000;
const frameAt = (b: number, qty = 100, ms = 1): BookFrame => ({
    type: "BOOK",
    bestBid: PX,
    bestAsk: PX + T,
    bids: [[PX, qty]],
    asks: [[PX + T, 50]],
    timestamp: b * HEATMAP_TICK_NS + ms * 1_000_000,
});
const grid = gridFor({ lo: PX - 20 * T, hi: PX + 20 * T });
const viewOf = (b0: number, n: number, plotW = 400): HeatView => ({
    lo: b0 * HEATMAP_TICK_NS,
    hi: (b0 + n) * HEATMAP_TICK_NS,
    plotLeft: 10,
    plotTop: 24,
    plotW,
    plotH: 200,
});

let fake: ReturnType<typeof installFakeCanvas>;

function setup(): { ring: DepthRing; renderer: HeatmapRenderer; main: FakeContext; data: FakeContext; canvas: HTMLCanvasElement } {
    const canvas = document.createElement("canvas");
    const renderer = new HeatmapRenderer(canvas);
    const [main, data] = fake.all;
    return { ring: createDepthRing(), renderer, main, data, canvas };
}

beforeEach(() => {
    fake = installFakeCanvas();
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("HeatmapRenderer", () => {
    it("paints a small frame: data image put once, then ground and one blit", () => {
        const { ring, renderer, main, data } = setup();
        for (let i = 0; i < 3; i++) ingestBook(ring, frameAt(B0 + i));
        renderer.resize(400, 200, 1);
        const stats = renderer.render(ring, viewOf(B0, 4), grid, "normal");
        expect(stats).toEqual({ mode: "full", columns: 3 });
        expect(data.names()).toEqual(["createImageData", "putImageData"]);
        expect(data.calls[0]).toEqual(["createImageData", HEATMAP_COLS, grid.rows]);
        expect(main.names()).toEqual(["setTransform", "set fillStyle", "fillRect", "set imageSmoothingEnabled", "drawImage"]);
        const sx = B0 % HEATMAP_COLS;
        // Three held columns over a four-bucket window 400px wide: 300px, from the plot's left edge.
        const [name, ...args] = main.calls[4] as [string, ...number[]];
        expect(name).toBe("drawImage");
        expect(args.slice(0, 4)).toEqual([sx, 0, 3, grid.rows]);
        [0, 0, 300, 200].forEach((v, i) => expect(args[4 + i]).toBeCloseTo(v, 3));
    });

    it("sizes the canvas to the device pixel ratio", () => {
        const { ring, renderer, main, canvas } = setup();
        ingestBook(ring, frameAt(B0));
        renderer.resize(300, 150, 2);
        renderer.render(ring, viewOf(B0, 4, 300), grid, "normal");
        expect(canvas.width).toBe(600);
        expect(canvas.height).toBe(300);
        expect(canvas.style.width).toBe("300px");
        expect(main.calls[0]).toEqual(["setTransform", 2, 0, 0, 2, 0, 0]);
    });

    it("paints only the new column on an append", () => {
        const { ring, renderer, data } = setup();
        for (let i = 0; i < 3; i++) ingestBook(ring, frameAt(B0 + i));
        renderer.resize(400, 200, 1);
        renderer.render(ring, viewOf(B0, 8), grid, "normal");
        data.clear();
        ingestBook(ring, frameAt(B0 + 3));
        expect(renderer.render(ring, viewOf(B0, 8), grid, "normal")).toEqual({ mode: "append", columns: 1 });
        expect(data.calls).toEqual([["putImageData", 0, 0, (B0 + 3) % HEATMAP_COLS, 0, 1, grid.rows]]);
    });

    it("repaints the head when a frame rewrites it, and nothing when nothing changed", () => {
        const { ring, renderer } = setup();
        ingestBook(ring, frameAt(B0));
        renderer.resize(400, 200, 1);
        renderer.render(ring, viewOf(B0, 8), grid, "normal");
        expect(renderer.render(ring, viewOf(B0, 8), grid, "normal")).toEqual({ mode: "none", columns: 0 });
        ingestBook(ring, frameAt(B0, 100, 200)); // same bucket, same sizes
        expect(renderer.render(ring, viewOf(B0, 8), grid, "normal")).toEqual({ mode: "append", columns: 1 });
    });

    it("pans without repainting", () => {
        const { ring, renderer } = setup();
        for (let i = 0; i < 10; i++) ingestBook(ring, frameAt(B0 + i));
        renderer.resize(400, 200, 1);
        renderer.render(ring, viewOf(B0, 10), grid, "normal");
        expect(renderer.render(ring, viewOf(B0 + 2, 6), grid, "normal").mode).toBe("none");
    });

    it("repaints in full on a resize, a device pixel ratio change, a new grid or contrast", () => {
        const { ring, renderer } = setup();
        for (let i = 0; i < 3; i++) ingestBook(ring, frameAt(B0 + i));
        const v = viewOf(B0, 8);
        renderer.resize(400, 200, 1);
        renderer.render(ring, v, grid, "normal");
        renderer.resize(500, 200, 1);
        expect(renderer.render(ring, v, grid, "normal").mode).toBe("full");
        renderer.resize(500, 200, 2);
        expect(renderer.render(ring, v, grid, "normal").mode).toBe("full");
        expect(renderer.render(ring, v, gridFor({ lo: PX - 30 * T, hi: PX + 30 * T }), "normal").mode).toBe("full");
        expect(renderer.render(ring, v, gridFor({ lo: PX - 30 * T, hi: PX + 30 * T }), "high").mode).toBe("full");
    });

    it("smooths only when the blit scales down (amendment 5)", () => {
        expect(smoothingFor(100, 200, 1)).toBe(false); // scales up
        expect(smoothingFor(100, 100, 1)).toBe(false); // 1:1
        expect(smoothingFor(100, 50, 2)).toBe(false); // 50 CSS px at dpr 2 is 100 device px
        expect(smoothingFor(100, 99, 1)).toBe(true); // scales down
        const { ring, renderer, main } = setup();
        for (let i = 0; i < 100; i++) ingestBook(ring, frameAt(B0 + i));
        renderer.resize(400, 200, 1);
        renderer.render(ring, viewOf(B0, 100, 400), grid, "normal");
        expect(main.calls.find((c) => c[0] === "set imageSmoothingEnabled")?.[1]).toBe(false);
        main.clear();
        renderer.resize(50, 200, 1);
        renderer.render(ring, viewOf(B0, 100, 50), grid, "normal");
        expect(main.calls.find((c) => c[0] === "set imageSmoothingEnabled")?.[1]).toBe(true);
    });
});

describe("HeatmapLayer", () => {
    function rafQueue(): { flush: () => void; pending: () => number } {
        let queue: FrameRequestCallback[] = [];
        vi.spyOn(window, "requestAnimationFrame").mockImplementation((cb) => {
            queue.push(cb);
            return queue.length;
        });
        vi.spyOn(window, "cancelAnimationFrame").mockImplementation(() => {});
        return {
            flush: () => {
                const q = queue;
                queue = [];
                q.forEach((cb) => cb(0));
            },
            pending: () => queue.length,
        };
    }

    function setHidden(hidden: boolean): void {
        Object.defineProperty(document, "hidden", { configurable: true, get: () => hidden });
        document.dispatchEvent(new Event("visibilitychange"));
    }

    afterEach(() => {
        Object.defineProperty(document, "hidden", { configurable: true, get: () => false });
    });

    it("paints on the next frame, once, and not while the tab is hidden", () => {
        const raf = rafQueue();
        const ring = createDepthRing();
        ingestBook(ring, frameAt(B0));
        const paints: string[] = [];
        const onPaint = (s: { mode: string }) => paints.push(s.mode);
        const v = viewOf(B0, 8);
        const { rerender, getByTestId } = render(
            <HeatmapLayer ring={ring} version={1} view={v} grid={grid} contrast="normal" onPaint={onPaint} />,
        );
        expect(getByTestId("heatmap-canvas").tagName).toBe("CANVAS");
        act(() => raf.flush());
        expect(paints).toEqual(["full"]);

        setHidden(true);
        ingestBook(ring, frameAt(B0 + 1));
        rerender(<HeatmapLayer ring={ring} version={2} view={v} grid={grid} contrast="normal" onPaint={onPaint} />);
        expect(raf.pending()).toBe(0);
        act(() => setHidden(false));
        act(() => raf.flush());
        expect(paints).toEqual(["full", "append"]);
    });

    it("does not repaint for a re-render that changes nothing it draws", () => {
        const raf = rafQueue();
        const ring = createDepthRing();
        ingestBook(ring, frameAt(B0));
        const v = viewOf(B0, 8);
        let n = 0;
        const onPaint = () => n++;
        const { rerender } = render(<HeatmapLayer ring={ring} version={1} view={v} grid={grid} contrast="normal" onPaint={onPaint} />);
        act(() => raf.flush());
        rerender(<HeatmapLayer ring={ring} version={1} view={{ ...v }} grid={grid} contrast="normal" onPaint={onPaint} />);
        expect(raf.pending()).toBe(0);
        expect(n).toBe(1);
    });

    it("mounts without painting where there is no 2D context", () => {
        vi.restoreAllMocks();
        const raf = rafQueue();
        const ring = createDepthRing();
        ingestBook(ring, frameAt(B0));
        const onPaint = vi.fn();
        render(<HeatmapLayer ring={ring} version={1} view={viewOf(B0, 8)} grid={grid} contrast="normal" onPaint={onPaint} />);
        act(() => raf.flush());
        expect(onPaint).not.toHaveBeenCalled();
    });
});
