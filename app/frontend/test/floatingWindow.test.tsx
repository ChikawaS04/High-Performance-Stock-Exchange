import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";

import { FloatingWindow } from "../src/components/FloatingWindow";
import { DepthChartOverlay } from "../src/components/DepthChartOverlay";
import { PriceChartOverlay } from "../src/components/PriceChartOverlay";
import { PriceChart } from "../src/components/PriceChart";
import { DepthCurve } from "../src/components/DepthCurve";
import { MID_LABEL_PX } from "../src/depthChartLayout";
import { SETTING_KEYS } from "../src/settings/persist";
import { MIN_H, MIN_W } from "../src/floatingWindow";
import type { BookState, TapeEntry } from "../src/state/reducer";

const KEY = "hpse.v1.testWindow.geometry";
const VP = { w: 1600, h: 900 };

beforeEach(() => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: VP.w });
    Object.defineProperty(window, "innerHeight", { configurable: true, value: VP.h });
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
});

function Win() {
    return (
        <FloatingWindow
            className="test-window"
            testId="win"
            ariaLabel="Test window"
            dragSelector=".hdr"
            storageKey={KEY}
            nominal={{ w: 800, h: 500 }}
        >
            <div className="hdr" data-testid="hdr">
                Title
                <button type="button" data-testid="hdr-btn">
                    x
                </button>
            </div>
            <div data-testid="body">body</div>
        </FloatingWindow>
    );
}

function geom(): { x: number; y: number; w: number; h: number } {
    const s = screen.getByTestId("win").style;
    return { x: parseFloat(s.left), y: parseFloat(s.top), w: parseFloat(s.width), h: parseFloat(s.height) };
}

function drag(el: Element, from: [number, number], to: [number, number]): void {
    fireEvent.pointerDown(el, { pointerId: 1, button: 0, clientX: from[0], clientY: from[1] });
    fireEvent.pointerMove(el, { pointerId: 1, clientX: to[0], clientY: to[1] });
    fireEvent.pointerUp(el, { pointerId: 1, clientX: to[0], clientY: to[1] });
}

describe("FloatingWindow", () => {
    it("opens at the nominal size, centred", () => {
        render(<Win />);
        expect(geom()).toEqual({ x: 400, y: 200, w: 800, h: 500 });
    });

    it("moves on a header drag without resizing, and saves the geometry", () => {
        render(<Win />);
        drag(screen.getByTestId("hdr"), [500, 210], [560, 250]);
        expect(geom()).toEqual({ x: 460, y: 240, w: 800, h: 500 });
        expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ x: 460, y: 240, w: 800, h: 500 });
    });

    it("never drags from a control in the header or from the body", () => {
        render(<Win />);
        drag(screen.getByTestId("hdr-btn"), [500, 210], [600, 300]);
        drag(screen.getByTestId("body"), [500, 400], [600, 500]);
        expect(geom()).toEqual({ x: 400, y: 200, w: 800, h: 500 });
    });

    it("resizes from an edge without moving the opposite edge", () => {
        render(<Win />);
        drag(screen.getByTestId("win-resize-e"), [1200, 400], [1300, 400]);
        expect(geom()).toEqual({ x: 400, y: 200, w: 900, h: 500 });

        drag(screen.getByTestId("win-resize-w"), [400, 400], [300, 450]);
        const g = geom();
        expect(g.x).toBe(300);
        expect(g.x + g.w).toBe(1300); // right edge unchanged
        expect(g.h).toBe(500);
    });

    it("resizes from a corner on both axes", () => {
        render(<Win />);
        drag(screen.getByTestId("win-resize-se"), [1200, 700], [1250, 760]);
        expect(geom()).toEqual({ x: 400, y: 200, w: 850, h: 560 });
    });

    it("holds the minimum size", () => {
        render(<Win />);
        drag(screen.getByTestId("win-resize-se"), [1200, 700], [0, 0]);
        expect(geom()).toEqual({ x: 400, y: 200, w: MIN_W, h: MIN_H });
    });

    it("renders all eight handles with their resize cursors, plus the grip mark", () => {
        const { container } = render(<Win />);
        for (const [edge, cursor] of [
            ["n", "ns-resize"],
            ["s", "ns-resize"],
            ["e", "ew-resize"],
            ["w", "ew-resize"],
            ["ne", "nesw-resize"],
            ["sw", "nesw-resize"],
            ["nw", "nwse-resize"],
            ["se", "nwse-resize"],
        ]) {
            expect(screen.getByTestId(`win-resize-${edge}`).style.cursor).toBe(cursor);
        }
        expect(container.querySelector(".floating-window__grip")).not.toBeNull();
    });

    it("suppresses text selection only for the length of a gesture", () => {
        render(<Win />);
        const handle = screen.getByTestId("win-resize-e");
        fireEvent.pointerDown(handle, { pointerId: 1, button: 0, clientX: 1200, clientY: 400 });
        expect(document.body.classList.contains("floating-window--busy")).toBe(true);
        fireEvent.pointerUp(handle, { pointerId: 1, clientX: 1200, clientY: 400 });
        expect(document.body.classList.contains("floating-window--busy")).toBe(false);
    });

    it("restores saved geometry after a remount, clamped to the viewport", () => {
        const first = render(<Win />);
        drag(screen.getByTestId("win-resize-se"), [1200, 700], [1300, 760]);
        const saved = geom();
        first.unmount();

        render(<Win />);
        expect(geom()).toEqual(saved);
        cleanup();

        localStorage.setItem(KEY, JSON.stringify({ x: 1500, y: 850, w: 700, h: 400 }));
        render(<Win />);
        expect(geom()).toEqual({ x: 900, y: 500, w: 700, h: 400 });
    });

    it("falls back to the default for invalid saved geometry", () => {
        localStorage.setItem(KEY, JSON.stringify({ x: 1, y: 2, w: "wide", h: 3 }));
        render(<Win />);
        expect(geom()).toEqual({ x: 400, y: 200, w: 800, h: 500 });
    });

    it("clamps back inside when the browser window shrinks", () => {
        render(<Win />);
        drag(screen.getByTestId("hdr"), [500, 210], [1300, 610]); // to the bottom-right corner
        Object.defineProperty(window, "innerWidth", { configurable: true, value: 900 });
        Object.defineProperty(window, "innerHeight", { configurable: true, value: 600 });
        act(() => {
            window.dispatchEvent(new Event("resize"));
        });
        const g = geom();
        expect(g.x + g.w).toBeLessThanOrEqual(900);
        expect(g.y + g.h).toBeLessThanOrEqual(600);
        expect(g.x).toBeGreaterThanOrEqual(0);
        expect(g.y).toBeGreaterThanOrEqual(0);
    });
});

