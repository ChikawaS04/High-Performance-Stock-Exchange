import { describe, expect, it } from "vitest";

import { newOrderFrame, cancelOrderFrame } from "../src/protocol/encode";
import type {
    BookFrame,
    ExecFrame,
    ExecType,
    ServerFrame,
    Side,
    TimeInForce,
} from "../src/protocol/messages";
import {
    initialState,
    isCancellable,
    reducer,
    TAPE_CAP,
} from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";

// --- fixtures ---------------------------------------------------------------

function book(
    bestBid: number,
    bestAsk: number,
    bids: readonly (readonly [number, number])[],
    asks: readonly (readonly [number, number])[],
    timestamp = 1,
): BookFrame {
    return { type: "BOOK", bestBid, bestAsk, bids, asks, timestamp };
}

// P14-6: newOrderFrame now takes an OrderIntent. This wrapper keeps the old
// positional call shape the fixtures below use, defaulting to a GTC LIMIT, so a
// test that cares about time in force passes it as the last argument.
function nf(clOrdId: number, side: Side, pricePx: number, qty: number, tif: TimeInForce = "GTC") {
    return newOrderFrame(clOrdId, { side, ordType: "LIMIT", tif, pricePx, qty, displayQty: 0 });
}

function exec(execType: ExecType, orderId: number, overrides: Partial<ExecFrame> = {}): ExecFrame {
    return {
        type: "EXEC",
        execType,
        orderId,
        tradeId: -1,
        price: -1,
        filledQuantity: -1,
        remainingQuantity: -1,
        aggressorOrderId: -1,
        passiveOrderId: -1,
        timestamp: 1,
        ...overrides,
    };
}

function fill(
    execType: "ORDER_FILLED" | "ORDER_PARTIALLY_FILLED",
    aggressor: number,
    passive: number,
    opts: { tradeId: number; price: number; filled: number; remaining: number; timestamp?: number },
): ExecFrame {
    return exec(execType, aggressor, {
        tradeId: opts.tradeId,
        price: opts.price,
        filledQuantity: opts.filled,
        remainingQuantity: opts.remaining,
        aggressorOrderId: aggressor,
        passiveOrderId: passive,
        timestamp: opts.timestamp ?? 1,
    });
}

function run(state: AppState, ...actions: readonly Action[]): AppState {
    return actions.reduce(reducer, state);
}

const frame = (f: ServerFrame): Action => ({ type: "FRAME", frame: f });
const sent = (f: ReturnType<typeof newOrderFrame> | ReturnType<typeof cancelOrderFrame>): Action => ({
    type: "SENT",
    frame: f,
});
const open: Action = { type: "CONNECTION", status: "open" };

// --- book -------------------------------------------------------------------

describe("BOOK is authoritative and replaces wholesale", () => {
    it("replaces the previous book rather than merging", () => {
        const first = run(initialState, frame(book(1500000, 1502500, [[1500000, 10]], [[1502500, 7]])));
        const second = run(first, frame(book(1490000, -1, [[1490000, 3]], [], 2)));

        expect(second.book.bids).toEqual([[1490000, 3]]);
        expect(second.book.asks).toEqual([]);
        expect(second.book.bestBid).toBe(1490000);
        expect(second.book.bestAsk).toBe(-1);
        expect(second.book.timestamp).toBe(2);
    });

    it("accepts an empty book", () => {
        const state = run(
            initialState,
            frame(book(1500000, -1, [[1500000, 10]], [])),
            frame(book(-1, -1, [], [], 9)),
        );
        expect(state.book).toEqual({ bestBid: -1, bestAsk: -1, bids: [], asks: [], timestamp: 9 });
    });
});

describe("EXEC never touches the book", () => {
    it("leaves book state untouched across every exec type", () => {
        const withBook = run(initialState, frame(book(1500000, 1502500, [[1500000, 10]], [[1502500, 7]])));
        const after = run(
            withBook,
            frame(exec("ORDER_ACCEPTED", 1, { price: 1500000, remainingQuantity: 10 })),
            frame(fill("ORDER_FILLED", 2, 1, { tradeId: 1, price: 1500000, filled: 4, remaining: 0 })),
            frame(exec("ORDER_CANCELLED", 1)),
            frame(exec("ORDER_REJECTED", 3)),
            frame(exec("ORDER_EXPIRED", 4, { remainingQuantity: 5 })),
        );
        expect(after.book).toBe(withBook.book);
    });
});

// --- tape -------------------------------------------------------------------

