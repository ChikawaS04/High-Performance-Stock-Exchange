import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import type { Action, AppState, LocalAction, SendMeta } from "../src/state/reducer";
import type { ClientFrame, ExecFrame, ExecType } from "../src/protocol/messages";
import { BOT_BLOCK } from "../src/participants";
import { REPRICE_TIMEOUT_MS } from "../src/reprice";

// P18-5 end to end in App: drag, Confirm Modify, the CANCEL with reprice meta, then
// exactly one replacement NEW once the cancel is confirmed, under StrictMode. The
// socket is replaced by the real reducer, so every frame the test pushes goes
// through the same state path as a live one.

const ctl = vi.hoisted(() => ({
    seed: null as unknown as AppState,
    sends: [] as { frame: ClientFrame; meta?: SendMeta; ok: boolean }[],
    ok: true,
    dispatch: null as unknown as (a: Action) => void,
}));

vi.mock("../src/state/useOrderBook", async () => {
    const React = await import("react");
    const { reducer } = await import("../src/state/reducer");
    return {
        useOrderBook: () => {
            const [state, dispatch] = React.useReducer(reducer, ctl.seed);
            ctl.dispatch = dispatch;
            const send = React.useCallback((frame: ClientFrame, meta?: SendMeta): boolean => {
                ctl.sends.push({ frame, ...(meta !== undefined ? { meta } : {}), ok: ctl.ok });
                if (!ctl.ok) return false;
                dispatch({ type: "SENT", frame, ...(meta !== undefined ? { meta } : {}) });
                return true;
            }, []);
            const dispatchLocal = React.useCallback((a: LocalAction) => dispatch(a), []);
            return { state, send, dispatchLocal };
        },
    };
});
vi.mock("../src/state/useIgnitionPrice", () => ({ useIgnitionPrice: () => ({ openPx: undefined }) }));

import App from "../src/App";
import { initialState, reducer } from "../src/state/reducer";
import { newOrderFrame } from "../src/protocol/encode";

const S = 1_000_000_000;
const NS = 15 * S;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);
const BUY_ID = 1_700_000_000_001;
const SELL_ID = 1_700_000_000_002;
let ts = T0 + 1000 * S;

function exec(execType: ExecType, orderId: number, over: Partial<ExecFrame> = {}): Action {
    ts += 1_000_000;
    return {
        type: "FRAME",
        frame: {
            type: "EXEC",
            execType,
            orderId,
            tradeId: -1,
            price: -1,
            filledQuantity: -1,
            remainingQuantity: -1,
            aggressorOrderId: -1,
            passiveOrderId: -1,
            timestamp: ts,
            ...over,
        },
    };
}

function seed(): AppState {
    const actions: Action[] = [{ type: "CONNECTION", status: "open" }];
    for (let i = 0; i < 30; i++) {
        actions.push(
            exec("ORDER_PARTIALLY_FILLED", BOT_BLOCK + i + 1, {
                tradeId: i + 1,
                price: i % 2 === 0 ? 999_000 : 1_001_000,
                filledQuantity: 1,
                remainingQuantity: 0,
                aggressorOrderId: BOT_BLOCK + i + 1,
                timestamp: T0 + i * NS + S,
            }),
        );
    }
    for (const [id, side, px] of [
        [BUY_ID, "BUY", 1_000_000],
        [SELL_ID, "SELL", 1_000_400],
    ] as const) {
        actions.push({ type: "SENT", frame: newOrderFrame(id, { side, ordType: "LIMIT", tif: "GTC", pricePx: px, qty: 100, displayQty: 0 }) });
        actions.push(exec("ORDER_ACCEPTED", id, { price: px, remainingQuantity: 100 }));
    }
    return actions.reduce(reducer, initialState);
}

beforeEach(() => {
    ctl.seed = seed();
    ctl.sends = [];
    ctl.ok = true;
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
});
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
    localStorage.clear();
});

function renderApp() {
    render(
        <StrictMode>
            <MemoryRouter initialEntries={["/charts"]}>
                <App />
            </MemoryRouter>
        </StrictMode>,
    );
}

const lineY = (id: number): number =>
    Number(document.querySelector(`[data-order-id="${id}"] [data-testid="price-chart-order"]`)!.getAttribute("y1"));
const tag = (id: number): Element => document.querySelector(`[data-order-id="${id}"] [data-testid="price-chart-order-tag"]`)!;

function dragBuyTo(px: number): void {
    const y0 = lineY(BUY_ID);
    const k = (y0 - lineY(SELL_ID)) / 400;
    const y = y0 - (px - 1_000_000) * k;
    const svg = screen.getByTestId("price-chart");
    fireEvent.pointerDown(tag(BUY_ID), { pointerId: 5, button: 0, clientX: 650, clientY: y0 });
    fireEvent.pointerMove(svg, { pointerId: 5, clientX: 650, clientY: y });
    fireEvent.pointerUp(svg, { pointerId: 5, clientX: 650, clientY: y });
    fireEvent.click(screen.getByTestId("modify-confirm"));
}

