import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import type { ExecFrame, Side } from "../src/protocol/messages";
import { SETTING_KEYS } from "../src/settings/persist";

// P19-4: the CVD pane.

afterEach(() => {
    cleanup();
    localStorage.clear();
});

const S = 1_000_000_000;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

let tradeId = 0;
function trade(t: number, qty: number, side: Side): Action {
    tradeId += 1;
    const frame: ExecFrame = {
        type: "EXEC",
        execType: "ORDER_FILLED",
        orderId: 5_000 + tradeId,
        tradeId,
        price: 1_000_000 + tradeId * 100,
        filledQuantity: qty,
        remainingQuantity: 0,
        aggressorOrderId: 5_000 + tradeId,
        passiveOrderId: 9_000 + tradeId,
        timestamp: t,
        aggressorSide: side,
    };
    return { type: "FRAME", frame };
}

/** Three 15s bars, the middle bucket empty (a gap): cvd 30, then 20 after a gap. */
function session(): AppState {
    return [trade(T0 + 1 * S, 30, "BUY"), trade(T0 + 31 * S, 10, "SELL")].reduce(reducer, initialState);
}

function chart(state: AppState, surface: "page" | "window" = "page") {
    return render(<PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={state.sessionOpenPx} surface={surface} />);
}

function points(): readonly (readonly [number, number])[] {
    const raw = screen.getByTestId("price-chart-cvd-line").getAttribute("points")!;
    return raw.split(" ").map((p) => {
        const [x, y] = p.split(",").map(Number);
        return [x, y] as const;
    });
}

describe("CVD pane (P19-4)", () => {
    it("draws the session delta per bar, holding flat across the gap", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["cvd"]));
        chart(session());
        const pts = points();
        // Bar 1 (cvd 30), the hold point at the gap slot, bar 3 (cvd 20).
        expect(pts.length).toBe(3);
        expect(pts[0][1]).toBeCloseTo(pts[1][1], 6);
        expect(pts[2][1]).toBeGreaterThan(pts[1][1]);
        // A one-way (all-positive) session: the domain is [0, 30], labelled top and bottom.
        expect(screen.getByTestId("price-chart-cvd-hi").textContent).toBe("+30");
        expect(screen.getByTestId("price-chart-cvd-lo").textContent).toBe("0");
    });

    it("puts the zero baseline at the pane's bottom on a one-way session", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["cvd"]));
        chart(session());
        const zeroY = Number(screen.getByTestId("price-chart-cvd-zero").getAttribute("y1"));
        const pts = points();
        // The top value sits higher than the baseline; the baseline is the lowest line in the pane.
        expect(pts[0][1]).toBeLessThan(zeroY);
        expect(pts.every(([, y]) => y <= zeroY)).toBe(true);
    });

    it("lays out below volume when both are on", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["volume", "cvd"]));
        chart(session());
        const volSep = screen.getByTestId("price-chart-vol").querySelector("line")!;
        const cvdSep = screen.getByTestId("price-chart-cvd").querySelector("line")!;
        expect(Number(cvdSep.getAttribute("y1"))).toBeGreaterThan(Number(volSep.getAttribute("y1")));
    });

    it("is absent in line mode", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["cvd"]));
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        chart(session());
        expect(screen.queryByTestId("price-chart-cvd")).toBeNull();
    });

    it("is absent on the floating window", () => {
        localStorage.setItem(SETTING_KEYS.priceWindowIndicators, JSON.stringify(["cvd"]));
        chart(session(), "window");
        expect(screen.queryByTestId("price-chart-cvd")).toBeNull();
    });
});