describe("trade tape", () => {
    it("appends only fills, newest first", () => {
        const state = run(
            initialState,
            frame(exec("ORDER_ACCEPTED", 1, { price: 1500000, remainingQuantity: 10 })),
            frame(fill("ORDER_FILLED", 2, 1, { tradeId: 1, price: 1500000, filled: 4, remaining: 0 })),
            frame(exec("ORDER_CANCELLED", 1)),
            frame(
                fill("ORDER_PARTIALLY_FILLED", 3, 1, { tradeId: 2, price: 1490000, filled: 2, remaining: 5 }),
            ),
            frame(exec("ORDER_REJECTED", 4)),
        );

        expect(state.tape).toHaveLength(2);
        expect(state.tape[0].tradeId).toBe(2);
        expect(state.tape[0].pricePx).toBe(1490000);
        expect(state.tape[0].quantity).toBe(2);
        expect(state.tape[1].tradeId).toBe(1);
    });

    it("caps at TAPE_CAP, discarding the oldest", () => {
        let state = initialState;
        for (let i = 1; i <= TAPE_CAP + 25; i++) {
            state = reducer(
                state,
                frame(fill("ORDER_FILLED", 1000 + i, 1, { tradeId: i, price: 1500000, filled: 1, remaining: 0 })),
            );
        }
        expect(state.tape).toHaveLength(TAPE_CAP);
        expect(state.tape[0].tradeId).toBe(TAPE_CAP + 25);
        expect(state.tape[TAPE_CAP - 1].tradeId).toBe(26);
    });

    it("flags a trade as mine when either side is one of my orders", () => {
        const mine = run(initialState, sent(nf(1, "BUY", 1500000, 10)));

        const asAggressor = reducer(
            mine,
            frame(fill("ORDER_FILLED", 1, 99, { tradeId: 1, price: 1500000, filled: 10, remaining: 0 })),
        );
        expect(asAggressor.tape[0].mine).toBe(true);

        const asPassive = reducer(
            mine,
            frame(fill("ORDER_FILLED", 99, 1, { tradeId: 2, price: 1500000, filled: 4, remaining: 0 })),
        );
        expect(asPassive.tape[0].mine).toBe(true);

        const foreign = reducer(
            mine,
            frame(fill("ORDER_FILLED", 98, 99, { tradeId: 3, price: 1500000, filled: 1, remaining: 0 })),
        );
        expect(foreign.tape[0].mine).toBe(false);
    });

    it("never flags a -1 NA counterparty id as mine", () => {
        const state = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 10)),
            frame(
                exec("ORDER_FILLED", 5, {
                    tradeId: 1,
                    price: 1500000,
                    filledQuantity: 1,
                    remainingQuantity: 0,
                    aggressorOrderId: -1,
                    passiveOrderId: -1,
                }),
            ),
        );
        expect(state.tape[0].mine).toBe(false);
    });
});

// --- myOrders ---------------------------------------------------------------

describe("myOrders registration at send time", () => {
    it("registers a NEW order as PENDING with side, price and order attributes from the send", () => {
        const state = run(initialState, sent(nf(1, "SELL", 1502500, 10)));
        expect(state.myOrders).toHaveLength(1);
        expect(state.myOrders[0]).toEqual({
            clOrdId: 1,
            side: "SELL",
            pricePx: 1502500,
            originalQty: 10,
            remainingQty: 10,
            status: "PENDING",
            ordType: "LIMIT",
            tif: "GTC",
            displayQty: 0,
        });
    });

    it("records nothing for a CANCEL send — EXEC stays the authority", () => {
        const before = run(initialState, sent(nf(1, "BUY", 1500000, 10)), open);
        const after = reducer(before, sent(cancelOrderFrame(2, 1)));
        expect(after.myOrders).toBe(before.myOrders);
        expect(after.myOrders[0].status).toBe("PENDING");
    });

    it("keeps newest first and ignores a duplicate clOrdId", () => {
        const state = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 10)),
            sent(nf(2, "SELL", 1502500, 5)),
            sent(nf(2, "SELL", 1502500, 5)),
        );
        expect(state.myOrders.map((o) => o.clOrdId)).toEqual([2, 1]);
    });
});