const replacements = () => ctl.sends.filter((s) => s.frame.type === "NEW" && s.meta?.repriceOf === BUY_ID);
const toastTitles = (): string[] => screen.queryAllByTestId("order-toast").map((t) => t.querySelector(".order-toast__title")!.textContent ?? "");

describe("drag to reprice in App (P18-5)", () => {
    it("sends the cancel with reprice meta, then exactly one replacement once the cancel is confirmed", () => {
        renderApp();
        dragBuyTo(999_700);
        expect(ctl.sends).toHaveLength(1);
        const cancel = ctl.sends[0];
        expect(cancel.frame).toMatchObject({ type: "CANCEL", origClOrdId: BUY_ID });
        expect(cancel.meta).toEqual({ reprice: { newPx: 999_700 } });
        // In flight: the tag cannot be dragged again, and nothing else is sent yet.
        expect(tag(BUY_ID).getAttribute("data-repricing")).toBe("true");
        expect(replacements()).toHaveLength(0);

        act(() => ctl.dispatch(exec("ORDER_CANCELLED", BUY_ID)));
        const sent = replacements();
        expect(sent).toHaveLength(1);
        expect(sent[0].frame).toMatchObject({ type: "NEW", side: "BUY", ordType: "LIMIT", tif: "GTC", price: 999_700, qty: 100, maxFloor: 0 });
        expect((sent[0].frame as { clOrdId: number }).clOrdId).not.toBe(BUY_ID);
        // A later render, and a repeat of the confirmation, send nothing more.
        act(() => ctl.dispatch(exec("ORDER_CANCELLED", BUY_ID)));
        expect(replacements()).toHaveLength(1);
        expect(toastTitles()).not.toContain("Order cancelled");
    });

    it("a fill before the cancel sends no replacement and raises one Modify not sent toast", () => {
        renderApp();
        dragBuyTo(999_700);
        act(() =>
            ctl.dispatch(
                exec("ORDER_FILLED", BOT_BLOCK + 900, {
                    tradeId: 900,
                    price: 1_000_000,
                    filledQuantity: 100,
                    remainingQuantity: 0,
                    aggressorOrderId: BOT_BLOCK + 900,
                    passiveOrderId: BUY_ID,
                    aggressorSide: "SELL",
                }),
            ),
        );
        act(() => ctl.dispatch(exec("ORDER_REJECTED", BUY_ID)));
        expect(replacements()).toHaveLength(0);
        expect(toastTitles().filter((t) => t === "Modify not sent: order filled")).toHaveLength(1);
        expect(toastTitles()).not.toContain("Modify failed");
    });

    it("a failed replacement write aborts with Modify failed instead of retrying", () => {
        renderApp();
        dragBuyTo(999_700);
        ctl.ok = false;
        act(() => ctl.dispatch(exec("ORDER_CANCELLED", BUY_ID)));
        expect(replacements()).toHaveLength(1);
        expect(replacements()[0].ok).toBe(false);
        expect(toastTitles()).toContain("Modify failed");
        act(() => ctl.dispatch(exec("ORDER_ACCEPTED", SELL_ID, { price: 1_000_400, remainingQuantity: 100 })));
        expect(replacements()).toHaveLength(1);
    });

    it("no answer within the timeout aborts with Modify failed; the timer fires once", () => {
        vi.useFakeTimers();
        renderApp();
        dragBuyTo(999_700);
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS - 1));
        expect(toastTitles()).not.toContain("Modify failed");
        act(() => vi.advanceTimersByTime(1));
        expect(toastTitles().filter((t) => t === "Modify failed")).toHaveLength(1);
        expect(tag(BUY_ID).getAttribute("data-repricing")).toBe("false");
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS * 2));
        expect(toastTitles().filter((t) => t === "Modify failed")).toHaveLength(1);
        // A late cancel after the abort is a real cancel: the normal toast, no replacement.
        act(() => ctl.dispatch(exec("ORDER_CANCELLED", BUY_ID)));
        expect(toastTitles()).toContain("Order cancelled");
        expect(replacements()).toHaveLength(0);
    });
});

describe("arrival stamping in App (P18-8)", () => {
    it("a ticket submit sends the market as the render saw it with the new order", () => {
        renderApp();
        act(() =>
            ctl.dispatch({
                type: "FRAME",
                frame: { type: "BOOK", bestBid: 999_900, bestAsk: 1_000_100, bids: [[999_900, 5]], asks: [[1_000_100, 5]], timestamp: ts + 5 },
            }),
        );
        fireEvent.change(screen.getByTestId("price-input"), { target: { value: "99.99" } });
        fireEvent.change(screen.getByTestId("qty-input"), { target: { value: "10" } });
        fireEvent.click(screen.getByTestId("order-submit"));
        const confirm = screen.queryByTestId("order-confirm-send");
        if (confirm !== null) fireEvent.click(confirm);
        const news = ctl.sends.filter((s) => s.frame.type === "NEW");
        expect(news).toHaveLength(1);
        // Session VWAP of the seeded prints: 15 at 999_000 and 15 at 1_001_000.
        expect(news[0].meta?.arrival).toEqual({ bid: 999_900, ask: 1_000_100, mid: 1_000_000, vwap: 1_000_000, t: ts + 5 });
    });
});
