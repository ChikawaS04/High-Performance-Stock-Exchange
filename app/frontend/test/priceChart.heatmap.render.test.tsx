import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState, OwnFill } from "../src/state/reducer";
import type { BookFrame, ExecFrame, Side } from "../src/protocol/messages";
import type { WorkingOrder } from "../src/ownExecutions";
import { SETTING_KEYS } from "../src/settings/persist";
import { resetLastFolders } from "../src/settings/chartSettingsTree";
import { INDICATOR_FOLDERS, openFolder } from "./settingsFolders";
import { DepthRecorder } from "../src/heatmap/depthRecorder";
import { HEATMAP_TICK_NS, bucketStartNs } from "../src/heatmap/depthRing";
import { gridFor, nextDomain, xOfNs, yOfPx } from "../src/heatmap/layout";
import type { HeatView } from "../src/heatmap/layout";
import { installFakeCanvas } from "./fakeCanvas";

// P20-5: the heatmap chart type on the Charts page.

afterEach(() => {
    cleanup();
    localStorage.clear();
    resetLastFolders();
    vi.restoreAllMocks();
});

const T = 100;
const PX = 1_500_000;
const B0 = 7_000_000_000;
const MINE = 42;

function bookAt(b: number, bidQty: number): BookFrame {
    return {
        type: "BOOK",
        bestBid: PX,
        bestAsk: PX + T,
        bids: [
            [PX, bidQty],
            [PX - T, 300],
        ],
        asks: [[PX + T, 80]],
        timestamp: b * HEATMAP_TICK_NS + 1_000_000,
    };
}

function recorder(columns = 8): DepthRecorder {
    const rec = new DepthRecorder();
    for (let i = 0; i < columns; i++) rec.ingest(bookAt(B0 + i, 100 + i), bookAt(B0 + i, 0).timestamp + 2_000_000);
    return rec;
}

let tradeId = 0;
function fill(t: number, side: Side, qty: number, price = PX): Action {
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

function session(): AppState {
    const t0 = bucketStartNs(B0);
    return [fill(t0 + 300_000_000, "BUY", 100), fill(t0 + 900_000_000, "SELL", 400, PX + T)].reduce(reducer, initialState);
}

function setPage(type: string, indicators: readonly string[] = []): void {
    localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify(type));
    localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(indicators));
    localStorage.setItem(SETTING_KEYS.priceChartIndicatorsSeen, JSON.stringify(["volume", "vwap", "executions", "orders", "bidask", "volprofile", "cvd", "sma", "sma2", "ema", "ema2", "bollinger"]));
}

const stored = (key: string): unknown => JSON.parse(localStorage.getItem(key) ?? "null");

interface ChartOpts {
    readonly surface?: "page" | "window";
    readonly depth?: DepthRecorder;
    readonly fills?: readonly OwnFill[];
    readonly working?: readonly WorkingOrder[];
}

function chart(state: AppState, opts: ChartOpts = {}) {
    return render(
        <PriceChart
            tape={state.tape}
            bars={state.bars}
            sessionOpenPx={state.sessionOpenPx}
            surface={opts.surface ?? "page"}
            depth={opts.depth ?? recorder()}
            heatTrades={state.heatTrades}
            quotes={state.quotes}
            fills={opts.fills}
            working={opts.working}
            bestBid={PX}
            bestAsk={PX + T}
        />,
    );
}

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

// The nominal 720 x 300 drawing: plot left 10, width 646, top 24, height 250.
function heatView(columns = 8): HeatView {
    return { lo: bucketStartNs(B0), hi: bucketStartNs(B0 + columns), plotLeft: 10, plotTop: 24, plotW: 646, plotH: 250 };
}

