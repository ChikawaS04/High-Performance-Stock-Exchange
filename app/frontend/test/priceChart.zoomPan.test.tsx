import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart, priceChartLayout } from "../src/components/PriceChart";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import { SETTING_KEYS } from "../src/settings/persist";
import { ZOOM_STEP } from "../src/chartViewport";
import { formatPrice } from "../src/format";

// Mouse wheel zoom, drag to pan and the Latest button (Charts follow-up, decisions
// 7 and 8). The svg's box is stubbed to the nominal 720 x 300 drawing, so a client
// coordinate equals a drawing coordinate.

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    localStorage.clear();
});

const S = 1_000_000_000;
const NS = 15 * S;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);
const L = priceChartLayout(720, 300);

/** `n` consecutive 15s bars, one fill each, rising. */
function bars(n: number): AppState {
    const actions: Action[] = [];
    for (let i = 0; i < n; i++) {
        actions.push({
            type: "FRAME",
            frame: {
                type: "EXEC",
                execType: "ORDER_PARTIALLY_FILLED",
                orderId: 7000 + i,
                tradeId: i + 1,
                price: 1_000_000 + i * 100,
                filledQuantity: 1,
                remainingQuantity: 0,
                aggressorOrderId: 7000 + i,
                passiveOrderId: -1,
                timestamp: T0 + i * NS + S,
            },
        });
    }
    return actions.reduce(reducer, initialState);
}

function stubSvgBox(): void {
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockReturnValue({
        width: 720,
        height: 300,
        top: 0,
        left: 0,
        right: 720,
        bottom: 300,
        x: 0,
        y: 0,
        toJSON: () => ({}),
    } as DOMRect);
}

function renderChart(state: AppState) {
    stubSvgBox();
    return render(<PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={-1} />);
}

const svg = (): SVGSVGElement => screen.getByTestId("price-chart") as unknown as SVGSVGElement;
const wickXs = (): number[] => screen.getAllByTestId("price-chart-wick").map((w) => Number(w.getAttribute("x1")));
const slotWidth = (): number => {
    const x = wickXs();
    return x[x.length - 1] - x[x.length - 2];
};

function wheel(deltaY: number, clientX = 300, clientY = 120): WheelEvent {
    const ev = new WheelEvent("wheel", { deltaY, deltaMode: 0, clientX, clientY, bubbles: true, cancelable: true });
    act(() => {
        svg().dispatchEvent(ev);
    });
    return ev;
}

function drag(fromX: number, toX: number, y = 120): void {
    fireEvent.pointerDown(svg(), { pointerId: 1, button: 0, clientX: fromX, clientY: y });
    fireEvent.pointerMove(svg(), { pointerId: 1, clientX: toX, clientY: y });
    fireEvent.pointerUp(svg(), { pointerId: 1, clientX: toX, clientY: y });
}

describe("mouse wheel zoom", () => {
    it("wheel up zooms in (wider slots, fewer bars) and wheel down zooms out", () => {
        renderChart(bars(300));
        const w0 = slotWidth();
        const n0 = wickXs().length;
        wheel(-100);
        expect(slotWidth()).toBeCloseTo(w0 * ZOOM_STEP, 6);
        expect(wickXs().length).toBeLessThan(n0);
        wheel(100);
        wheel(100);
        expect(slotWidth()).toBeCloseTo(w0 / ZOOM_STEP, 6);
    });

    it("keeps the newest bar pinned at the price axis while following", () => {
        renderChart(bars(300));
        wheel(-300);
        const x = wickXs();
        expect(x[x.length - 1] + slotWidth() / 2).toBeCloseTo(L.PLOT_RIGHT, 6);
        expect(svg().getAttribute("data-following")).toBe("true");
    });

    it("stops the page scrolling over the plot, and lets it scroll elsewhere", () => {
        renderChart(bars(50));
        expect(wheel(-100, 300, 120).defaultPrevented).toBe(true);
        // Over the right-hand price axis, outside the plot: the page scrolls normally.
        expect(wheel(-100, L.PLOT_RIGHT + 30, 120).defaultPrevented).toBe(false);
    });

    it("refits the y axis to the bars in view", () => {
        renderChart(bars(300));
        const labels = () => screen.getAllByTestId("price-chart-y-label").map((t) => t.textContent);
        const before = labels();
        wheel(-1000);
        expect(labels()).not.toEqual(before);
    });
});

