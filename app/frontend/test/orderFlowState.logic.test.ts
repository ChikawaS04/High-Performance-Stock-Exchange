import { describe, expect, it } from "vitest";

import { newOrderFrame } from "../src/protocol/encode";
import type { ExecFrame, Side } from "../src/protocol/messages";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";

function run(state: AppState, ...actions: Action[]): AppState {
    return actions.reduce(reducer, state);
}

function exec(over: Partial<ExecFrame>): Action {
    const frame: ExecFrame = {
        type: "EXEC",
        execType: "ORDER_PARTIALLY_FILLED",
        orderId: 900,
        tradeId: 1,
        price: 1_500_000,
        filledQuantity: 10,
        remainingQuantity: 5,
        aggressorOrderId: 900,
        passiveOrderId: 901,
        timestamp: 1_750_000_000_000_000_000,
        ...over,
    };
    return { type: "FRAME", frame };
}

const fill = (side: Side | undefined, price: number, qty: number): Action =>
    exec(side === undefined ? { price, filledQuantity: qty } : { aggressorSide: side, price, filledQuantity: qty });

describe("session volume at price (P19-2, D3)", () => {
    it("starts empty with a zero delta", () => {
        expect(initialState.volumeAtPrice.size).toBe(0);
        expect(initialState.sessionDelta).toBe(0);
    });

    it("adds every fill's quantity at its exact price", () => {
        const s = run(
            initialState,
            fill("BUY", 1_500_000, 10),
            fill("SELL", 1_500_100, 4),
            fill("SELL", 1_500_000, 6),
            fill(undefined, 1_500_050, 3),
        );
        expect([...s.volumeAtPrice.entries()].sort((a, b) => a[0] - b[0])).toEqual([
            [1_500_000, 16],
            [1_500_050, 3],
            [1_500_100, 4],
        ]);
    });

    it("replaces the map on a fill and never mutates the previous one", () => {
        const a = run(initialState, fill("BUY", 1_500_000, 10));
        const b = run(a, fill("BUY", 1_500_000, 5));
        expect(b.volumeAtPrice).not.toBe(a.volumeAtPrice);
        expect(a.volumeAtPrice.get(1_500_000)).toBe(10);
        expect(b.volumeAtPrice.get(1_500_000)).toBe(15);
    });

    it("survives a reconnect", () => {
        const filled = run(initialState, fill("BUY", 1_500_000, 10), fill("SELL", 1_500_100, 3));
        const after = run(
            filled,
            { type: "CONNECTION", status: "open" },
            { type: "CONNECTION", status: "reconnecting" },
            { type: "CONNECTION", status: "open" },
        );
        expect(after.volumeAtPrice).toBe(filled.volumeAtPrice);
        expect(after.sessionDelta).toBe(7);
    });

    it("a frame that is not a fill changes nothing", () => {
        const filled = run(initialState, fill("BUY", 1_500_000, 10));
        const after = run(
            filled,
            exec({ execType: "ORDER_ACCEPTED", side: "BUY", tradeId: -1, filledQuantity: -1, remainingQuantity: 10 }),
            exec({ execType: "ORDER_CANCELLED", tradeId: -1, price: -1, filledQuantity: -1 }),
            {
                type: "FRAME",
                frame: { type: "BOOK", bestBid: 1_499_900, bestAsk: 1_500_100, bids: [], asks: [], timestamp: 2 },
            },
        );
        expect(after.volumeAtPrice).toBe(filled.volumeAtPrice);
        expect(after.sessionDelta).toBe(filled.sessionDelta);
    });
});

describe("session delta (P19-2, D5)", () => {
    it("adds buyer-initiated and subtracts seller-initiated quantity", () => {
        const s = run(initialState, fill("BUY", 1_500_000, 10), fill("SELL", 1_500_000, 25), fill("BUY", 1_500_000, 5));
        expect(s.sessionDelta).toBe(-10);
    });

    it("ignores a foreign fill with no side, which still counts in volume at price", () => {
        const s = run(initialState, fill("BUY", 1_500_000, 10), fill(undefined, 1_500_000, 7));
        expect(s.sessionDelta).toBe(10);
        expect(s.volumeAtPrice.get(1_500_000)).toBe(17);
    });

    it("falls back to our own order's side when the frame has none", () => {
        const sent: Action = {
            type: "SENT",
            frame: newOrderFrame(900, { side: "SELL", ordType: "LIMIT", tif: "GTC", pricePx: 1_500_000, qty: 15, displayQty: 0 }),
        };
        const s = run(initialState, sent, fill(undefined, 1_500_000, 10));
        expect(s.sessionDelta).toBe(-10);
    });

    it("each bar's cvd equals the session delta after its last fill", () => {
        const s = run(initialState, fill("BUY", 1_500_000, 10), fill("SELL", 1_500_000, 4));
        expect(s.bars["5s"].open!.cvd).toBe(s.sessionDelta);
        expect(s.bars["5m"].open!.cvd).toBe(6);
    });
});
