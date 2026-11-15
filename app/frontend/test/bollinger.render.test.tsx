import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import type { ExecFrame } from "../src/protocol/messages";
import { SETTING_KEYS } from "../src/settings/persist";
import { resetLastFolders } from "../src/settings/chartSettingsTree";
import { openFolder } from "./settingsFolders";

// P19-9: Bollinger bands.

afterEach(() => {
    cleanup();
    localStorage.clear();
    resetLastFolders();
});

const S = 1_000_000_000;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

let tradeId = 0;
function trade(t: number, price: number): Action {
    tradeId += 1;
    const frame: ExecFrame = {
        type: "EXEC",
        execType: "ORDER_FILLED",
        orderId: 5_000 + tradeId,
        tradeId,
        price,
        filledQuantity: 10,
        remainingQuantity: 0,
        aggressorOrderId: 5_000 + tradeId,
        passiveOrderId: 9_000 + tradeId,
        timestamp: t,
        aggressorSide: "SELL",
    };
    return { type: "FRAME", frame };
}

/** Four 15s bars closing 100.00, 100.01, 100.00, 100.01. */
function session(): AppState {
    return [0, 1, 2, 3].map((k) => trade(T0 + k * 15 * S + S, 1_000_000 + (k % 2) * 100)).reduce(reducer, initialState);
}

function chart(state: AppState = session()) {
    return render(<PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={state.sessionOpenPx} surface="page" />);
}

function enable(params: Record<string, unknown>, type = "candles"): void {
    localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["bollinger"]));
    localStorage.setItem(SETTING_KEYS.priceChartIndicatorParams, JSON.stringify(params));
    localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify(type));
}

describe("Bollinger layer (P19-9)", () => {
    it("draws the middle line, both bands and the fill", () => {
        enable({ bollinger: { length: 2 } });
        chart();
        for (const id of ["price-chart-boll-mid", "price-chart-boll-upper", "price-chart-boll-lower", "price-chart-boll-fill"]) {
            expect(screen.getByTestId(id)).toBeTruthy();
        }
        // 4 bars, length 2: three points per line.
        expect(screen.getByTestId("price-chart-boll-mid").getAttribute("points")!.split(" ").length).toBe(3);
        // The upper band sits above the middle line (smaller y).
        const y = (id: string): number => Number(screen.getByTestId(id).getAttribute("points")!.split(" ")[0].split(",")[1]);
        expect(y("price-chart-boll-upper")).toBeLessThan(y("price-chart-boll-mid"));
        expect(y("price-chart-boll-lower")).toBeGreaterThan(y("price-chart-boll-mid"));
    });

    it("shows mid, upper and lower in the readout at the bar", () => {
        enable({ bollinger: { length: 2 } });
        chart();
        // Newest window: 100.00 and 100.01, mean 100.005, SD 0.005; width 2.0 gives 100.015 and 99.995.
        expect(screen.getByTestId("price-chart-readout-BB2").textContent).toBe("100.005");
        expect(screen.getByTestId("price-chart-readout-UB").textContent).toBe("100.015");
        expect(screen.getByTestId("price-chart-readout-LB").textContent).toBe("99.995");
    });

    it("draws nothing and reads nothing before the window fills", () => {
        enable({});
        chart();
        expect(screen.queryByTestId("price-chart-boll")).toBeNull();
        expect(screen.queryByTestId("price-chart-readout-BB20")).toBeNull();
    });

    it("is absent in line mode", () => {
        enable({ bollinger: { length: 2 } }, "line");
        chart();
        expect(screen.queryByTestId("price-chart-boll")).toBeNull();
    });

    it("shows the width as a standard deviation with one decimal in the gear popup", () => {
        enable({});
        chart();
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        openFolder("indicators/price");
        fireEvent.click(screen.getByTestId("pc-ind-bollinger-params"));
        expect(screen.getByTestId("pc-param-bollinger-width-row").textContent).toContain("Std dev");
        const width = screen.getByTestId("pc-param-bollinger-width-input") as HTMLInputElement;
        expect(width.value).toBe("2.0");
        fireEvent.change(width, { target: { value: "2.5" } });
        expect(JSON.parse(localStorage.getItem(SETTING_KEYS.priceChartIndicatorParams)!)).toEqual({ bollinger: { width: 25 } });
    });
});
