import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import type { ExecFrame } from "../src/protocol/messages";
import { SETTING_KEYS } from "../src/settings/persist";

// P19-8: moving average lines and their readout.

afterEach(() => {
    cleanup();
    localStorage.clear();
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
        aggressorSide: "BUY",
    };
    return { type: "FRAME", frame };
}

/** Four 15s bars closing 100.00, 100.10, 100.20, 100.30. */
function session(): AppState {
    return [0, 1, 2, 3].map((k) => trade(T0 + k * 15 * S + S, 1_000_000 + k * 1_000)).reduce(reducer, initialState);
}

function setup(prefix: "priceChart" | "priceWindow", ids: readonly string[], params: Record<string, unknown>, type = "candles"): void {
    const keys =
        prefix === "priceChart"
            ? [SETTING_KEYS.priceChartIndicators, SETTING_KEYS.priceChartIndicatorParams, SETTING_KEYS.priceChartChartType]
            : [SETTING_KEYS.priceWindowIndicators, SETTING_KEYS.priceWindowIndicatorParams, SETTING_KEYS.priceWindowChartType];
    localStorage.setItem(keys[0], JSON.stringify(ids));
    localStorage.setItem(keys[1], JSON.stringify(params));
    localStorage.setItem(keys[2], JSON.stringify(type));
}

function chart(surface: "page" | "window" = "page") {
    const s = session();
    return render(<PriceChart tape={s.tape} bars={s.bars} sessionOpenPx={s.sessionOpenPx} surface={surface} />);
}

const pointCount = (id: string): number =>
    screen.getByTestId(`price-chart-ma-${id}`).getAttribute("points")!.split(" ").length;

describe("moving average lines (P19-8)", () => {
    it("draws two instances of different lengths, the second dashed", () => {
        setup("priceChart", ["sma", "sma2"], { sma: { length: 2 }, sma2: { length: 3 } });
        chart();
        // 4 bars: a length-2 SMA has 3 points, a length-3 SMA has 2.
        expect(pointCount("sma")).toBe(3);
        expect(pointCount("sma2")).toBe(2);
        expect(screen.getByTestId("price-chart-ma-sma").getAttribute("class")).toBe("price-chart__ma price-chart__ma--sma");
        expect(screen.getByTestId("price-chart-ma-sma2").getAttribute("class")).toContain("price-chart__ma--second");
    });

    it("draws an EMA with its own class", () => {
        setup("priceChart", ["ema"], { ema: { length: 2 } });
        chart();
        expect(screen.getByTestId("price-chart-ma-ema").getAttribute("class")).toContain("price-chart__ma--ema");
    });

    it("draws nothing before the window fills", () => {
        setup("priceChart", ["sma"], {});
        chart();
        // The default length 20 needs 20 bars; there are 4.
        expect(screen.queryByTestId("price-chart-ma-sma")).toBeNull();
        expect(screen.queryByTestId("price-chart-readout-SMA20")).toBeNull();
    });

    it("is absent in line mode", () => {
        setup("priceChart", ["sma"], { sma: { length: 2 } }, "line");
        chart();
        expect(screen.queryByTestId("price-chart-mas")).toBeNull();
    });

    it("draws on the floating window too", () => {
        setup("priceWindow", ["sma"], { sma: { length: 2 } });
        chart("window");
        expect(pointCount("sma")).toBe(3);
    });

    it("draws in footprint", () => {
        setup("priceChart", ["sma"], { sma: { length: 2 } }, "footprint");
        localStorage.setItem(SETTING_KEYS.priceChartBarSize, JSON.stringify("1m"));
        const s = [0, 1, 2].map((k) => trade(T0 + k * 60 * S + S, 1_000_000 + k * 1_000)).reduce(reducer, initialState);
        render(<PriceChart tape={s.tape} bars={s.bars} sessionOpenPx={s.sessionOpenPx} surface="page" />);
        expect(pointCount("sma")).toBe(2);
    });
});

describe("moving average readout (P19-8, amendment 6)", () => {
    it("shows each enabled average at the bar, labelled with its length, key in its colour", () => {
        setup("priceChart", ["sma", "ema"], { sma: { length: 2 }, ema: { length: 3 } });
        chart();
        // Newest bar: SMA2 = (100.20 + 100.30) / 2 = 100.25.
        expect(screen.getByTestId("price-chart-readout-SMA2").textContent).toBe("100.25");
        expect(screen.getByTestId("price-chart-readout-EMA3")).toBeTruthy();
        const key = screen.getByTestId("price-chart-readout-SMA2").previousElementSibling as SVGElement;
        expect(key.textContent).toBe("SMA2");
        expect(key.getAttribute("style")).toContain("var(--ind-sma)");
        // Values take the text ink, as VWAP's does.
        expect(screen.getByTestId("price-chart-readout-SMA2").getAttribute("fill")).toBe(
            screen.getByTestId("price-chart-readout-C").getAttribute("fill"),
        );
    });
});
