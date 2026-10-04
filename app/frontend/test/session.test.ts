import { describe, expect, it } from "vitest";

import { EMPTY_BOOK, initialState, reducer, SESSION_OPEN_UNSET, TAPE_CAP } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import { cancelOrderFrame, newOrderFrame } from "../src/protocol/encode";
import type { ExecFrame } from "../src/protocol/messages";

function run(state: AppState, ...actions: Action[]): AppState {
    return actions.reduce(reducer, state);
}

function fillExec(orderId: number, over: Partial<ExecFrame> = {}): Action {
    const frame: ExecFrame = {
        type: "EXEC",
        execType: "ORDER_PARTIALLY_FILLED",
        orderId,
        tradeId: orderId,
        price: 15000,
        filledQuantity: 1,
        remainingQuantity: 0,
        aggressorOrderId: orderId,
        passiveOrderId: -1,
        timestamp: 1,
        ...over,
    };
    return { type: "FRAME", frame };
}

function acceptedExec(orderId: number, over: Partial<ExecFrame> = {}): Action {
    const frame: ExecFrame = {
        type: "EXEC",
        execType: "ORDER_ACCEPTED",
        orderId,
        tradeId: -1,
        price: 15000,
        filledQuantity: -1,
        remainingQuantity: 10,
        aggressorOrderId: -1,
        passiveOrderId: -1,
        timestamp: 5,
        ...over,
    };
    return { type: "FRAME", frame };
}

function bookFrame(timestamp: number): Action {
    return {
        type: "FRAME",
        frame: { type: "BOOK", bestBid: 15000, bestAsk: 15025, bids: [[15000, 6]], asks: [[15025, 4]], timestamp },
    };
}

describe("session volume", () => {
    it("accumulates across more fills than TAPE_CAP without folding the capped tape", () => {
        const fills: Action[] = [];
        for (let i = 1; i <= 250; i++) {
            fills.push(fillExec(9000 + i, { tradeId: i, filledQuantity: 1, price: 15000 }));
        }
        const state = run(initialState, ...fills);

        // The regression this design exists to prevent: the tape is capped, so the
        // total can only be right if it was accumulated in reducer state.
        expect(state.sessionVolume).toBe(250);
        expect(state.tape).toHaveLength(TAPE_CAP);

        const tapeSum = state.tape.reduce((n, e) => n + e.quantity, 0);
        expect(tapeSum).toBe(TAPE_CAP);
        expect(state.sessionVolume).toBeGreaterThan(tapeSum);
    });

    it("sums both fill types and counts each trade once (aggressor-only)", () => {
        const state = run(
            initialState,
            fillExec(1, { tradeId: 1, execType: "ORDER_PARTIALLY_FILLED", filledQuantity: 4 }),
            fillExec(1, { tradeId: 2, execType: "ORDER_FILLED", filledQuantity: 6 }),
        );
        expect(state.sessionVolume).toBe(10);
    });

    it("is not moved by a non-fill EXEC", () => {
        const state = run(initialState, acceptedExec(1));
        expect(state.sessionVolume).toBe(0);
    });
});

describe("session open price", () => {
    it("is set by the first fill and never overwritten", () => {
        const first = run(initialState, fillExec(1, { tradeId: 1, price: 15000 }));
        expect(first.sessionOpenCents).toBe(15000);

        const later = run(
            first,
            fillExec(2, { tradeId: 2, price: 15025 }),
            fillExec(3, { tradeId: 3, price: 14980 }),
        );
        expect(later.sessionOpenCents).toBe(15000);
    });

    it("has no session open before the first trade", () => {
        const state = run(initialState, acceptedExec(1));
        expect(state.sessionOpenCents).toBeLessThanOrEqual(0);
    });
});

