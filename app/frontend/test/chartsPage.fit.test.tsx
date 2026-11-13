import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { ChartsPage } from "../src/pages/ChartsPage";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";

// The Charts page draws at its real size (Charts follow-up, decision 3).

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
});

const S = 1_000_000_000;
const NS = 15 * S;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

function session(n: number): AppState {
    const actions: Action[] = [];
    for (let i = 0; i < n; i++) {
        actions.push({
            type: "FRAME",
            frame: {
                type: "EXEC",
                execType: "ORDER_PARTIALLY_FILLED",
                orderId: 6000 + i,
                tradeId: i + 1,
                // A wide range, so the lowest price level sits right on the price area's floor.
                price: 1_000_000 + ((i * 7) % 23) * 1_000,
                filledQuantity: 1 + (i % 4),
                remainingQuantity: 0,
                aggressorOrderId: 6000 + i,
                passiveOrderId: -1,
                timestamp: T0 + i * NS + S,
            },
        });
    }
    return actions.reduce(reducer, { ...initialState, connection: "open" });
}

/** A measured box: the plot is `w` x `h` CSS pixels. */
function stubBox(w: number, h: number): void {
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockReturnValue({
        width: w,
        height: h,
        top: 0,
        left: 0,
        right: w,
        bottom: h,
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

function renderPage(state: AppState) {
    return render(
        <MemoryRouter>
            <ChartsPage state={state} onSubmitOrder={vi.fn()} onViewInTrading={vi.fn()} />
        </MemoryRouter>,
    );
}

describe("Charts page fit mode", () => {
    it("renders the chart in fit mode at its measured size", () => {
        stubBox(1100, 458);
        renderPage(session(120));
        expect(document.querySelector(".price-chart--fit.price-chart--page")).not.toBeNull();
        expect(screen.getByTestId("price-chart").getAttribute("viewBox")).toBe("0 0 1100 458");
    });

    it("never lets a price label overlap the volume pane's labels at the page's real size", () => {
        for (const [w, h] of [
            [1100, 458],
            [900, 375],
            [1400, 583],
        ] as const) {
            stubBox(w, h);
            const { unmount } = renderPage(session(120));
            const ys = (sel: string): number[] =>
                Array.from(document.querySelectorAll(sel)).map((t) => Number(t.getAttribute("y")));
            const price = ys('[data-testid="price-chart-y-label"]');
            const vol = [...ys('[data-testid="price-chart-vol-max"]'), ...ys('[data-testid="price-chart-vol-word"]')];
            expect(vol.length).toBe(2);
            for (const p of price) for (const v of vol) expect(Math.abs(p - v)).toBeGreaterThanOrEqual(12);
            // Price labels never collide with each other either.
            const sorted = [...price].sort((a, b) => a - b);
            for (let i = 1; i < sorted.length; i++) expect(sorted[i] - sorted[i - 1]).toBeGreaterThanOrEqual(12);
            unmount();
            vi.restoreAllMocks();
        }
    });

    it("x-axis labels never overlap at the page's real size", () => {
        stubBox(1100, 458);
        renderPage(session(120));
        const labels = screen.getAllByTestId("price-chart-x-label");
        expect(labels.length).toBeGreaterThan(2);
        const xs = labels.map((l) => Number(l.getAttribute("x")));
        for (let i = 1; i < xs.length; i++) expect(xs[i] - xs[i - 1]).toBeGreaterThanOrEqual(80);
    });
});
