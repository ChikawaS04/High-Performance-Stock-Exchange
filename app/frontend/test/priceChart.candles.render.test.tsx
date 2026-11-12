import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart, priceChartLayout } from "../src/components/PriceChart";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import type { ExecFrame } from "../src/protocol/messages";
import { formatClockNanos, formatPrice, formatQty } from "../src/format";
import { emptyBars } from "../src/bars";
import { SETTING_KEYS } from "../src/settings/persist";

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    localStorage.clear();
});

const S = 1_000_000_000;
const NS = 15 * S;
// Clock-aligned to 5m so the 15s buckets start exactly on T0.
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

let tradeId = 0;
function fill(t: number, price: number, qty: number): Action {
    tradeId += 1;
    const frame: ExecFrame = {
        type: "EXEC",
        execType: "ORDER_PARTIALLY_FILLED",
        orderId: 9000 + tradeId,
        tradeId,
        price,
        filledQuantity: qty,
        remainingQuantity: 0,
        aggressorOrderId: 9000 + tradeId,
        passiveOrderId: -1,
        timestamp: t,
    };
    return { type: "FRAME", frame };
}

/**
 * Three 15s bars over four slots: an up bar, a down bar, a gap, then a doji.
 *   bar 0: 1,000,000 -> 1,000,500 high -> close 1,000,300 (up), volume 30
 *   bar 1: 1,000,200 -> 999,800 low -> close 999,900 (down), volume 15
 *   bar 3: one fill at 1,000,000 (doji), volume 5
 */
function session(): AppState {
    return [
        fill(T0 + 1 * S, 1_000_000, 10),
        fill(T0 + 2 * S, 1_000_500, 10),
        fill(T0 + 3 * S, 1_000_300, 10),
        fill(T0 + 16 * S, 1_000_200, 5),
        fill(T0 + 17 * S, 999_800, 5),
        fill(T0 + 18 * S, 999_900, 5),
        fill(T0 + 46 * S, 1_000_000, 5),
    ].reduce(reducer, initialState);
}

// Page layout at the nominal 720 x 300.
const L = priceChartLayout(720, 300);

function renderPage(state: AppState) {
    return render(<PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={-1} />);
}

describe("candle rendering (P17-6)", () => {
    it("renders candles by default, one body and one wick per bar, with the up or down class", () => {
        renderPage(session());
        expect(screen.getByTestId("price-chart").getAttribute("data-chart-type")).toBe("candles");
        const candles = screen.getAllByTestId("price-chart-candle");
        expect(candles).toHaveLength(3);
        expect(screen.getAllByTestId("price-chart-body")).toHaveLength(3);
        expect(screen.getAllByTestId("price-chart-wick")).toHaveLength(3);
        expect(candles.map((c) => c.getAttribute("class"))).toEqual([
            "price-chart__candle price-chart__candle--up",
            "price-chart__candle price-chart__candle--down",
            "price-chart__candle price-chart__candle--up",
        ]);
        // Line-mode marks are absent.
        expect(screen.queryByTestId("price-chart-line")).toBeNull();
        expect(screen.queryByTestId("price-chart-last")).toBeNull();
    });

    it("draws a doji (open equals close) as a 1px body", () => {
        renderPage(session());
        const bodies = screen.getAllByTestId("price-chart-body");
        expect(bodies[2].getAttribute("height")).toBe("1");
        expect(Number(bodies[0].getAttribute("height"))).toBeGreaterThan(1);
    });

    it("leaves the gap bucket's slot empty: the doji sits two slots right of the down bar", () => {
        renderPage(session());
        const x = screen.getAllByTestId("price-chart-wick").map((w) => Number(w.getAttribute("x1")));
        const slot = x[1] - x[0];
        expect(x[2] - x[1]).toBeCloseTo(2 * slot, 9);
        // Right-aligned: the newest slot ends at the plot's right edge.
        expect(x[2] + slot / 2).toBeCloseTo(L.PLOT_RIGHT, 9);
    });

    it("reads the last-price tag from the open bar's close, in its direction colour", () => {
        renderPage(session());
        const tag = screen.getByTestId("price-chart-tag");
        expect(tag.textContent).toBe(formatPrice(1_000_000));
        expect(tag.querySelector("rect")!.getAttribute("class")).toContain("price-chart__tagbg--up");
        expect(screen.getByTestId("price-chart-level").getAttribute("class")).toContain("price-chart__level--up");
    });

    it("colours the tag down when the open bar closed below its open", () => {
        const state = [fill(T0 + S, 1_000_500, 1), fill(T0 + 2 * S, 1_000_000, 1)].reduce(reducer, initialState);
        renderPage(state);
        expect(screen.getByTestId("price-chart-tag").querySelector("rect")!.getAttribute("class")).toContain("--down");
    });

    it("labels the time axis in clock time at bar boundaries", () => {
        renderPage(session());
        const labels = screen.getAllByTestId("price-chart-x-label").map((l) => l.textContent);
        expect(labels.length).toBeGreaterThan(0);
        for (const l of labels) expect(l).toMatch(/^\d{2}:\d{2}:\d{2}$/);
        expect(labels).toContain(formatClockNanos(T0, "s"));
    });

    it("shows the No trades yet state with no bars", () => {
        render(<PriceChart tape={[]} bars={emptyBars()} sessionOpenPx={-1} />);
        expect(screen.getByTestId("price-chart").textContent).toContain("No trades yet");
        expect(screen.queryByTestId("price-chart-candle")).toBeNull();
    });

    it("still renders the P12 print line in line mode", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        renderPage(session());
        expect(screen.getByTestId("price-chart-line")).not.toBeNull();
        expect(screen.queryByTestId("price-chart-candle")).toBeNull();
        for (const l of screen.getAllByTestId("price-chart-x-label")) expect(l.textContent).toMatch(/^\d{2}:\d{2}:\d{2}$/);
    });

    it("follows the selected bar size", () => {
        localStorage.setItem(SETTING_KEYS.priceChartBarSize, JSON.stringify("1m"));
        renderPage(session());
        expect(screen.getAllByTestId("price-chart-candle")).toHaveLength(1);
    });
});