const BOOK: BookState = {
    bestBid: 14_466_800,
    bestAsk: 14_466_900,
    bids: [[14_466_800, 10]],
    asks: [[14_466_900, 7]],
    timestamp: 1,
};

const TAPE: TapeEntry[] = [
    { tradeId: 2, pricePx: 14_466_900, quantity: 5, aggressorOrderId: 9, passiveOrderId: 8, timestamp: 2e9, mine: false },
    { tradeId: 1, pricePx: 14_466_800, quantity: 5, aggressorOrderId: 9, passiveOrderId: 8, timestamp: 1e9, mine: false },
];

function stubBox(width: number, height: number): void {
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockReturnValue({
        width,
        height,
        top: 0,
        left: 0,
        right: width,
        bottom: height,
        x: 0,
        y: 0,
        toJSON: () => ({}),
    } as DOMRect);
    vi.stubGlobal(
        "ResizeObserver",
        class {
            observe(): void {}
            disconnect(): void {}
        },
    );
}

describe("the chart windows use the shared shell", () => {
    it("depth chart window: drags by its header, resizes, persists under its own key", () => {
        render(<DepthChartOverlay open onClose={vi.fn()} book={BOOK} />);
        expect(screen.getByTestId("depth-overlay-resize-se")).not.toBeNull();
        drag(screen.getByTestId("depth-overlay-grip"), [700, 200], [720, 230]);
        const saved = JSON.parse(localStorage.getItem(SETTING_KEYS.depthWindowGeometry)!);
        expect(saved).toMatchObject({ w: 1040, h: 560 });
        expect(SETTING_KEYS.depthWindowGeometry).toBe("hpse.v1.depthWindow.geometry");
    });

    it("price chart window: drags by the chart band, resizes, persists under its own key", () => {
        render(<PriceChartOverlay open onClose={vi.fn()} tape={TAPE} sessionOpenPx={14_466_800} />);
        const band = document.querySelector(".price-chart__band")!;
        drag(band, [700, 300], [650, 280]);
        drag(screen.getByTestId("price-overlay-resize-s"), [700, 600], [700, 700]);
        const saved = JSON.parse(localStorage.getItem(SETTING_KEYS.priceWindowGeometry)!);
        expect(saved).toMatchObject({ w: 760, h: 450 });
        expect(SETTING_KEYS.priceWindowGeometry).toBe("hpse.v1.priceWindow.geometry");
    });
});

describe("the charts lay out at the window's size", () => {
    it("price chart in fit mode draws at the measured box, with the axes and tag reflowed", () => {
        stubBox(1000, 500);
        render(<PriceChart tape={TAPE} sessionOpenPx={14_466_800} fit />);
        const svg = screen.getByTestId("price-chart");
        expect(svg.getAttribute("viewBox")).toBe("0 0 1000 500");
        // The last point sits at the plot's right edge, and the last-price tag on the
        // right-hand axis follows the new width (1000 - 64 pad).
        const last = screen.getByTestId("price-chart-last");
        expect(Number(last.getAttribute("cx"))).toBe(1000 - 64);
        const tag = svg.querySelector(".price-chart__tag")!;
        expect(Number(tag.getAttribute("x"))).toBe(1000 - 64 + 30);
    });

    it("price chart off the window keeps its fixed 720 x 300 drawing", () => {
        stubBox(1000, 500);
        render(<PriceChart tape={TAPE} sessionOpenPx={14_466_800} />);
        expect(screen.getByTestId("price-chart").getAttribute("viewBox")).toBe("0 0 720 300");
    });

    it("depth chart in fit mode draws at the measured box and keeps the 13px mid label", () => {
        stubBox(1400, 700);
        render(<DepthCurve book={BOOK} variant="full" unitPx={1.4} />);
        const vb = screen.getByTestId("depth-curve").getAttribute("viewBox")!.split(" ").map(Number);
        expect(vb[2]).toBeCloseTo(1000, 6);
        expect(vb[3]).toBeCloseTo(500, 6);
        const label = screen.getByTestId("depth-curve-mid").querySelector(".depth-curve__mid-label")!;
        expect(Number(label.getAttribute("font-size")) * 1.4).toBeCloseTo(MID_LABEL_PX, 4);
    });
});
