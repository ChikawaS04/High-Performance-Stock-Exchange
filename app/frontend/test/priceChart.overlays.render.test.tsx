import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { PriceChart } from "../src/components/PriceChart";
import { PriceChartOverlay } from "../src/components/PriceChartOverlay";
import { ChartsPage } from "../src/pages/ChartsPage";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState, OwnFill } from "../src/state/reducer";
import type { WorkingOrder } from "../src/ownExecutions";
import type { ExecFrame, Side } from "../src/protocol/messages";
import { newOrderFrame } from "../src/protocol/encode";
import { BOT_BLOCK } from "../src/participants";
import { formatPrice, formatQty } from "../src/format";
import { SETTING_KEYS } from "../src/settings/persist";

// P18-2: own fill markers on the chart.

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    localStorage.clear();
});

const S = 1_000_000_000;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);
const MINE = 1_763_000_000_001;
const MAKER = BOT_BLOCK + 1;
const TREND = 2 * BOT_BLOCK + 1;

let tradeId = 0;
function trade(t: number, agg: number, pas: number, price: number, qty: number, aggRemaining = 0): Action {
    tradeId += 1;
    const frame: ExecFrame = {
        type: "EXEC",
        execType: aggRemaining === 0 ? "ORDER_FILLED" : "ORDER_PARTIALLY_FILLED",
        orderId: agg,
        tradeId,
        price,
        filledQuantity: qty,
        remainingQuantity: aggRemaining,
        aggressorOrderId: agg,
        passiveOrderId: pas,
        timestamp: t,
        aggressorSide: "BUY",
    };
    return { type: "FRAME", frame };
}

function sent(id: number, side: Side, price: number, qty: number): Action {
    return { type: "SENT", frame: newOrderFrame(id, { side, ordType: "LIMIT", tif: "GTC", pricePx: price, qty, displayQty: 0 }) };
}

/** Bot trades in three 15s bars, plus one own buy fill in the second bar. */
function session(): AppState {
    return [
        trade(T0 + 1 * S, TREND + 1, MAKER, 1_000_000, 10),
        trade(T0 + 2 * S, TREND + 2, MAKER, 1_000_500, 10),
        sent(MINE, "BUY", 1_000_300, 20),
        trade(T0 + 16 * S, MINE, MAKER, 1_000_300, 20),
        trade(T0 + 17 * S, TREND + 3, MAKER, 999_800, 5),
        trade(T0 + 31 * S, TREND + 4, MAKER, 1_000_100, 5),
    ].reduce(reducer, initialState);
}

function own(t: number, side: Side, pricePx: number, qty: number): OwnFill {
    tradeId += 1;
    return { clOrdId: MINE, tradeId, side, pricePx, qty, timestamp: t, role: "aggressive", vwap: pricePx };
}