describe("session high, low and trade count (P13-1)", () => {
    it("starts unset, with a real zero count", () => {
        expect(initialState.sessionHighCents).toBe(SESSION_OPEN_UNSET);
        expect(initialState.sessionLowCents).toBe(SESSION_OPEN_UNSET);
        expect(initialState.sessionTradeCount).toBe(0);
    });

    it("is seeded by the first fill: high, low and open all equal that price", () => {
        const state = run(initialState, fillExec(1, { tradeId: 1, price: 15000 }));

        expect(state.sessionHighCents).toBe(15000);
        expect(state.sessionLowCents).toBe(15000);
        expect(state.sessionOpenCents).toBe(15000);
        expect(state.sessionTradeCount).toBe(1);
    });

    it("raises the high on a higher print and leaves the low alone", () => {
        const state = run(
            initialState,
            fillExec(1, { tradeId: 1, price: 15000 }),
            fillExec(2, { tradeId: 2, price: 15075 }),
        );

        expect(state.sessionHighCents).toBe(15075);
        expect(state.sessionLowCents).toBe(15000);
        expect(state.sessionTradeCount).toBe(2);
    });

    it("lowers the low on a lower print and leaves the high alone", () => {
        const state = run(
            initialState,
            fillExec(1, { tradeId: 1, price: 15000 }),
            fillExec(2, { tradeId: 2, price: 14925 }),
        );

        expect(state.sessionHighCents).toBe(15000);
        expect(state.sessionLowCents).toBe(14925);
        expect(state.sessionTradeCount).toBe(2);
    });

    it("moves neither bound on an in-range print but still counts it", () => {
        const bracketed = run(
            initialState,
            fillExec(1, { tradeId: 1, price: 15075 }),
            fillExec(2, { tradeId: 2, price: 14925 }),
        );
        expect(bracketed.sessionHighCents).toBe(15075);
        expect(bracketed.sessionLowCents).toBe(14925);

        const after = reducer(bracketed, fillExec(3, { tradeId: 3, price: 15000 }));
        expect(after.sessionHighCents).toBe(15075);
        expect(after.sessionLowCents).toBe(14925);
        expect(after.sessionTradeCount).toBe(3);
    });

    it("counts a partial fill once, exactly like a full fill", () => {
        const state = run(
            initialState,
            fillExec(1, { tradeId: 1, execType: "ORDER_PARTIALLY_FILLED", price: 15000, filledQuantity: 4 }),
            fillExec(1, { tradeId: 2, execType: "ORDER_FILLED", price: 15010, filledQuantity: 6 }),
        );

        expect(state.sessionTradeCount).toBe(2);
        expect(state.sessionHighCents).toBe(15010);
        expect(state.sessionLowCents).toBe(15000);
    });

    it("is not moved by a non-fill EXEC of any type", () => {
        // The sentinel must survive untouched: a seeded low of -1 would pin the low
        // at the sentinel for the rest of the session.
        const fresh = run(
            initialState,
            acceptedExec(1),
            acceptedExec(2, { execType: "ORDER_CANCELLED" }),
            acceptedExec(3, { execType: "ORDER_REJECTED" }),
        );
        expect(fresh.sessionHighCents).toBe(SESSION_OPEN_UNSET);
        expect(fresh.sessionLowCents).toBe(SESSION_OPEN_UNSET);
        expect(fresh.sessionTradeCount).toBe(0);

        // And once seeded, a non-fill EXEC still moves none of the three.
        const traded = reducer(fresh, fillExec(4, { tradeId: 1, price: 15000 }));
        const afterAccept = reducer(traded, acceptedExec(5, { price: 99999 }));
        expect(afterAccept.sessionHighCents).toBe(15000);
        expect(afterAccept.sessionLowCents).toBe(15000);
        expect(afterAccept.sessionTradeCount).toBe(1);
    });

    it("accumulates past TAPE_CAP, where a tape fold would lose the extremes", () => {
        // The extremes print FIRST, so they are the two rows the cap evicts. Only a
        // reducer accumulator can still report them.
        const fills: Action[] = [
            fillExec(9001, { tradeId: 1, price: 16000, filledQuantity: 1 }),
            fillExec(9002, { tradeId: 2, price: 14000, filledQuantity: 1 }),
        ];
        for (let i = 3; i <= 250; i++) {
            fills.push(fillExec(9000 + i, { tradeId: i, price: 15000, filledQuantity: 1 }));
        }
        const state = run(initialState, ...fills);

        expect(state.sessionTradeCount).toBe(250);
        expect(state.sessionHighCents).toBe(16000);
        expect(state.sessionLowCents).toBe(14000);

        expect(state.tape).toHaveLength(TAPE_CAP);
        const tapePrices = state.tape.map((e) => e.priceCents);
        expect(Math.max(...tapePrices)).toBe(15000);
        expect(Math.min(...tapePrices)).toBe(15000);
    });
});