describe("volume pane (P17-7)", () => {
    it("draws one column per visible bar, the tallest reaching the pane top", () => {
        renderPage(session());
        const cols = screen.getAllByTestId("price-chart-vol-bar");
        expect(cols).toHaveLength(3);
        const h = cols.map((c) => Number(c.getAttribute("height")));
        const y = cols.map((c) => Number(c.getAttribute("y")));
        // Heights proportional to volume 30 : 15 : 5.
        expect(h[1] / h[0]).toBeCloseTo(0.5, 9);
        expect(h[2] / h[0]).toBeCloseTo(5 / 30, 9);
        // One pane on the page plot: 20% of the plot height, under the price area.
        const paneH = Math.floor(0.2 * L.PLOT_H);
        const paneTop = L.PLOT_BOTTOM - paneH;
        expect(y[0]).toBeCloseTo(paneTop, 9);
        expect(h[0]).toBeCloseTo(paneH, 9);
        // Bottoms all on the pane floor.
        for (let i = 0; i < 3; i++) expect(y[i] + h[i]).toBeCloseTo(L.PLOT_BOTTOM, 9);
        expect(screen.getByTestId("price-chart-vol-max").textContent).toBe(formatQty(30));
    });

    it("aligns each column with its candle body", () => {
        renderPage(session());
        const bodies = screen.getAllByTestId("price-chart-body");
        const cols = screen.getAllByTestId("price-chart-vol-bar");
        cols.forEach((c, i) => {
            expect(c.getAttribute("x")).toBe(bodies[i].getAttribute("x"));
            expect(c.getAttribute("width")).toBe(bodies[i].getAttribute("width"));
            expect(c.getAttribute("class")).toContain(i === 1 ? "--down" : "--up");
        });
    });

    it("is absent when volume is off and absent in line mode", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["vwap"]));
        const { unmount } = renderPage(session());
        expect(screen.queryByTestId("price-chart-vol")).toBeNull();
        unmount();

        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["volume", "vwap"]));
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        renderPage(session());
        expect(screen.queryByTestId("price-chart-vol")).toBeNull();
    });
});

describe("VWAP overlay (P17-8)", () => {
    it("renders the VWAP line and tag in candle mode when on", () => {
        const state = session();
        renderPage(state);
        const line = screen.getByTestId("price-chart-vwap");
        expect(line.getAttribute("points")!.split(" ")).toHaveLength(3);
        expect(line.getAttribute("clip-path")).toMatch(/^url\(#pc-clip-/);
        expect(screen.getByTestId("price-chart-vwap-tag").textContent).toBe(formatPrice(state.bars["5s"].open!.vwap));
    });

    it("renders the VWAP step line in line mode when on", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        renderPage(session());
        expect(screen.getByTestId("price-chart-vwap")).not.toBeNull();
        expect(screen.getByTestId("price-chart-vwap-tag")).not.toBeNull();
    });

    it("is absent when off, in both modes", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["volume"]));
        const { unmount } = renderPage(session());
        expect(screen.queryByTestId("price-chart-vwap")).toBeNull();
        expect(screen.queryByTestId("price-chart-vwap-tag")).toBeNull();
        unmount();
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        renderPage(session());
        expect(screen.queryByTestId("price-chart-vwap")).toBeNull();
    });

    it("never widens the price domain: the gridlines are the same with VWAP on and off", () => {
        const levels = () =>
            Array.from(document.querySelectorAll(".price-chart__y-label")).map((t) => t.textContent);
        const { unmount } = renderPage(session());
        const on = levels();
        unmount();
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["volume"]));
        renderPage(session());
        expect(levels()).toEqual(on);
    });
});