describe("myOrders lifecycle", () => {
    it("PENDING -> OPEN on ORDER_ACCEPTED", () => {
        const state = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 10)),
            frame(exec("ORDER_ACCEPTED", 1, { price: 1500000, remainingQuantity: 10 })),
        );
        expect(state.myOrders[0].status).toBe("OPEN");
        expect(state.myOrders[0].remainingQty).toBe(10);
        expect(isCancellable(state.myOrders[0].status)).toBe(true);
    });

    it("ACCEPTED -> PARTIALLY_FILLED -> FILLED", () => {
        const state = run(
            initialState,
            sent(nf(2, "BUY", 1500000, 10)),
            frame(exec("ORDER_ACCEPTED", 2, { price: 1500000, remainingQuantity: 10 })),
            frame(fill("ORDER_PARTIALLY_FILLED", 2, 1, { tradeId: 1, price: 1500000, filled: 4, remaining: 6 })),
            frame(fill("ORDER_FILLED", 2, 1, { tradeId: 2, price: 1500000, filled: 6, remaining: 0 })),
        );
        expect(state.myOrders[0].status).toBe("FILLED");
        expect(state.myOrders[0].remainingQty).toBe(0);
        expect(isCancellable(state.myOrders[0].status)).toBe(false);
        expect(state.tape).toHaveLength(2);
    });

    it("a trailing ACCEPTED after a partial updates remaining but keeps the PARTIALLY_FILLED label", () => {
        const state = run(
            initialState,
            sent(nf(2, "BUY", 1500000, 80)),
            frame(fill("ORDER_PARTIALLY_FILLED", 2, 1, { tradeId: 1, price: 1500000, filled: 50, remaining: 30 })),
            frame(exec("ORDER_ACCEPTED", 2, { price: 1500000, remainingQuantity: 30 })),
        );
        expect(state.myOrders[0].status).toBe("PARTIALLY_FILLED");
        expect(state.myOrders[0].remainingQty).toBe(30);
        expect(isCancellable(state.myOrders[0].status)).toBe(true);
    });

    it("OPEN -> CANCELLED, keyed on the cancelled order's id, not the request's", () => {
        const state = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 10)),
            frame(exec("ORDER_ACCEPTED", 1, { price: 1500000, remainingQuantity: 10 })),
            sent(cancelOrderFrame(3, 1)),
            // ORDER_CANCELLED carries orderId == OrigClOrdID (1), never the request's clOrdId (3).
            frame(exec("ORDER_CANCELLED", 1)),
        );
        expect(state.myOrders).toHaveLength(1);
        expect(state.myOrders[0].clOrdId).toBe(1);
        expect(state.myOrders[0].status).toBe("CANCELLED");
    });

    it("marks a known order REJECTED and ignores a rejection for an unknown id", () => {
        const known = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 10)),
            frame(exec("ORDER_REJECTED", 1)),
        );
        expect(known.myOrders[0].status).toBe("REJECTED");

        const unknown = reducer(known, frame(exec("ORDER_REJECTED", 12345)));
        expect(unknown.myOrders).toHaveLength(1);
        expect(unknown.myOrders).toBe(known.myOrders);
    });

    it("never resurrects a terminal row", () => {
        const state = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 10)),
            frame(exec("ORDER_CANCELLED", 1)),
            frame(exec("ORDER_ACCEPTED", 1, { price: 1500000, remainingQuantity: 10 })),
        );
        expect(state.myOrders[0].status).toBe("CANCELLED");
    });
});

describe("IOC / FOK expiry (P14-6)", () => {
    it("captures ordType, tif and displayQty at send time", () => {
        const state = run(initialState, sent(nf(1, "BUY", 1500000, 10, "IOC")));
        expect(state.myOrders[0]).toEqual({
            clOrdId: 1,
            side: "BUY",
            pricePx: 1500000,
            originalQty: 10,
            remainingQty: 10,
            status: "PENDING",
            ordType: "LIMIT",
            tif: "IOC",
            displayQty: 0,
        });
    });

    it("an IOC partial fill then ORDER_EXPIRED ends EXPIRED with the unfilled remainder, terminal and non-cancellable", () => {
        const state = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 100, "IOC")),
            frame(fill("ORDER_PARTIALLY_FILLED", 1, 9, { tradeId: 1, price: 1500000, filled: 30, remaining: 70 })),
            frame(exec("ORDER_EXPIRED", 1, { remainingQuantity: 70 })),
        );
        const row = state.myOrders[0];
        expect(row.status).toBe("EXPIRED");
        expect(row.remainingQty).toBe(70);
        expect(isCancellable(row.status)).toBe(false);
        // One fill printed; the expiry is not a trade and adds no tape row.
        expect(state.tape).toHaveLength(1);
    });

    it("expires a never-filled order in full, carrying its time in force", () => {
        const state = run(
            initialState,
            sent(nf(1, "SELL", 1502500, 25, "FOK")),
            frame(exec("ORDER_EXPIRED", 1, { remainingQuantity: 25 })),
        );
        expect(state.myOrders[0].status).toBe("EXPIRED");
        expect(state.myOrders[0].remainingQty).toBe(25);
        expect(state.myOrders[0].tif).toBe("FOK");
    });

    it("ignores a stray EXEC after an order has expired (terminal guard)", () => {
        const expired = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 100, "IOC")),
            frame(exec("ORDER_EXPIRED", 1, { remainingQuantity: 100 })),
        );
        expect(expired.myOrders[0].status).toBe("EXPIRED");

        const after = reducer(
            expired,
            frame(exec("ORDER_ACCEPTED", 1, { price: 1500000, remainingQuantity: 100 })),
        );
        expect(after.myOrders[0].status).toBe("EXPIRED");
        expect(after.myOrders[0].remainingQty).toBe(100);
        // The terminal row is untouched, so the slice keeps its reference.
        expect(after.myOrders).toBe(expired.myOrders);
    });

    it("moves neither the tape nor the session accumulators, but is a received frame", () => {
        const state = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 10, "IOC")),
            frame(exec("ORDER_EXPIRED", 1, { remainingQuantity: 10, timestamp: 42 })),
        );
        expect(state.tape).toHaveLength(0);
        expect(state.sessionVolume).toBe(0);
        expect(state.sessionTradeCount).toBe(0);
        expect(state.lastFrameNanos).toBe(42);
    });
});

