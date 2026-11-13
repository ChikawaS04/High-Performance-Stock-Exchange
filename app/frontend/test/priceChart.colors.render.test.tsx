import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { PriceChart } from "../src/components/PriceChart";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import { SETTING_KEYS } from "../src/settings/persist";

// Wicks and colours (Charts follow-up, decisions 5 and 6).

afterEach(() => {
    cleanup();
    localStorage.clear();
});

const CSS = readFileSync(resolve(__dirname, "../src/styles/terminal.css"), "utf-8");
const S = 1_000_000_000;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

let tradeId = 0;
function fill(t: number, price: number, qty = 1): Action {
    tradeId += 1;
    return {
        type: "FRAME",
        frame: {
            type: "EXEC",
            execType: "ORDER_PARTIALLY_FILLED",
            orderId: 8000 + tradeId,
            tradeId,
            price,
            filledQuantity: qty,
            remainingQuantity: 0,
            aggressorOrderId: 8000 + tradeId,
            passiveOrderId: -1,
            timestamp: t,
        },
    };
}

/** One 15s bar that traded both ways: open 1,000,200, high 1,000,500, low 999,800, close 1,000,300. */
function twoWay(): AppState {
    return [
        fill(T0 + 1 * S, 1_000_200),
        fill(T0 + 2 * S, 1_000_500),
        fill(T0 + 3 * S, 999_800),
        fill(T0 + 4 * S, 1_000_300),
    ].reduce(reducer, initialState);
}

/** An up bar then a down bar. */
function upDown(): AppState {
    return [fill(T0 + S, 1_000_000), fill(T0 + 2 * S, 1_000_400), fill(T0 + 16 * S, 1_000_400), fill(T0 + 17 * S, 1_000_100)].reduce(
        reducer,
        initialState,
    );
}

const renderChart = (state: AppState) => render(<PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={-1} />);

describe("wicks", () => {
    it("draw visibly past both ends of the body when the bar traded above and below it", () => {
        renderChart(twoWay());
        const wick = screen.getByTestId("price-chart-wick");
        const body = screen.getByTestId("price-chart-body");
        const top = Number(body.getAttribute("y"));
        const bottom = top + Number(body.getAttribute("height"));
        const y1 = Number(wick.getAttribute("y1"));
        const y2 = Number(wick.getAttribute("y2"));
        expect(y1).toBeLessThan(top - 1);
        expect(y2).toBeGreaterThan(bottom + 1);
    });

    it("match the body by default and take the dim ink when set to Grey", () => {
        const { unmount } = renderChart(twoWay());
        expect(screen.getByTestId("price-chart-wick").getAttribute("stroke")).toBeNull();
        unmount();
        localStorage.setItem(SETTING_KEYS.priceChartWicks, JSON.stringify("grey"));
        const again = renderChart(twoWay());
        expect(screen.getByTestId("price-chart-wick").getAttribute("stroke")).toBe("#8f8f8f");
        again.unmount();
        localStorage.setItem(SETTING_KEYS.priceChartBackground, JSON.stringify("white"));
        renderChart(twoWay());
        expect(screen.getByTestId("price-chart-wick").getAttribute("stroke")).toBe("#6b6b6b");
    });

    it("Wicks in the gear popup persists Grey", () => {
        renderChart(twoWay());
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        fireEvent.click(screen.getByTestId("pc-wicks-grey"));
        expect(localStorage.getItem(SETTING_KEYS.priceChartWicks)).toBe(JSON.stringify("grey"));
        expect(screen.getByTestId("price-chart-wick").getAttribute("stroke")).toBe("#8f8f8f");
    });
});