function renderChart(state: AppState, fills: readonly OwnFill[] = state.myFills) {
    return render(<PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={-1} fills={fills} />);
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

/** The centre of a marker path "M x y L ...": its x, and the mean of its three y values. */
function markerCentre(el: Element): { x: number; y: number } {
    const d = el.querySelector("path")?.getAttribute("d") ?? "";
    const nums = d.match(/-?\d+(\.\d+)?/g)?.map(Number) ?? [];
    return { x: nums[0], y: (nums[1] + nums[3] + nums[5]) / 3 };
}

describe("fill markers in candle mode (P18-2)", () => {
    it("draws one marker per own fill at its bar's slot centre, with the side class", () => {
        renderChart(session());
        const markers = screen.getAllByTestId("price-chart-fill");
        expect(markers).toHaveLength(1);
        expect(markers[0].getAttribute("data-side")).toBe("BUY");
        expect(markers[0].querySelector("path")?.getAttribute("class")).toBe("price-chart__fill price-chart__fill--buy");
        const wicks = screen.getAllByTestId("price-chart-wick").map((w) => Number(w.getAttribute("x1")));
        expect(markerCentre(markers[0]).x).toBeCloseTo(wicks[1], 9);
    });

    it("puts a buy below its price, pointing up, and a sell above it, pointing down", () => {
        const s = session();
        renderChart(s, [own(T0 + 17 * S, "SELL", 1_000_300, 5), own(T0 + 16 * S, "BUY", 1_000_300, 5)]);
        const [buy, sell] = ["BUY", "SELL"].map(
            (side) => screen.getAllByTestId("price-chart-fill").find((m) => m.getAttribute("data-side") === side)!,
        );
        const buyD = buy.querySelector("path")!.getAttribute("d")!.match(/-?\d+(\.\d+)?/g)!.map(Number);
        const sellD = sell.querySelector("path")!.getAttribute("d")!.match(/-?\d+(\.\d+)?/g)!.map(Number);
        // Apex first: a buy's apex is above its base, a sell's below.
        expect(buyD[1]).toBeLessThan(buyD[3]);
        expect(sellD[1]).toBeGreaterThan(sellD[3]);
        expect(markerCentre(buy).y).toBeGreaterThan(markerCentre(sell).y);
        expect(sell.querySelector("path")?.getAttribute("class")).toContain("price-chart__fill--sell");
    });

    it("stacks up to three fills in one bar and draws one count marker past three", () => {
        const s = session();
        const three = [1, 2, 3].map((i) => own(T0 + 15 * S + i * S, "BUY", 1_000_300, 5)).reverse();
        const { unmount } = renderChart(s, three);
        const ys = screen.getAllByTestId("price-chart-fill").map((m) => markerCentre(m).y);
        expect(ys).toHaveLength(3);
        expect(ys[1] - ys[0]).toBeCloseTo(3, 9);
        expect(ys[2] - ys[1]).toBeCloseTo(3, 9);
        expect(screen.queryByTestId("price-chart-fill-count")).toBeNull();
        unmount();

        const four = [1, 2, 3, 4].map((i) => own(T0 + 15 * S + i * S, "BUY", 1_000_300, 5)).reverse();
        renderChart(s, four);
        const markers = screen.getAllByTestId("price-chart-fill");
        expect(markers).toHaveLength(1);
        expect(markers[0].getAttribute("data-count")).toBe("4");
        expect(screen.getByTestId("price-chart-fill-count").textContent).toBe("4");
    });

    it("extends the readout with the hovered marker's fill", () => {
        stubSvgBox();
        renderChart(session());
        const c = markerCentre(screen.getByTestId("price-chart-fill"));
        fireEvent.pointerMove(screen.getByTestId("price-chart"), { clientX: c.x + 1, clientY: c.y });
        expect(screen.getByTestId("price-chart-readout-BUY").textContent).toBe(`${formatQty(20)} @ ${formatPrice(1_000_300)}`);
        // Away from the marker the readout drops the fill line.
        fireEvent.pointerMove(screen.getByTestId("price-chart"), { clientX: c.x, clientY: c.y - 40 });
        expect(screen.queryByTestId("price-chart-readout-BUY")).toBeNull();
    });

    it("the Executions toggle removes the markers", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["volume", "vwap"]));
        localStorage.setItem(SETTING_KEYS.priceChartIndicatorsSeen, JSON.stringify(["volume", "vwap", "executions", "orders"]));
        renderChart(session());
        expect(screen.queryByTestId("price-chart-fill")).toBeNull();
        expect(screen.queryByTestId("price-chart-fills")).toBeNull();
    });

    it("draws nothing with no own fills", () => {
        const s = session();
        renderChart(s, []);
        expect(screen.queryByTestId("price-chart-fills")).toBeNull();
    });
});

describe("fill markers in line mode (P18-2)", () => {
    it("places each marker on its fill's print", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        renderChart(session());
        const markers = screen.getAllByTestId("price-chart-fill");
        expect(markers).toHaveLength(1);
        // The line's points are "x,y" pairs; the own fill is the third print, oldest first.
        const pts = screen.getByTestId("price-chart-line").getAttribute("points")!.split(" ").map((p) => p.split(",").map(Number));
        expect(markerCentre(markers[0]).x).toBeCloseTo(pts[2][0], 9);
        expect(markerCentre(markers[0]).y).toBeGreaterThan(pts[2][1]);
    });
});

