import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { FOOTPRINT_CAP } from "../src/bars";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import type { ExecFrame, Side } from "../src/protocol/messages";
import { SETTING_KEYS } from "../src/settings/persist";

// P19-6: the footprint chart type.

afterEach(() => {
    cleanup();
    localStorage.clear();
});

const S = 1_000_000_000;
const M = 60 * S;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

let tradeId = 0;
function trade(t: number, price: number, qty: number, side: Side): Action {
    tradeId += 1;
    const frame: ExecFrame = {
        type: "EXEC",
        execType: "ORDER_FILLED",
        orderId: 5_000 + tradeId,
        tradeId,
        price,
        filledQuantity: qty,
        remainingQuantity: 0,
        aggressorOrderId: 5_000 + tradeId,
        passiveOrderId: 9_000 + tradeId,
        timestamp: t,
        aggressorSide: side,
    };
    return { type: "FRAME", frame };
}

/**
 * Two 1m bars. Bar 1 opens 100.00 and closes 100.02 (up): 10 sold at 100.00, 40
 * bought at 100.01 (4x the 10 below: a buy imbalance), 5 bought at 100.02. Bar 2
 * opens 100.02 and closes 100.00 (down): 30 sold at 100.00, 5 bought at 100.02.
 */
function session(): AppState {
    return [
        trade(T0 + 1 * S, 1_000_000, 10, "SELL"),
        trade(T0 + 2 * S, 1_000_100, 40, "BUY"),
        trade(T0 + 3 * S, 1_000_200, 5, "BUY"),
        trade(T0 + M + 1 * S, 1_000_200, 5, "BUY"),
        trade(T0 + M + 2 * S, 1_000_000, 30, "SELL"),
    ].reduce(reducer, initialState);
}

function setPage(type: string, size = "1m"): void {
    localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify(type));
    localStorage.setItem(SETTING_KEYS.priceChartBarSize, JSON.stringify(size));
    localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify([]));
}

function chart(state: AppState, surface: "page" | "window" = "page") {
    return render(<PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={state.sessionOpenPx} surface={surface} />);
}

const stored = (key: string): unknown => JSON.parse(localStorage.getItem(key) ?? "null");

