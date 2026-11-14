import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { PriceChart, priceChartLayout } from "../src/components/PriceChart";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import type { ExecFrame } from "../src/protocol/messages";
import { SETTING_KEYS } from "../src/settings/persist";

// P19-10 follow-up: the readout sits in a strip above the plot, never over it.

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

function session(): AppState {
    return [trade(T0 + S, 1_000_000), trade(T0 + 2 * S, 1_000_100), trade(T0 + 61 * S, 1_000_200)].reduce(reducer, initialState);
}

describe("readout position (P19-10 follow-up)", () => {
    for (const type of ["line", "candles", "footprint"]) {
        it(`sits above the plot, aligned to its left edge, in ${type}`, () => {
            localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify(type));
            localStorage.setItem(SETTING_KEYS.priceChartBarSize, JSON.stringify("1m"));
            const s = session();
            render(<PriceChart tape={s.tape} bars={s.bars} sessionOpenPx={s.sessionOpenPx} surface="page" />);
            const layout = priceChartLayout(720, 300);
            const readout = screen.getByTestId("price-chart-readout");
            // Centred in the top padding, with half the 10px text height clear of the plot's top edge.
            expect(Number(readout.getAttribute("y")) + 5).toBeLessThan(layout.PLOT_TOP);
            expect(Number(readout.getAttribute("x"))).toBe(layout.PLOT_LEFT);
            expect(screen.queryByTestId("price-chart-readout-backing")).toBeNull();
        });
    }
});