describe("client MsgSeqNum", () => {
    it("increments once per SENT, including CANCEL, and adds no row for CANCEL", () => {
        const state = run(
            initialState,
            { type: "SENT", frame: newOrderFrame(1, "BUY", 15000, 10) },
            { type: "SENT", frame: newOrderFrame(2, "SELL", 15025, 5) },
            { type: "SENT", frame: cancelOrderFrame(3, 1) },
        );
        expect(state.msgSeqNum).toBe(3);
        // CANCEL advanced the counter but recorded no order row.
        expect(state.myOrders.map((o) => o.clOrdId)).toEqual([2, 1]);
    });

    it("advances on CANCEL while keeping the order rows by reference", () => {
        const before = run(initialState, { type: "SENT", frame: newOrderFrame(1, "BUY", 15000, 10) });
        const after = reducer(before, { type: "SENT", frame: cancelOrderFrame(2, 1) });
        expect(after.msgSeqNum).toBe(2);
        expect(after.myOrders).toBe(before.myOrders);
        expect(after.myOrders[0].status).toBe("PENDING");
    });

    it("advances on a duplicate clOrdId without inserting a second row", () => {
        const state = run(
            initialState,
            { type: "SENT", frame: newOrderFrame(1, "BUY", 15000, 10) },
            { type: "SENT", frame: newOrderFrame(1, "BUY", 15000, 10) },
        );
        expect(state.msgSeqNum).toBe(2);
        expect(state.myOrders).toHaveLength(1);
    });
});

describe("last frame received", () => {
    it("advances on BOOK and EXEC frames", () => {
        const afterBook = run(initialState, bookFrame(100));
        expect(afterBook.lastFrameNanos).toBe(100);

        const afterExec = reducer(afterBook, fillExec(1, { tradeId: 1, price: 15000, timestamp: 200 }));
        expect(afterExec.lastFrameNanos).toBe(200);

        // A non-fill EXEC is still a received frame and advances the marker.
        const afterAccept = reducer(afterExec, acceptedExec(2, { timestamp: 250 }));
        expect(afterAccept.lastFrameNanos).toBe(250);
    });
});

describe("session state across a disconnect", () => {
    it("clears the book but preserves the aggregates, counter, and last-frame marker", () => {
        const live = run(
            initialState,
            { type: "SENT", frame: newOrderFrame(1, "BUY", 15000, 10) },
            fillExec(1, { tradeId: 1, price: 15000, filledQuantity: 4, timestamp: 300 }),
            bookFrame(350),
        );

        const dropped = reducer(live, { type: "CONNECTION", status: "reconnecting" });
        expect(dropped.book).toBe(EMPTY_BOOK);
        expect(dropped.sessionVolume).toBe(live.sessionVolume);
        expect(dropped.sessionOpenCents).toBe(live.sessionOpenCents);
        expect(dropped.msgSeqNum).toBe(live.msgSeqNum);
        expect(dropped.lastFrameNanos).toBe(live.lastFrameNanos);

        // P13-1: the three new accumulators belong to the browser session, not the
        // socket, so a blip must not reset them out from under a surviving tape.
        expect(dropped.sessionHighCents).toBe(live.sessionHighCents);
        expect(dropped.sessionLowCents).toBe(live.sessionLowCents);
        expect(dropped.sessionTradeCount).toBe(live.sessionTradeCount);
    });
});