describe("passive fills decrement the resting row locally (P7-8)", () => {
    it("decrements my resting order locally when someone else's aggressor hits it", () => {
        const resting = run(
            initialState,
            sent(nf(1, "BUY", 1500000, 10)),
            frame(exec("ORDER_ACCEPTED", 1, { price: 1500000, remainingQuantity: 10 })),
        );

        // The engine fires one onFill naming the aggressor (99); order 1 is only the
        // passive side and receives no EXEC of its own. The frame's remaining is the
        // AGGRESSOR's; we decrement our resting order locally by filledQuantity (4).
        const after = reducer(
            resting,
            frame(fill("ORDER_FILLED", 99, 1, { tradeId: 1, price: 1500000, filled: 4, remaining: 0 })),
        );

        expect(after.myOrders[0].status).toBe("PARTIALLY_FILLED");
        expect(after.myOrders[0].remainingQty).toBe(6);
        // The trade still reaches the tape, flagged as mine.
        expect(after.tape).toHaveLength(1);
        expect(after.tape[0].mine).toBe(true);
    });

    it("ignores a fill between two foreign orders entirely, except for the tape", () => {
        const mine = run(initialState, sent(nf(1, "BUY", 1500000, 10)));
        const after = reducer(
            mine,
            frame(fill("ORDER_FILLED", 98, 99, { tradeId: 1, price: 1500000, filled: 4, remaining: 0 })),
        );
        expect(after.myOrders).toBe(mine.myOrders);
        expect(after.tape).toHaveLength(1);
    });
});

// --- connection -------------------------------------------------------------

describe("connection transitions", () => {
    it("retains the book on drop (stale, not blank) along with tape and myOrders", () => {
        const live = run(
            initialState,
            open,
            sent(nf(1, "BUY", 1500000, 10)),
            frame(exec("ORDER_ACCEPTED", 1, { price: 1500000, remainingQuantity: 10 })),
            frame(fill("ORDER_FILLED", 99, 1, { tradeId: 1, price: 1500000, filled: 4, remaining: 0 })),
            frame(book(1500000, -1, [[1500000, 6]], [])),
        );
        expect(live.book.bids).toHaveLength(1);

        const dropped = reducer(live, { type: "CONNECTION", status: "reconnecting" });
        // The last-known book is kept (same reference, untouched) and marked stale by the
        // connection status; it is not replaced until the next BOOK frame. tape and myOrders
        // likewise survive the blip.
        expect(dropped.book).toBe(live.book);
        expect(dropped.connection).toBe("reconnecting");
        expect(dropped.tape).toBe(live.tape);
        expect(dropped.myOrders).toBe(live.myOrders);
    });

    it("is a no-op when the status is unchanged", () => {
        const live = run(initialState, open, frame(book(1500000, -1, [[1500000, 6]], [])));
        expect(reducer(live, open)).toBe(live);
    });

    it("keeps the last-known book across reconnect until the next BOOK frame replaces it", () => {
        const withBook = run(
            initialState,
            open,
            frame(book(1500000, -1, [[1500000, 6]], [])),
        );
        const state = run(withBook, { type: "CONNECTION", status: "reconnecting" }, open);
        // The book now survives a drop (rendered stale while disconnected) and is replaced
        // wholesale only by the next BOOK frame — the snapshot a client is sent on connect, or
        // the next order flow.
        expect(state.book).toBe(withBook.book);
        expect(state.connection).toBe("open");

        const refreshed = run(state, frame(book(1490000, 1495000, [[1490000, 2]], [[1495000, 3]])));
        expect(refreshed.book.bids).toEqual([[1490000, 2]]);
        expect(refreshed.book.bestAsk).toBe(1495000);
    });
});
