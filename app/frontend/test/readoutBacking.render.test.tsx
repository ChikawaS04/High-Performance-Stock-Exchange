import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import type { ExecFrame } from "../src/protocol/messages";
import { SETTING_KEYS } from "../src/settings/persist";
import { readoutTextWidth } from "../src/readoutBacking";

// P19-10 open item: the readout strip's backing.

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

/** Two 1m bars, so line, candles (1m) and footprint all have something to draw. */
function session(): AppState {
    return [trade(T0 + S, 1_000_000), trade(T0 + 2 * S, 1_000_100), trade(T0 + 61 * S, 1_000_200)].reduce(reducer, initialState);
}

function chart(type: string, bg = "black") {
    localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify(type));
    localStorage.setItem(SETTING_KEYS.priceChartBarSize, JSON.stringify("1m"));
    localStorage.setItem(SETTING_KEYS.priceChartBackground, JSON.stringify(bg));
    const s = session();
    return render(<PriceChart tape={s.tape} bars={s.bars} sessionOpenPx={s.sessionOpenPx} surface="page" />);
}

describe("readout backing (P19-10)", () => {
    for (const type of ["line", "candles", "footprint"]) {
        it(`draws under the readout text in ${type}`, () => {
            chart(type);
            const backing = screen.getByTestId("price-chart-readout-backing");
            const text = screen.getByTestId("price-chart-readout");
            // Same parent, backing first: SVG paints in document order, so it sits under the text.
            expect(backing.parentElement).toBe(text.parentElement);
            expect(backing.compareDocumentPosition(text) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
            expect(Number(backing.getAttribute("width"))).toBeGreaterThan(0);
        });
    }

    it("sizes to the readout's text", () => {
        chart("candles");
        const parts = Array.from(screen.getByTestId("price-chart-readout").children).map((part) => {
            const spans = part.querySelectorAll("tspan");
            return spans.length === 2 ? ([spans[0].textContent!, spans[1].textContent!] as const) : (["", spans[0].textContent!] as const);
        });
        const width = Number(screen.getByTestId("price-chart-readout-backing").getAttribute("width"));
        expect(width).toBe(readoutTextWidth(parts) + 8);
    });

    it("uses the black-ground backing on black and the white-ground backing on white", () => {
        chart("candles", "black");
        expect(screen.getByTestId("price-chart-readout-backing").getAttribute("fill")).toBe("rgba(0,0,0,0.85)");
        cleanup();
        chart("candles", "white");
        expect(screen.getByTestId("price-chart-readout-backing").getAttribute("fill")).toBe("rgba(255,255,255,0.85)");
    });

    it("never takes the pointer, so a move over it still updates the crosshair", () => {
        // Line mode snaps the crosshair to the nearest print from anywhere on the plot, so a
        // move over the top-left backing must still produce one.
        chart("line");
        const backing = screen.getByTestId("price-chart-readout-backing");
        expect(backing.getAttribute("pointer-events")).toBe("none");
        expect(screen.queryByTestId("price-chart-cross")).toBeNull();
        const svg = screen.getByTestId("price-chart");
        svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 720, height: 300, right: 720, bottom: 300, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
        const x = Number(backing.getAttribute("x")) + Number(backing.getAttribute("width")) - 2;
        const y = Number(backing.getAttribute("y")) + 3;
        fireEvent.pointerMove(svg, { pointerId: 1, clientX: x, clientY: y });
        expect(screen.getByTestId("price-chart-cross")).toBeTruthy();
    });
});