describe("crosshair and readout (P17-10)", () => {
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

    const read = (k: string): string | null => screen.getByTestId(`price-chart-readout-${k}`).textContent;

    it("shows the newest bar in the readout with no pointer", () => {
        renderPage(session());
        expect(read("O")).toBe(formatPrice(1_000_000));
        expect(read("C")).toBe(formatPrice(1_000_000));
        expect(read("V")).toBe(formatQty(5));
        expect(screen.getByTestId("price-chart-readout-VWAP")).not.toBeNull();
        expect(screen.queryByTestId("price-chart-cross")).toBeNull();
    });

    it("draws hairlines and tags on move, snapped to the slot centre, and reads the hovered bar", () => {
        stubSvgBox();
        renderPage(session());
        const wicks = screen.getAllByTestId("price-chart-wick").map((w) => Number(w.getAttribute("x1")));
        const svg = screen.getByTestId("price-chart");
        // A little left of the down bar's centre, in the price area.
        fireEvent.pointerMove(svg, { clientX: wicks[1] - 3, clientY: 80 });
        const cross = screen.getByTestId("price-chart-cross");
        expect(cross.getAttribute("pointer-events")).toBe("none");
        expect(Number(screen.getByTestId("price-chart-cross-v").getAttribute("x1"))).toBeCloseTo(wicks[1], 9);
        expect(Number(screen.getByTestId("price-chart-cross-h").getAttribute("y1"))).toBe(80);
        expect(screen.getByTestId("price-chart-cross-price").textContent).toMatch(/^\d+\.\d+$/);
        expect(screen.getByTestId("price-chart-cross-time").textContent).toBe(formatClockNanos(T0 + NS, "s"));
        expect(read("O")).toBe(formatPrice(1_000_200));
        expect(read("H")).toBe(formatPrice(1_000_200));
        expect(read("L")).toBe(formatPrice(999_800));
        expect(read("C")).toBe(formatPrice(999_900));
        expect(read("V")).toBe(formatQty(15));

        fireEvent.pointerLeave(svg);
        expect(screen.queryByTestId("price-chart-cross")).toBeNull();
        expect(read("O")).toBe(formatPrice(1_000_000));
    });

    it("drops the price hairline over the volume pane but keeps the time snap", () => {
        stubSvgBox();
        renderPage(session());
        const wicks = screen.getAllByTestId("price-chart-wick").map((w) => Number(w.getAttribute("x1")));
        fireEvent.pointerMove(screen.getByTestId("price-chart"), { clientX: wicks[0], clientY: L.PLOT_BOTTOM - 5 });
        expect(screen.getByTestId("price-chart-cross-v")).not.toBeNull();
        expect(screen.queryByTestId("price-chart-cross-h")).toBeNull();
    });

    it("reads the newest bar over a gap slot", () => {
        stubSvgBox();
        renderPage(session());
        const wicks = screen.getAllByTestId("price-chart-wick").map((w) => Number(w.getAttribute("x1")));
        const gapX = (wicks[1] + wicks[2]) / 2;
        fireEvent.pointerMove(screen.getByTestId("price-chart"), { clientX: gapX, clientY: 80 });
        expect(Number(screen.getByTestId("price-chart-cross-v").getAttribute("x1"))).toBeCloseTo(gapX, 9);
        expect(screen.getByTestId("price-chart-cross-time").textContent).toBe(formatClockNanos(T0 + 2 * NS, "s"));
        expect(read("O")).toBe(formatPrice(1_000_000));
    });

    it("omits VWAP from the readout when VWAP is off", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["volume"]));
        renderPage(session());
        expect(screen.queryByTestId("price-chart-readout-VWAP")).toBeNull();
    });

    it("snaps to the nearest print in line mode and reads price x size @ time", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        stubSvgBox();
        renderPage(session());
        // Newest print with no pointer.
        expect(read("px")).toBe(formatPrice(1_000_000));
        expect(read("x")).toBe(formatQty(5));
        expect(read("@")).toBe(formatClockNanos(T0 + 46 * S, "s"));
        // The oldest print is at the plot's left edge.
        fireEvent.pointerMove(screen.getByTestId("price-chart"), { clientX: L.PLOT_LEFT + 1, clientY: 80 });
        expect(Number(screen.getByTestId("price-chart-cross-v").getAttribute("x1"))).toBeCloseTo(L.PLOT_LEFT, 9);
        expect(read("px")).toBe(formatPrice(1_000_000));
        expect(read("x")).toBe(formatQty(10));
        expect(read("@")).toBe(formatClockNanos(T0 + S, "s"));
    });

    it("ignores a pointer outside the plot", () => {
        stubSvgBox();
        renderPage(session());
        fireEvent.pointerMove(screen.getByTestId("price-chart"), { clientX: L.PLOT_RIGHT + 20, clientY: 80 });
        expect(screen.queryByTestId("price-chart-cross")).toBeNull();
    });
});