describe("heatmap chart type (P20-5)", () => {
    it("is offered in the band on the page and not on the window", () => {
        chart(session());
        fireEvent.click(screen.getByTestId("pc-band-type-trigger"));
        expect(screen.getByTestId("pc-band-type-opt-heatmap")).toBeTruthy();
        cleanup();
        chart(session(), { surface: "window" });
        fireEvent.click(screen.getByTestId("pc-band-type-trigger"));
        expect(screen.queryByTestId("pc-band-type-opt-heatmap")).toBeNull();
    });

    it("drops a stored heatmap on the window to the default", () => {
        localStorage.setItem(SETTING_KEYS.priceWindowChartType, JSON.stringify("heatmap"));
        chart(session(), { surface: "window" });
        expect(screen.getByTestId("price-chart").getAttribute("data-chart-type")).toBe("candles");
    });

    it("draws the canvas under the plot with no panes and no bar size", () => {
        setPage("heatmap", ["volume", "cvd"]);
        chart(session());
        const svg = screen.getByTestId("price-chart");
        expect(svg.getAttribute("data-chart-type")).toBe("heatmap");
        expect(screen.getByTestId("heatmap-canvas")).toBeTruthy();
        expect(screen.queryByTestId("price-chart-vol")).toBeNull();
        expect(screen.queryByTestId("pc-band-size-trigger")).toBeNull();
        expect(document.querySelector(".price-chart--heatmap.price-chart--black")).not.toBeNull();
    });

    it("draws on black even when the Background is white", () => {
        setPage("heatmap");
        localStorage.setItem(SETTING_KEYS.priceChartBackground, JSON.stringify("white"));
        chart(session());
        expect(document.querySelector(".price-chart--black")).not.toBeNull();
        expect(document.querySelector(".price-chart--white")).toBeNull();
    });

    it("shows a quiet frame before any depth", () => {
        setPage("heatmap");
        chart(session(), { depth: new DepthRecorder() });
        expect(screen.getByText("No depth yet")).toBeTruthy();
        expect(screen.queryByTestId("heatmap-canvas")).toBeNull();
    });
});

describe("heatmap settings (D6)", () => {
    it("disables Background with its note and persists Contrast and Trades", () => {
        setPage("heatmap");
        chart(session());
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect(screen.getByTestId("pc-bg-row").className).toContain("settings-pop__row--disabled");
        expect(screen.getByTestId("pc-bg-note").textContent).toBe("Heatmap draws on black");
        expect((screen.getByTestId("pc-bg-white") as HTMLButtonElement).disabled).toBe(true);
        expect(screen.queryByTestId("pc-size-row")).toBeNull();
        openFolder("display/heatmap");
        expect((screen.getByTestId("pc-contrast-normal") as HTMLInputElement).checked).toBe(true);
        fireEvent.click(screen.getByTestId("pc-contrast-high"));
        expect(stored(SETTING_KEYS.priceChartHeatmapContrast)).toBe("high");
        fireEvent.click(screen.getByTestId("pc-trades-off"));
        expect(stored(SETTING_KEYS.priceChartHeatmapTrades)).toBe(false);
    });

    it("shows the heatmap rows disabled outside heatmap mode, and not on the window", () => {
        setPage("candles");
        chart(session());
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect(screen.queryByTestId("pc-bg-note")).toBeNull();
        openFolder("display/heatmap");
        expect(screen.getByTestId("pc-contrast-row").className).toContain("settings-pop__row--disabled");
        cleanup();
        chart(session(), { surface: "window" });
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect(screen.queryByTestId("pc-folder-display-heatmap")).toBeNull();
    });

    it("offers VWAP, Executions and Orders, and blocks the bar indicators and the profile", () => {
        setPage("heatmap");
        chart(session());
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        // Each indicator row lives in its folder (Price, Volume, Trading): gather them all.
        const rowClass: Record<string, string> = {};
        for (const f of INDICATOR_FOLDERS) {
            openFolder(f);
            for (const r of Array.from(screen.getByTestId("pc-settings-indicators").querySelectorAll("[data-testid$='-row']"))) {
                rowClass[r.getAttribute("data-testid")!] = r.className;
            }
        }
        for (const id of ["vwap", "executions", "orders"]) {
            expect(rowClass[`pc-ind-${id}-row`]).not.toContain("settings-pop__row--disabled");
        }
        for (const id of ["volume", "cvd", "sma", "bollinger", "bidask", "volprofile"]) {
            expect(rowClass[`pc-ind-${id}-row`]).toContain("settings-pop__row--disabled");
        }
    });
});