describe("drag to pan and the Latest button", () => {
    it("dragging right moves back in time; Latest appears only when not following", () => {
        renderChart(bars(300));
        expect(screen.queryByTestId("pc-latest")).toBeNull();
        const w = slotWidth();
        drag(400, 400 + 10.5 * w);
        expect(svg().getAttribute("data-following")).toBe("false");
        expect(screen.getByTestId("pc-latest")).not.toBeNull();
        // The readout shows the newest bar in view, ten bars back.
        expect(screen.getByTestId("price-chart-readout-C").textContent).toBe(formatPrice(1_000_000 + 289 * 100));
        fireEvent.click(screen.getByTestId("pc-latest"));
        expect(svg().getAttribute("data-following")).toBe("true");
        expect(screen.queryByTestId("pc-latest")).toBeNull();
    });

    it("dragging back to the right edge resumes following", () => {
        renderChart(bars(300));
        const w = slotWidth();
        drag(400, 400 + 5 * w);
        expect(svg().getAttribute("data-following")).toBe("false");
        drag(400, 400 - 20 * w);
        expect(svg().getAttribute("data-following")).toBe("true");
    });

    it("moves in whole slots: the newest bar in view is a whole number of bars back", () => {
        renderChart(bars(300));
        const w = slotWidth();
        drag(400, 400 + 7.6 * w);
        // 7.6 slots is 7 whole slots: the close in view is bar 292's.
        expect(screen.getByTestId("price-chart-readout-C").textContent).toBe(formatPrice(1_000_000 + 292 * 100));
    });

    it("under 3px is a click, not a pan", () => {
        renderChart(bars(300));
        drag(400, 402);
        expect(svg().getAttribute("data-following")).toBe("true");
    });

    it("hides the crosshair while dragging", () => {
        renderChart(bars(300));
        fireEvent.pointerMove(svg(), { pointerId: 1, clientX: 400, clientY: 120 });
        expect(screen.queryByTestId("price-chart-cross")).not.toBeNull();
        fireEvent.pointerDown(svg(), { pointerId: 1, button: 0, clientX: 400, clientY: 120 });
        fireEvent.pointerMove(svg(), { pointerId: 1, clientX: 460, clientY: 120 });
        expect(screen.queryByTestId("price-chart-cross")).toBeNull();
        expect(svg().getAttribute("class")).toContain("price-chart__svg--panning");
        fireEvent.pointerUp(svg(), { pointerId: 1, clientX: 460, clientY: 120 });
        expect(svg().getAttribute("class")).not.toContain("price-chart__svg--panning");
    });

    it("cannot pan when every bar fits", () => {
        renderChart(bars(20));
        drag(400, 700);
        expect(svg().getAttribute("data-following")).toBe("true");
    });

    it("stops auto-scrolling on new bars while panned back", () => {
        const state = bars(300);
        const { rerender } = renderChart(state);
        const w = slotWidth();
        drag(400, 400 + 10 * w);
        const before = screen.getByTestId("price-chart-readout-C").textContent;
        const next = [
            {
                type: "FRAME",
                frame: {
                    type: "EXEC",
                    execType: "ORDER_PARTIALLY_FILLED",
                    orderId: 9999,
                    tradeId: 9999,
                    price: 2_000_000,
                    filledQuantity: 1,
                    remainingQuantity: 0,
                    aggressorOrderId: 9999,
                    passiveOrderId: -1,
                    timestamp: T0 + 300 * NS + S,
                },
            } as Action,
        ].reduce(reducer, state);
        rerender(<PriceChart tape={next.tape} bars={next.bars} sessionOpenPx={-1} />);
        expect(screen.getByTestId("price-chart-readout-C").textContent).toBe(before);
        expect(svg().getAttribute("data-following")).toBe("false");
    });
});

describe("line mode zoom and pan", () => {
    it("zooms the tape's time window and pans it, with Latest returning to live", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        renderChart(bars(100));
        const linePoints = () => screen.getByTestId("price-chart-line").getAttribute("points")!.split(" ").length;
        const full = linePoints();
        wheel(-500);
        expect(linePoints()).toBeLessThan(full);
        expect(svg().getAttribute("data-following")).toBe("true");
        drag(300, 500);
        expect(svg().getAttribute("data-following")).toBe("false");
        expect(screen.queryByTestId("price-chart-last")).toBeNull();
        fireEvent.click(screen.getByTestId("pc-latest"));
        expect(svg().getAttribute("data-following")).toBe("true");
        expect(screen.getByTestId("price-chart-last")).not.toBeNull();
    });
});
