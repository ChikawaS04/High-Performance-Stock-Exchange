import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart, priceChartLayout } from "../src/components/PriceChart";
import { PriceChartOverlay } from "../src/components/PriceChartOverlay";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import { SETTING_KEYS } from "../src/settings/persist";
import type { IndicatorDescriptor } from "../src/indicators/registry";

// P17-11: the floating window runs the same chart on its own settings and pane cap.

beforeEach(() => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1600 });
    Object.defineProperty(window, "innerHeight", { configurable: true, value: 900 });
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
});

const S = 1_000_000_000;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

/** `n` consecutive 15s bars, one fill each. */
function bars(n: number): AppState {
    const actions: Action[] = [];
    for (let i = 0; i < n; i++) {
        actions.push({
            type: "FRAME",
            frame: {
                type: "EXEC",
                execType: "ORDER_PARTIALLY_FILLED",
                orderId: 5000 + i,
                tradeId: i + 1,
                price: 1_000_000 + (i % 9) * 100,
                filledQuantity: 1 + (i % 3),
                remainingQuantity: 0,
                aggressorOrderId: 5000 + i,
                passiveOrderId: -1,
                timestamp: T0 + i * 15 * S + S,
            },
        });
    }
    return actions.reduce(reducer, initialState);
}

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

describe("floating window parity (P17-11)", () => {
    it("the window and the page render different chart types at the same time", () => {
        localStorage.setItem(SETTING_KEYS.priceWindowChartType, JSON.stringify("line"));
        const state = bars(10);
        render(
            <>
                <div data-testid="page">
                    <PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={-1} surface="page" />
                </div>
                <PriceChartOverlay open onClose={vi.fn()} tape={state.tape} bars={state.bars} sessionOpenPx={-1} />
            </>,
        );
        const page = screen.getByTestId("page").querySelector('[data-testid="price-chart"]')!;
        const win = screen.getByTestId("price-overlay").querySelector('[data-testid="price-chart"]')!;
        expect(page.getAttribute("data-chart-type")).toBe("candles");
        expect(win.getAttribute("data-chart-type")).toBe("line");
        expect(page.querySelectorAll('[data-testid="price-chart-candle"]')).toHaveLength(10);
        expect(win.querySelector('[data-testid="price-chart-line"]')).not.toBeNull();
    });

    it("resizing the window changes the visible slot count", () => {
        const state = bars(300);
        const count = (w: number): number => {
            stubBox(w, 400);
            const { unmount } = render(
                <PriceChartOverlay open onClose={vi.fn()} tape={state.tape} bars={state.bars} sessionOpenPx={-1} />,
            );
            const n = screen.getAllByTestId("price-chart-candle").length;
            unmount();
            vi.restoreAllMocks();
            return n;
        };
        const narrow = count(400);
        const wide = count(1200);
        expect(narrow).toBe(Math.floor(priceChartLayout(400, 400).PLOT_W / 5));
        expect(wide).toBe(Math.floor(priceChartLayout(1200, 400).PLOT_W / 5));
        expect(wide).toBeGreaterThan(narrow);
    });

    it("the window never lays out more than one pane; the page lays out both", () => {
        const TWO_PANES: IndicatorDescriptor[] = [
            { id: "volume", label: "Volume", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
            { id: "flow", label: "Flow", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
        ];
        localStorage.setItem(SETTING_KEYS.priceWindowIndicators, JSON.stringify(["volume", "flow"]));
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["volume", "flow"]));
        const state = bars(5);
        const L = priceChartLayout(720, 300);
        const paneH = Math.floor(0.2 * L.PLOT_H);

        const sepY = (surface: "page" | "window"): number => {
            const { unmount } = render(
                <PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={-1} surface={surface} registry={TWO_PANES} />,
            );
            const y = Number(screen.getByTestId("price-chart-vol").querySelector("line")!.getAttribute("y1"));
            unmount();
            return y;
        };
        // Window: one pane (volume) under a price area of PLOT_H - paneH.
        expect(sepY("window")).toBe(L.PLOT_TOP + L.PLOT_H - paneH);
        // Page: two panes, so volume (the first) starts two pane heights up.
        expect(sepY("page")).toBe(L.PLOT_TOP + L.PLOT_H - 2 * paneH);
    });

    it("a pointer drag on the plot moves the crosshair, never the window", () => {
        const state = bars(5);
        render(<PriceChartOverlay open onClose={vi.fn()} tape={state.tape} bars={state.bars} sessionOpenPx={-1} />);
        const before = screen.getByTestId("price-overlay").getAttribute("style");
        const svg = screen.getByTestId("price-chart");
        fireEvent.pointerDown(svg, { pointerId: 1, button: 0, clientX: 600, clientY: 300 });
        fireEvent.pointerMove(svg, { pointerId: 1, clientX: 500, clientY: 250 });
        fireEvent.pointerUp(svg, { pointerId: 1, clientX: 500, clientY: 250 });
        expect(screen.getByTestId("price-overlay").getAttribute("style")).toBe(before);
        expect(localStorage.getItem(SETTING_KEYS.priceWindowGeometry)).toBeNull();
    });
});