describe("fill markers on both surfaces (P18-2)", () => {
    it("the Charts page shows own fills only, never a bot's", () => {
        render(
            <MemoryRouter>
                <ChartsPage state={session()} onSubmitOrder={vi.fn()} onViewInTrading={vi.fn()} />
            </MemoryRouter>,
        );
        // Five trades printed, one of them ours.
        expect(screen.getAllByTestId("price-chart-candle").length).toBeGreaterThan(0);
        expect(screen.getAllByTestId("price-chart-fill")).toHaveLength(1);
    });

    it("the floating window draws the same markers", () => {
        const s = session();
        render(
            <PriceChartOverlay open onClose={vi.fn()} tape={s.tape} bars={s.bars} fills={s.myFills} sessionOpenPx={-1} />,
        );
        const win = screen.getByTestId("price-overlay");
        expect(within(win).getAllByTestId("price-chart-fill")).toHaveLength(1);
    });
});

// ---- P18-3: working order lines -------------------------------------------------

function wo(over: Partial<WorkingOrder> & Pick<WorkingOrder, "clOrdId">): WorkingOrder {
    return { side: "BUY", ordType: "LIMIT", limitPx: 1_000_000, remainingQty: 200, displayQty: null, ...over };
}

function renderOrders(working: readonly WorkingOrder[], bestBid = -1, bestAsk = -1, state: AppState = session()) {
    return render(
        <PriceChart tape={state.tape} bars={state.bars} sessionOpenPx={-1} working={working} bestBid={bestBid} bestAsk={bestAsk} />,
    );
}

function execFrame(execType: ExecFrame["execType"], orderId: number, t: number, price = -1, remaining = -1): Action {
    return {
        type: "FRAME",
        frame: {
            type: "EXEC",
            execType,
            orderId,
            tradeId: -1,
            price,
            filledQuantity: -1,
            remainingQuantity: remaining,
            aggressorOrderId: -1,
            passiveOrderId: -1,
            timestamp: t,
        },
    };
}

const lineY = (el: Element): number => Number(el.getAttribute("y1"));
const lineFor = (id: number): Element => document.querySelector(`[data-order-id="${id}"] [data-testid="price-chart-order"]`)!;
const tagFor = (id: number): Element => document.querySelector(`[data-order-id="${id}"] [data-testid="price-chart-order-tag"]`)!;