describe("footprint chart type (P19-6)", () => {
    it("is offered in the band on the page and not on the window", () => {
        chart(session());
        fireEvent.click(screen.getByTestId("pc-band-type-trigger"));
        expect(screen.getByTestId("pc-band-type-opt-footprint")).toBeTruthy();
        cleanup();
        chart(session(), "window");
        fireEvent.click(screen.getByTestId("pc-band-type-trigger"));
        expect(screen.queryByTestId("pc-band-type-opt-footprint")).toBeNull();
    });

    it("drops a stored footprint on the window to the default", () => {
        localStorage.setItem(SETTING_KEYS.priceWindowChartType, JSON.stringify("footprint"));
        chart(session(), "window");
        expect(screen.getByTestId("price-chart").getAttribute("data-chart-type")).toBe("candles");
        expect(localStorage.getItem(SETTING_KEYS.priceWindowChartType)).toBeNull();
    });

    it("choosing footprint on 15s switches to 1m, which stays after leaving footprint", () => {
        setPage("candles", "15s");
        chart(session());
        fireEvent.click(screen.getByTestId("pc-band-type-trigger"));
        fireEvent.mouseDown(screen.getByTestId("pc-band-type-opt-footprint"));
        expect(stored(SETTING_KEYS.priceChartBarSize)).toBe("1m");
        expect(screen.getByTestId("price-chart").getAttribute("data-chart-type")).toBe("footprint");
        fireEvent.click(screen.getByTestId("pc-band-type-trigger"));
        fireEvent.mouseDown(screen.getByTestId("pc-band-type-opt-candles"));
        expect(stored(SETTING_KEYS.priceChartBarSize)).toBe("1m");
    });

    it("the band offers only 1m and 5m in footprint", () => {
        setPage("footprint");
        chart(session());
        fireEvent.click(screen.getByTestId("pc-band-size-trigger"));
        expect(screen.queryByTestId("pc-band-size-opt-5s")).toBeNull();
        expect(screen.queryByTestId("pc-band-size-opt-15s")).toBeNull();
        expect(screen.getByTestId("pc-band-size-opt-1m")).toBeTruthy();
        expect(screen.getByTestId("pc-band-size-opt-5m")).toBeTruthy();
    });

    it("the popup shows 5s and 15s disabled with the note", () => {
        setPage("footprint");
        chart(session());
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect((screen.getByTestId("pc-size-5s") as HTMLInputElement).disabled).toBe(true);
        expect((screen.getByTestId("pc-size-15s") as HTMLInputElement).disabled).toBe(true);
        expect((screen.getByTestId("pc-size-1m") as HTMLInputElement).disabled).toBe(false);
        expect(screen.getByTestId("pc-size-note").textContent).toBe("1m and 5m only");
    });

    it("draws cells, the imbalance tint and the open-close outline", () => {
        setPage("footprint");
        chart(session());
        expect(screen.queryAllByTestId("price-chart-candle")).toEqual([]);
        const bars = screen.getAllByTestId("price-chart-fp-bar");
        expect(bars.length).toBe(2);
        const texts = Array.from(bars[0].querySelectorAll('[data-testid="price-chart-fp-text"]')).map((t) => t.textContent);
        // Row step depends on the scale; the bar's total shares are in its cells either way.
        expect(texts.length).toBeGreaterThan(0);
        const imb = screen.getAllByTestId("price-chart-fp-cell").filter((c) => c.getAttribute("data-imbalance") === "buy");
        expect(imb.length).toBeGreaterThan(0);
        expect(imb[0].getAttribute("class")).toContain("price-chart__fp-cell--buy");
        const outlines = screen.getAllByTestId("price-chart-fp-outline");
        expect(outlines.map((o) => o.getAttribute("class"))).toEqual([
            "price-chart__fp-outline price-chart__fp-outline--up",
            "price-chart__fp-outline price-chart__fp-outline--down",
        ]);
    });

    it("tints with the user's picked up colour", () => {
        setPage("footprint");
        localStorage.setItem(SETTING_KEYS.priceChartCandleUp, JSON.stringify("#0fd9b6"));
        chart(session());
        const tint = screen.getAllByTestId("price-chart-fp-tint")[0];
        expect(tint.getAttribute("fill")).toBe("#0fd9b6");
        expect(screen.getAllByTestId("price-chart-fp-outline")[0].getAttribute("stroke")).toBe("#0fd9b6");
    });

    it("lays out at the 64px minimum column, following", () => {
        setPage("footprint");
        chart(session());
        const svg = screen.getByTestId("price-chart");
        expect(svg.getAttribute("data-following")).toBe("true");
        const outlines = screen.getAllByTestId("price-chart-fp-outline");
        // Column width is the slot less a pixel each side.
        expect(Number(outlines[0].getAttribute("width"))).toBe(62);
    });

    it("draws a bar without detail as a plain outline with a marker", () => {
        setPage("footprint");
        let s = initialState;
        for (let k = 0; k <= FOOTPRINT_CAP + 2; k++) s = reducer(s, trade(T0 + k * M + S, 1_000_000 + (k % 3) * 100, 10, "BUY"));
        chart(s);
        // 64px columns show the newest bars only; pan back to the oldest to find bars without detail.
        const svg = screen.getByTestId("price-chart");
        svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 720, height: 300, right: 720, bottom: 300, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
        fireEvent.pointerDown(svg, { button: 0, pointerId: 1, clientX: 100, clientY: 100 });
        fireEvent.pointerMove(svg, { pointerId: 1, clientX: 700, clientY: 100 });
        fireEvent.pointerMove(svg, { pointerId: 1, clientX: 700 + 64 * 200, clientY: 100 });
        fireEvent.pointerUp(svg, { pointerId: 1, clientX: 700 + 64 * 200, clientY: 100 });
        expect(screen.getAllByTestId("price-chart-fp-nodetail").length).toBeGreaterThan(0);
        expect(screen.getAllByTestId("price-chart-fp-plain").length).toBeGreaterThan(0);
    });

    it("the readout shows the hovered row's sell and buy", () => {
        setPage("footprint");
        chart(session());
        const svg = screen.getByTestId("price-chart");
        svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 720, height: 300, right: 720, bottom: 300, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
        // Hover the first bar's column at the cell holding the buy imbalance.
        const cell = screen.getAllByTestId("price-chart-fp-cell").find((c) => c.getAttribute("data-imbalance") === "buy")!;
        const tint = cell.querySelector("rect")!;
        const x = Number(tint.getAttribute("x")) + 10;
        const y = Number(tint.getAttribute("y")) + Number(tint.getAttribute("height")) / 2;
        fireEvent.pointerMove(svg, { pointerId: 1, clientX: x, clientY: y });
        expect(screen.getByTestId("price-chart-readout-ROW")).toBeTruthy();
        expect(screen.getByTestId("price-chart-readout-B").textContent).toBe("40");
        expect(screen.getByTestId("price-chart-readout-S").textContent).toBe("0");
    });
});