describe("chosen candle colours", () => {
    it("bodies, wicks, volume, the level and the tag all draw from the candle tokens", () => {
        // A pick sets the token on the chart root; these rules carry it to every mark.
        for (const dir of ["up", "down"]) {
            expect(CSS).toMatch(new RegExp(`\\.price-chart__candle--${dir} \\{ fill: var\\(--candle-${dir}\\); stroke: var\\(--candle-${dir}\\); \\}`));
            expect(CSS).toMatch(new RegExp(`\\.price-chart__vol--${dir} \\{ fill: var\\(--candle-${dir}\\); \\}`));
            expect(CSS).toMatch(new RegExp(`\\.price-chart__level--${dir} \\{ stroke: var\\(--candle-${dir}\\); \\}`));
            expect(CSS).toMatch(new RegExp(`\\.price-chart__tagbg--${dir} \\{ fill: var\\(--candle-${dir}\\); \\}`));
        }
        expect(CSS).toMatch(/\.price-chart__vol \{ opacity: 0\.6;/);
    });

    it("reach the chart as the candle tokens on both grounds, with the tag ink by luminance", () => {
        localStorage.setItem(SETTING_KEYS.priceChartCandleUp, JSON.stringify("#ffe234"));
        localStorage.setItem(SETTING_KEYS.priceChartCandleDown, JSON.stringify("#1f5fd1"));
        for (const bg of ["black", "white"]) {
            localStorage.setItem(SETTING_KEYS.priceChartBackground, JSON.stringify(bg));
            const { unmount } = renderChart(upDown());
            const root = document.querySelector(".price-chart") as HTMLElement;
            expect(root.style.getPropertyValue("--candle-up")).toBe("#ffe234");
            expect(root.style.getPropertyValue("--candle-down")).toBe("#1f5fd1");
            // The newest bar is down: the tag takes the down colour's legible ink.
            expect(screen.getByTestId("price-chart-tag-text").style.fill).toBe("rgb(255, 255, 255)");
            unmount();
        }
    });

    it("leave the ground's defaults in place until picked", () => {
        renderChart(upDown());
        const root = document.querySelector(".price-chart") as HTMLElement;
        expect(root.style.getPropertyValue("--candle-up")).toBe("");
        // Default down on black is #ff0000: white ink, as before.
        expect(screen.getByTestId("price-chart-tag-text").style.fill).toBe("rgb(255, 255, 255)");
    });

    it("a light up colour gets the dark tag ink", () => {
        localStorage.setItem(SETTING_KEYS.priceChartCandleUp, JSON.stringify("#ffe234"));
        renderChart([fill(T0 + S, 1_000_000), fill(T0 + 2 * S, 1_000_400)].reduce(reducer, initialState));
        expect(screen.getByTestId("price-chart-tag-text").style.fill).toBe("rgb(11, 11, 11)");
    });
});

describe("gear popup colours", () => {
    function openGear(state: AppState = upDown()) {
        renderChart(state);
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
    }

    it("orders Display as Background, Chart type, Bar size, Candles rows, Line rows", () => {
        openGear();
        const panel = screen.getByTestId("pc-settings").querySelector(".settings-pop__panel")!;
        const names = Array.from(panel.querySelectorAll(".settings-pop__name, .settings-pop__sub")).map((n) => n.textContent);
        expect(names).toEqual([
            "Background",
            "Chart type",
            "Bar size",
            "Candles",
            "Up candle",
            "Down candle",
            "Wicks",
            "Line",
            "Line color",
            "Area fill",
            "Fill color",
        ]);
        expect(screen.getByTestId("pc-reset-colors").textContent).toBe("Reset colors");
    });

    it("each colour row shows a chip with its hex and opens the palette", () => {
        openGear();
        expect(screen.getByTestId("pc-up-chip").textContent).toContain("#00FF00");
        expect(screen.getByTestId("pc-down-chip").textContent).toContain("#FF0000");
        fireEvent.click(screen.getByTestId("pc-up-chip"));
        const pal = screen.getByTestId("pc-up-palette");
        expect(pal.querySelectorAll(".color-pop__grid button")).toHaveLength(42);
        expect(screen.getByTestId("pc-up-#00ff00").getAttribute("aria-checked")).toBe("true");
    });

    it("up, down, line and fill change independently, and picks feed Recent", () => {
        openGear();
        fireEvent.click(screen.getByTestId("pc-up-chip"));
        fireEvent.click(screen.getByTestId("pc-up-#0fd9b6"));
        expect(screen.queryByTestId("pc-up-palette")).toBeNull();
        fireEvent.click(screen.getByTestId("pc-down-chip"));
        fireEvent.click(screen.getByTestId("pc-down-#ff7a18"));
        expect(localStorage.getItem(SETTING_KEYS.priceChartCandleUp)).toBe(JSON.stringify("#0fd9b6"));
        expect(localStorage.getItem(SETTING_KEYS.priceChartCandleDown)).toBe(JSON.stringify("#ff7a18"));
        expect(localStorage.getItem(SETTING_KEYS.priceChartLineColor)).toBeNull();
        fireEvent.click(screen.getByTestId("pc-down-chip"));
        expect(screen.getByTestId("pc-down-recent-#ff7a18")).not.toBeNull();
        expect(screen.getByTestId("pc-down-recent-#0fd9b6")).not.toBeNull();
    });

    it("the Hex field applies #RRGGBB on Enter and ignores anything else", () => {
        openGear();
        fireEvent.click(screen.getByTestId("pc-up-chip"));
        const hex = screen.getByTestId("pc-up-hex") as HTMLInputElement;
        fireEvent.change(hex, { target: { value: "#12345" } });
        fireEvent.keyDown(hex, { key: "Enter" });
        expect(localStorage.getItem(SETTING_KEYS.priceChartCandleUp)).toBeNull();
        fireEvent.change(hex, { target: { value: "#ABCDEF" } });
        fireEvent.keyDown(hex, { key: "Enter" });
        expect(localStorage.getItem(SETTING_KEYS.priceChartCandleUp)).toBe(JSON.stringify("#abcdef"));
    });

    it("the candle rows are disabled in line mode and the line rows in candle mode", () => {
        openGear();
        expect(screen.getByTestId("pc-up-row").className).not.toContain("--disabled");
        expect((screen.getByTestId("pc-line-chip") as HTMLButtonElement).disabled).toBe(true);
        fireEvent.click(screen.getByTestId("pc-type-line"));
        expect(screen.getByTestId("pc-up-row").className).toContain("--disabled");
        expect(screen.getByTestId("pc-wicks-row").className).toContain("--disabled");
        expect((screen.getByTestId("pc-line-chip") as HTMLButtonElement).disabled).toBe(false);
    });

    it("Fill color keeps Match line", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        openGear();
        expect(screen.getByTestId("pc-fill-chip").textContent).toContain("Match line");
        fireEvent.click(screen.getByTestId("pc-fill-chip"));
        fireEvent.click(screen.getByTestId("pc-fill-#3b93ff"));
        expect(screen.getByTestId("pc-fill-chip").textContent).toContain("#3B93FF");
        fireEvent.click(screen.getByTestId("pc-fill-chip"));
        fireEvent.click(screen.getByTestId("pc-fill-line"));
        expect(localStorage.getItem(SETTING_KEYS.priceChartFill)).toBe(JSON.stringify("line"));
    });

    it("Reset colors restores the defaults", () => {
        openGear();
        fireEvent.click(screen.getByTestId("pc-up-chip"));
        fireEvent.click(screen.getByTestId("pc-up-#0fd9b6"));
        fireEvent.click(screen.getByTestId("pc-wicks-grey"));
        fireEvent.click(screen.getByTestId("pc-reset-colors"));
        expect(screen.getByTestId("pc-up-chip").textContent).toContain("#00FF00");
        expect(screen.getByTestId("pc-wicks-body").getAttribute("aria-pressed")).toBe("true");
        expect(localStorage.getItem(SETTING_KEYS.priceChartCandleUp)).toBe(JSON.stringify("default"));
        const root = document.querySelector(".price-chart") as HTMLElement;
        expect(root.style.getPropertyValue("--candle-up")).toBe("");
    });
});