describe("heatmap overlays and readout (D5, F9 split)", () => {
    it("draws own order lines and fill markers over a casing", () => {
        setPage("heatmap", ["executions", "orders"]);
        const t = bucketStartNs(B0 + 2);
        const fills: OwnFill[] = [{ clOrdId: MINE, tradeId: 1, side: "BUY", pricePx: PX, qty: 50, timestamp: t, role: "aggressive", vwap: PX }];
        const working: WorkingOrder[] = [
            { clOrdId: MINE + 1, side: "SELL", ordType: "LIMIT", limitPx: PX + 2 * T, remainingQty: 30, displayQty: null },
        ];
        chart(session(), { fills, working });
        expect(screen.getAllByTestId("price-chart-fill")).toHaveLength(1);
        expect(screen.getAllByTestId("price-chart-order")).toHaveLength(1);
        expect(screen.getAllByTestId("price-chart-order-casing")).toHaveLength(1);
    });

    it("reads the resting size under the pointer, and the bests without one", () => {
        setPage("heatmap");
        stubSvgBox();
        chart(session());
        expect(screen.getByTestId("price-chart-readout-BID").textContent).toBe("150.00 x 107");
        const grid = gridFor(nextDomain(null, PX, PX + T)!);
        const v = heatView();
        const x = xOfNs(v, bucketStartNs(B0 + 3) + 100_000_000);
        const y = yOfPx(v, grid, PX - T);
        fireEvent.pointerMove(screen.getByTestId("price-chart"), { clientX: x, clientY: y });
        expect(screen.getByTestId("price-chart-readout-SIZE").textContent).toBe("300");
        expect(screen.getByTestId("price-chart-readout-PX").textContent).toBe("149.99");
        fireEvent.pointerMove(screen.getByTestId("price-chart"), { clientX: x, clientY: yOfPx(v, grid, PX) });
        expect(screen.getByTestId("price-chart-readout-SIZE").textContent).toBe("103");
    });

    it("draws bubbles only while Trades is on", () => {
        setPage("heatmap");
        const fake = installFakeCanvas();
        vi.spyOn(window, "requestAnimationFrame").mockImplementation((cb) => {
            cb(0);
            return 1;
        });
        chart(session());
        const arcs = () => fake.all.flatMap((c) => c.calls.filter((k) => k[0] === "arc")).length;
        expect(arcs()).toBe(2);
        cleanup();
        fake.all.length = 0;
        localStorage.setItem(SETTING_KEYS.priceChartHeatmapTrades, JSON.stringify(false));
        setPage("heatmap");
        chart(session());
        expect(arcs()).toBe(0);
    });

    it("pans back in time and returns with Latest", () => {
        setPage("heatmap");
        stubSvgBox();
        chart(session(), { depth: recorder(200) });
        const svg = screen.getByTestId("price-chart");
        expect(svg.getAttribute("data-following")).toBe("true");
        // Zoom in to the 10 s minimum, then drag right to go back in time.
        for (let i = 0; i < 10; i++) fireEvent.wheel(svg, { deltaY: -300, clientX: 300, clientY: 150 });
        act(() => {
            fireEvent.pointerDown(svg, { button: 0, pointerId: 1, clientX: 200, clientY: 150 });
            fireEvent.pointerMove(svg, { pointerId: 1, clientX: 500, clientY: 150 });
            fireEvent.pointerUp(svg, { pointerId: 1, clientX: 500, clientY: 150 });
        });
        expect(svg.getAttribute("data-following")).toBe("false");
        fireEvent.click(screen.getByTestId("pc-latest"));
        expect(screen.getByTestId("price-chart").getAttribute("data-following")).toBe("true");
    });
});
