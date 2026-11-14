import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { priceChartLayout } from "../src/components/PriceChart";
import { PROFILE_WIDTH_FRACTION } from "../src/indicators/volumeProfile";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import type { ExecFrame, Side } from "../src/protocol/messages";
import { SETTING_KEYS } from "../src/settings/persist";

// P19-3: the volume profile overlay.

afterEach(() => {
    cleanup();
    localStorage.clear();
});

const S = 1_000_000_000;
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

/** Volume 10 at 100.00, 40 at 100.05 (the POC), 20 at 100.10, across two 15s bars. */
function session(): AppState {
    return [
        trade(T0 + 1 * S, 1_000_000, 10, "SELL"),
        trade(T0 + 2 * S, 1_000_500, 40, "BUY"),
        trade(T0 + 16 * S, 1_001_000, 20, "BUY"),
    ].reduce(reducer, initialState);
}

function enable(key: string, ids: readonly string[]): void {
    localStorage.setItem(key, JSON.stringify(ids));
}

function chart(state: AppState, surface: "page" | "window" = "page") {
    return render(
        <PriceChart
            tape={state.tape}
            bars={state.bars}
            sessionOpenPx={state.sessionOpenPx}
            volumeAtPrice={state.volumeAtPrice}
            surface={surface}
        />,
    );
}

const rows = (): HTMLElement[] => screen.queryAllByTestId("price-chart-profile-row");

describe("volume profile layer (P19-3)", () => {
    it("is off by default", () => {
        chart(session());
        expect(screen.queryByTestId("price-chart-profile")).toBeNull();
    });

    it("draws one row per traded tick with widths proportional to volume, capped at a quarter of the plot", () => {
        enable(SETTING_KEYS.priceChartIndicators, ["volprofile"]);
        chart(session());
        const drawn = rows();
        expect(drawn.map((r) => r.getAttribute("data-volume"))).toEqual(["10", "40", "20"]);
        const widths = drawn.map((r) => Number(r.getAttribute("width")));
        const maxW = priceChartLayout(720, 300).PLOT_W * PROFILE_WIDTH_FRACTION;
        expect(widths[1]).toBeCloseTo(maxW, 6);
        expect(widths[0]).toBeCloseTo(maxW / 4, 6);
        expect(widths[2]).toBeCloseTo(maxW / 2, 6);
        // Anchored on the price axis: every row ends at the plot's right edge.
        const right = priceChartLayout(720, 300).PLOT_RIGHT;
        drawn.forEach((r, i) => expect(Number(r.getAttribute("x")) + widths[i]).toBeCloseTo(right, 6));
    });

    it("marks the POC and the value area", () => {
        enable(SETTING_KEYS.priceChartIndicators, ["volprofile"]);
        chart(session());
        // 40 of 70 is under 70%; above (20) beats below (10): POC plus the row above.
        expect(rows().map((r) => r.getAttribute("data-kind"))).toEqual(["out", "poc", "va"]);
        expect(rows()[1].getAttribute("class")).toContain("price-chart__profile-row--poc");
    });

    it("draws in line mode too", () => {
        enable(SETTING_KEYS.priceChartIndicators, ["volprofile"]);
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        chart(session());
        expect(rows().length).toBe(3);
    });

    it("is never offered on the floating window", () => {
        enable(SETTING_KEYS.priceWindowIndicators, ["volprofile"]);
        chart(session(), "window");
        expect(screen.queryByTestId("price-chart-profile")).toBeNull();
    });
});