describe("working order lines (P18-3)", () => {
    it("draws one dashed line per working limit at its price, in the side colour, with its tag", () => {
        renderOrders([wo({ clOrdId: 1, limitPx: 1_000_000 }), wo({ clOrdId: 2, side: "SELL", limitPx: 1_000_400, remainingQty: 50 })]);
        expect(screen.getAllByTestId("price-chart-order")).toHaveLength(2);
        expect(lineFor(1).getAttribute("class")).toBe("price-chart__order price-chart__order--buy price-chart__order--limit");
        expect(lineFor(2).getAttribute("class")).toContain("price-chart__order--sell");
        // Higher price, higher on screen.
        expect(lineY(lineFor(2))).toBeLessThan(lineY(lineFor(1)));
        expect(tagFor(1).textContent).toBe("BUY 200 LMT");
        expect(tagFor(2).textContent).toBe("SELL 50 LMT");
        expect(tagFor(1).getAttribute("data-draggable")).toBe("true");
    });

    it("labels an iceberg with its displayed size", () => {
        renderOrders([wo({ clOrdId: 1, remainingQty: 500, displayQty: 100 })]);
        expect(tagFor(1).textContent).toBe("BUY 100/500 LMT");
    });

    it("draws a peg dotted at the exact mid, half a cent included, and never draggable", () => {
        renderOrders(
            [
                wo({ clOrdId: 1, limitPx: 1_000_000 }),
                wo({ clOrdId: 2, limitPx: 1_000_100 }),
                wo({ clOrdId: 3, ordType: "PEG_MID", limitPx: null, remainingQty: 30 }),
            ],
            1_000_000,
            1_000_100,
        );
        const peg = lineFor(3);
        expect(peg.getAttribute("data-kind")).toBe("peg");
        expect(peg.getAttribute("class")).toContain("price-chart__order--peg");
        // The mid of a one-tick spread sits exactly halfway, unsnapped.
        expect(lineY(peg)).toBeCloseTo((lineY(lineFor(1)) + lineY(lineFor(2))) / 2, 9);
        expect(tagFor(3).getAttribute("data-draggable")).toBe("false");
        expect(tagFor(3).textContent).toBe("BUY 30 PEG");
    });

    it("draws no peg line on a one-sided book", () => {
        renderOrders([wo({ clOrdId: 3, ordType: "PEG_MID", limitPx: null })], 1_000_000, -1);
        expect(screen.queryByTestId("price-chart-order")).toBeNull();
        expect(screen.queryByTestId("price-chart-order-tag")).toBeNull();
    });

    it("pins an order outside the visible prices to the edge as an arrow tag, with no line", () => {
        const { unmount } = renderOrders([]);
        const before = screen.getAllByTestId("price-chart-y-label").map((t) => t.textContent);
        unmount();
        renderOrders([wo({ clOrdId: 1, side: "SELL", limitPx: 2_000_000 }), wo({ clOrdId: 2, limitPx: 500_000 })]);
        expect(screen.queryByTestId("price-chart-order")).toBeNull();
        const tags = screen.getAllByTestId("price-chart-order-tag");
        expect(tags.map((t) => t.getAttribute("data-pin"))).toEqual(["top", "bottom"]);
        expect(tags[0].textContent).toBe("▲ SELL 200 LMT");
        expect(tags[1].textContent).toBe("▼ BUY 200 LMT");
        // Pinned orders never widen the price axis.
        expect(screen.getAllByTestId("price-chart-y-label").map((t) => t.textContent)).toEqual(before);
    });

    it("draws the lines in line mode too", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        renderOrders([wo({ clOrdId: 1 })]);
        expect(screen.getAllByTestId("price-chart-order")).toHaveLength(1);
    });

    it("the Orders toggle removes them", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicators, JSON.stringify(["volume"]));
        localStorage.setItem(SETTING_KEYS.priceChartIndicatorsSeen, JSON.stringify(["volume", "vwap", "executions", "orders"]));
        renderOrders([wo({ clOrdId: 1 })]);
        expect(screen.queryByTestId("price-chart-orders")).toBeNull();
    });

    it("a cancelled or filled order's line disappears on the next frame", () => {
        const live = [
            sent(MINE + 10, "BUY", 999_900, 10),
            execFrame("ORDER_ACCEPTED", MINE + 10, T0 + 40 * S, 999_900, 10),
            sent(MINE + 11, "SELL", 1_000_400, 5),
            execFrame("ORDER_ACCEPTED", MINE + 11, T0 + 41 * S, 1_000_400, 5),
        ].reduce(reducer, session());
        const page = (state: AppState) => (
            <MemoryRouter>
                <ChartsPage state={state} onSubmitOrder={vi.fn()} onViewInTrading={vi.fn()} />
            </MemoryRouter>
        );
        const { rerender } = render(page(live));
        expect(screen.getAllByTestId("price-chart-order-tag")).toHaveLength(2);
        const cancelled = reducer(live, execFrame("ORDER_CANCELLED", MINE + 10, T0 + 42 * S));
        rerender(page(cancelled));
        expect(screen.getAllByTestId("price-chart-order-tag").map((t) => t.textContent)).toEqual(["SELL 5 LMT"]);
        const filled = reducer(cancelled, trade(T0 + 43 * S, TREND + 9, MINE + 11, 1_000_400, 5));
        rerender(page(filled));
        expect(screen.queryByTestId("price-chart-order-tag")).toBeNull();
    });

    it("the floating window draws the same lines", () => {
        const s = session();
        render(<PriceChartOverlay open onClose={vi.fn()} tape={s.tape} bars={s.bars} working={[wo({ clOrdId: 1 })]} sessionOpenPx={-1} />);
        expect(within(screen.getByTestId("price-overlay")).getAllByTestId("price-chart-order")).toHaveLength(1);
    });
});
