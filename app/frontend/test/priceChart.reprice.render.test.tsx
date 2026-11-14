import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { PriceChartOverlay } from "../src/components/PriceChartOverlay";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import type { WorkingOrder } from "../src/ownExecutions";
import { formatPrice } from "../src/format";
import { isOnTick } from "../src/prices";

// P18-4: drag a working limit order's tag to reprice it, confirmed in Modify order.

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    localStorage.clear();
});

const S = 1_000_000_000;
const NS = 15 * S;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

/** `n` 15s bars of anonymous prints between 999_000 and 1_001_000. */
function session(n = 40): AppState {
    const actions: Action[] = [];
    for (let i = 0; i < n; i++) {
        actions.push({
            type: "FRAME",
            frame: {
                type: "EXEC",
                execType: "ORDER_PARTIALLY_FILLED",
                orderId: 8000 + i,
                tradeId: i + 1,
                price: i % 2 === 0 ? 999_000 : 1_001_000,
                filledQuantity: 1,
                remainingQuantity: 0,
                aggressorOrderId: 8000 + i,
                passiveOrderId: -1,
                timestamp: T0 + i * NS + S,
            },
        });
    }
    return actions.reduce(reducer, initialState);
}

function stubBox(): void {
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

const BUY: WorkingOrder = { clOrdId: 11, side: "BUY", ordType: "LIMIT", limitPx: 1_000_000, remainingQty: 300, displayQty: null };
const SELL: WorkingOrder = { clOrdId: 12, side: "SELL", ordType: "LIMIT", limitPx: 1_000_400, remainingQty: 50, displayQty: null };
const PEG: WorkingOrder = { clOrdId: 13, side: "BUY", ordType: "PEG_MID", limitPx: null, remainingQty: 20, displayQty: null };

interface Opts {
    readonly working?: readonly WorkingOrder[];
    readonly onReprice?: (id: number, px: number) => void;
    readonly repricing?: readonly number[];
    readonly bestBid?: number;
    readonly bestAsk?: number;
}

function renderChart(opts: Opts = {}) {
    stubBox();
    const s = session();
    const onReprice = opts.onReprice ?? vi.fn();
    const utils = render(
        <PriceChart
            tape={s.tape}
            bars={s.bars}
            sessionOpenPx={-1}
            working={opts.working ?? [BUY, SELL]}
            bestBid={opts.bestBid ?? 999_900}
            bestAsk={opts.bestAsk ?? 1_000_600}
            onReprice={onReprice}
            repricing={opts.repricing}
        />,
    );
    return { ...utils, onReprice };
}

const svg = (): Element => screen.getByTestId("price-chart");
const lineY = (id: number): number =>
    Number(document.querySelector(`[data-order-id="${id}"] [data-testid="price-chart-order"]`)!.getAttribute("y1"));
const tag = (id: number): Element => document.querySelector(`[data-order-id="${id}"] [data-testid="price-chart-order-tag"]`)!;

/** Drawing units per price unit, from the BUY and SELL lines 400 units apart. */
function yForPrice(px: number): number {
    const y0 = lineY(BUY.clOrdId);
    const k = (y0 - lineY(SELL.clOrdId)) / (SELL.limitPx! - BUY.limitPx!);
    return y0 - (px - BUY.limitPx!) * k;
}

function dragTag(id: number, toY: number, toX = 600, up = true): void {
    const y0 = lineY(id);
    fireEvent.pointerDown(tag(id), { pointerId: 3, button: 0, clientX: 650, clientY: y0 });
    fireEvent.pointerMove(svg(), { pointerId: 3, clientX: toX, clientY: toY });
    if (up) fireEvent.pointerUp(svg(), { pointerId: 3, clientX: toX, clientY: toY });
}

describe("drag to reprice (P18-4)", () => {
    it("moves a ghost line snapped to the cent, with the new price in its tag", () => {
        renderChart();
        const target = yForPrice(1_000_230);
        dragTag(BUY.clOrdId, target, 600, false);
        const ghost = screen.getByTestId("price-chart-order-ghost");
        expect(screen.getByTestId("price-chart-order-ghost-text").textContent).toBe(formatPrice(1_000_200));
        expect(Number(ghost.querySelector("line")!.getAttribute("y1"))).toBeCloseTo(yForPrice(1_000_200), 6);
        // The original line stays where it was until the order is repriced.
        expect(lineY(BUY.clOrdId)).toBeCloseTo(yForPrice(1_000_000), 6);
    });

    it("dropping back on the old price cancels the gesture: no popup, nothing sent", () => {
        const { onReprice } = renderChart();
        dragTag(BUY.clOrdId, yForPrice(1_000_020));
        expect(screen.queryByTestId("price-chart-order-ghost")).toBeNull();
        expect(screen.queryByTestId("modify-order")).toBeNull();
        expect(onReprice).not.toHaveBeenCalled();
    });

    it("dropping on a new price opens Modify order with the old and new price", () => {
        renderChart();
        dragTag(BUY.clOrdId, yForPrice(999_700));
        expect(screen.queryByTestId("price-chart-order-ghost")).toBeNull();
        expect(screen.getByTestId("modify-id").textContent).toBe("11");
        expect(screen.getByTestId("modify-qty").textContent).toBe("300");
        expect(screen.getByTestId("modify-old").textContent).toBe(formatPrice(1_000_000));
        expect(screen.getByTestId("modify-new").textContent).toBe(formatPrice(999_700));
        expect(isOnTick(999_700)).toBe(true);
    });

    it("Confirm Modify calls the reprice seam exactly once and closes the popup", () => {
        const { onReprice } = renderChart();
        dragTag(BUY.clOrdId, yForPrice(999_700));
        const btn = screen.getByTestId("modify-confirm");
        fireEvent.click(btn);
        fireEvent.click(btn);
        expect(onReprice).toHaveBeenCalledTimes(1);
        expect(onReprice).toHaveBeenCalledWith(11, 999_700);
        expect(screen.queryByTestId("modify-order")).toBeNull();
    });

    it("Escape, the exit, an outside click and Enter send nothing", () => {
        const { onReprice } = renderChart();
        for (const close of [
            () => fireEvent.keyDown(document, { key: "Escape" }),
            () => fireEvent.click(screen.getByTestId("modify-close")),
            () => fireEvent.mouseDown(screen.getByTestId("modify-overlay")),
        ]) {
            dragTag(BUY.clOrdId, yForPrice(999_700));
            fireEvent.keyDown(screen.getByTestId("modify-order"), { key: "Enter" });
            close();
            expect(screen.queryByTestId("modify-order")).toBeNull();
        }
        expect(onReprice).not.toHaveBeenCalled();
    });

    it("warns when the drop crosses the opposite best", () => {
        renderChart({ bestBid: 999_900, bestAsk: 1_000_300 });
        dragTag(BUY.clOrdId, yForPrice(1_000_300));
        expect(screen.getByTestId("modify-marketable")).not.toBeNull();
        fireEvent.keyDown(document, { key: "Escape" });
        dragTag(BUY.clOrdId, yForPrice(1_000_200));
        expect(screen.queryByTestId("modify-marketable")).toBeNull();
    });

    it("a peg line cannot be dragged", () => {
        const { onReprice } = renderChart({ working: [BUY, SELL, PEG] });
        const t = tag(PEG.clOrdId);
        expect(t.getAttribute("class")).not.toContain("--draggable");
        fireEvent.pointerDown(t, { pointerId: 3, button: 0, clientX: 650, clientY: lineY(PEG.clOrdId) });
        fireEvent.pointerMove(svg(), { pointerId: 3, clientX: 650, clientY: yForPrice(999_500) });
        fireEvent.pointerUp(svg(), { pointerId: 3, clientX: 650, clientY: yForPrice(999_500) });
        expect(screen.queryByTestId("price-chart-order-ghost")).toBeNull();
        expect(screen.queryByTestId("modify-order")).toBeNull();
        expect(onReprice).not.toHaveBeenCalled();
    });

    it("an order with a reprice in flight cannot be dragged, and nothing drags without the seam", () => {
        renderChart({ repricing: [BUY.clOrdId] });
        expect(tag(BUY.clOrdId).getAttribute("data-repricing")).toBe("true");
        dragTag(BUY.clOrdId, yForPrice(999_700));
        expect(screen.queryByTestId("modify-order")).toBeNull();
        expect(tag(SELL.clOrdId).getAttribute("class")).toContain("--draggable");
        cleanup();

        stubBox();
        const s = session();
        render(<PriceChart tape={s.tape} bars={s.bars} sessionOpenPx={-1} working={[BUY, SELL]} />);
        expect(tag(BUY.clOrdId).getAttribute("class")).not.toContain("--draggable");
    });

    it("a tag drag never pans the chart, while a plot drag still does", () => {
        renderChart();
        const wicks = (): string[] => screen.getAllByTestId("price-chart-wick").map((w) => w.getAttribute("x1") ?? "");
        // Zoom in first so there is history to pan back into.
        for (let i = 0; i < 6; i++) {
            fireEvent(svg(), new WheelEvent("wheel", { deltaY: -100, clientX: 300, clientY: 120, bubbles: true, cancelable: true }));
        }
        const before = wicks();
        dragTag(BUY.clOrdId, yForPrice(999_700), 200);
        expect(wicks()).toEqual(before);
        expect(svg().getAttribute("data-following")).toBe("true");
        fireEvent.keyDown(document, { key: "Escape" });

        fireEvent.pointerDown(svg(), { pointerId: 4, button: 0, clientX: 300, clientY: 120 });
        fireEvent.pointerMove(svg(), { pointerId: 4, clientX: 500, clientY: 120 });
        fireEvent.pointerUp(svg(), { pointerId: 4, clientX: 500, clientY: 120 });
        expect(svg().getAttribute("data-following")).toBe("false");
    });

    it("hides the crosshair while dragging an order", () => {
        renderChart();
        fireEvent.pointerMove(svg(), { clientX: 300, clientY: 120 });
        expect(screen.queryByTestId("price-chart-cross")).not.toBeNull();
        dragTag(BUY.clOrdId, yForPrice(999_700), 300, false);
        expect(screen.queryByTestId("price-chart-cross")).toBeNull();
    });

    it("a pointer cancel ends the gesture with no popup", () => {
        renderChart();
        dragTag(BUY.clOrdId, yForPrice(999_700), 600, false);
        fireEvent.pointerCancel(svg(), { pointerId: 3 });
        expect(screen.queryByTestId("price-chart-order-ghost")).toBeNull();
        expect(screen.queryByTestId("modify-order")).toBeNull();
    });
});

describe("drag to reprice in the floating window (P18-4)", () => {
    function renderWindow(onReprice = vi.fn(), onClose = vi.fn()) {
        stubBox();
        const s = session();
        render(
            <PriceChartOverlay
                open
                onClose={onClose}
                tape={s.tape}
                bars={s.bars}
                working={[BUY, SELL]}
                bestBid={999_900}
                bestAsk={1_000_600}
                onReprice={onReprice}
                sessionOpenPx={-1}
            />,
        );
        return { onReprice, onClose };
    }

    it("a tag drag does not move the window", () => {
        const { onReprice } = renderWindow();
        const win = screen.getByTestId("price-overlay") as HTMLElement;
        const before = win.getAttribute("style");
        dragTag(BUY.clOrdId, yForPrice(999_700), 100);
        expect(win.getAttribute("style")).toBe(before);
        fireEvent.click(screen.getByTestId("modify-confirm"));
        expect(onReprice).toHaveBeenCalledWith(11, 999_700);
    });

    it("Escape closes Modify order without closing the window", () => {
        const { onClose } = renderWindow();
        dragTag(BUY.clOrdId, yForPrice(999_700));
        fireEvent.keyDown(document, { key: "Escape" });
        expect(screen.queryByTestId("modify-order")).toBeNull();
        expect(onClose).not.toHaveBeenCalled();
        fireEvent.keyDown(document, { key: "Escape" });
        expect(onClose).toHaveBeenCalledTimes(1);
    });
});
