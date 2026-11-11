import { describe, expect, it } from "vitest";

import { TOAST_CAP, noticesForExec, toastQueue } from "../src/notices";
import type { OrderNotice } from "../src/notices";
import { NOTICE_CAP, initialState, reducer } from "../src/state/reducer";
import type { AppState } from "../src/state/reducer";
import { parseServerFrame } from "../src/protocol/messages";
import type { ExecFrame, ExecType, NewOrderFrame } from "../src/protocol/messages";

function sent(clOrdId: number, over: Partial<NewOrderFrame> = {}): NewOrderFrame {
    return {
        type: "NEW",
        clOrdId,
        side: "BUY",
        ordType: "LIMIT",
        tif: "GTC",
        price: 14_466_900,
        qty: 100,
        maxFloor: 0,
        symbol: "ASML",
        ...over,
    };
}

function exec(execType: ExecType, over: Partial<ExecFrame> = {}): ExecFrame {
    return {
        type: "EXEC",
        execType,
        orderId: 1,
        tradeId: -1,
        price: -1,
        filledQuantity: -1,
        remainingQuantity: -1,
        aggressorOrderId: -1,
        passiveOrderId: -1,
        timestamp: 1,
        ...over,
    };
}

function apply(state: AppState, frame: ExecFrame): AppState {
    return reducer(state, { type: "FRAME", frame });
}

function withOrder(frame: NewOrderFrame = sent(1)): AppState {
    return reducer(initialState, { type: "SENT", frame });
}

function kinds(state: AppState): string[] {
    return state.orderNotices.map((n) => n.kind);
}

describe("noticesForExec (D4): one notice per own-order event", () => {
    it("raises 'Order submitted' on the venue's accept, with side, qty, type and price", () => {
        const s = apply(withOrder(), exec("ORDER_ACCEPTED", { remainingQuantity: 100 }));
        expect(s.orderNotices).toHaveLength(1);
        const n = s.orderNotices[0];
        expect(n.kind).toBe("submitted");
        expect(n.title).toBe("Order submitted");
        expect(n.side).toBe("BUY");
        expect(n.detail).toBe("100 LIMIT @ 1446.69");
        expect(n.clOrdId).toBe(1);
        expect(n.id).toBe(1);
    });

    it("names a midpoint peg without a price", () => {
        const s = apply(
            withOrder(sent(1, { ordType: "PEG_MID", price: -1 })),
            exec("ORDER_ACCEPTED", { remainingQuantity: 100 }),
        );
        expect(s.orderNotices[0].detail).toBe("100 MIDPOINT PEG");
    });

    it("raises partial and full fills with qty @ price (and the remaining on a partial)", () => {
        let s = apply(withOrder(), exec("ORDER_ACCEPTED", { remainingQuantity: 100 }));
        s = apply(
            s,
            exec("ORDER_PARTIALLY_FILLED", {
                tradeId: 7,
                price: 14_466_800,
                filledQuantity: 40,
                remainingQuantity: 60,
                aggressorOrderId: 1,
                passiveOrderId: 900,
            }),
        );
        s = apply(
            s,
            exec("ORDER_FILLED", {
                tradeId: 8,
                price: 14_466_900,
                filledQuantity: 60,
                remainingQuantity: 0,
                aggressorOrderId: 1,
                passiveOrderId: 901,
            }),
        );
        expect(kinds(s)).toEqual(["submitted", "partial", "filled"]);
        expect(s.orderNotices[1].title).toBe("Partially filled");
        expect(s.orderNotices[1].detail).toBe("40 @ 1446.68, 60 remaining");
        expect(s.orderNotices[2].title).toBe("Order filled");
        expect(s.orderNotices[2].detail).toBe("60 @ 1446.69");
    });

    it("raises cancelled (remaining qty, price), rejected and expired", () => {
        let s = apply(withOrder(), exec("ORDER_ACCEPTED", { remainingQuantity: 100 }));
        s = apply(s, exec("ORDER_CANCELLED"));
        expect(s.orderNotices[1].kind).toBe("cancelled");
        expect(s.orderNotices[1].detail).toBe("100 @ 1446.69");

        const r = apply(withOrder(sent(2)), exec("ORDER_REJECTED", { orderId: 2 }));
        expect(kinds(r)).toEqual(["rejected"]);
        expect(r.orderNotices[0].detail).toBe("100 @ 1446.69");

        const e = apply(
            withOrder(sent(3, { tif: "IOC" })),
            exec("ORDER_EXPIRED", { orderId: 3, remainingQuantity: 30 }),
        );
        expect(kinds(e)).toEqual(["expired"]);
        expect(e.orderNotices[0].title).toBe("Order expired");
        expect(e.orderNotices[0].detail).toBe("30 unfilled");
    });

    it("never shows 'submitted' before a reject", () => {
        const s = apply(withOrder(), exec("ORDER_REJECTED"));
        expect(kinds(s)).toEqual(["rejected"]);
    });

    it("raises no second 'submitted' for the trailing accept after a partial fill", () => {
        let s = apply(withOrder(), exec("ORDER_ACCEPTED", { remainingQuantity: 100 }));
        s = apply(
            s,
            exec("ORDER_PARTIALLY_FILLED", {
                tradeId: 7,
                price: 14_466_900,
                filledQuantity: 40,
                remainingQuantity: 60,
                aggressorOrderId: 1,
                passiveOrderId: 900,
            }),
        );
        s = apply(s, exec("ORDER_ACCEPTED", { remainingQuantity: 60 }));
        expect(kinds(s)).toEqual(["submitted", "partial"]);
    });

    it("raises nothing for another participant's order", () => {
        const s = apply(withOrder(), exec("ORDER_ACCEPTED", { orderId: 555, remainingQuantity: 10 }));
        const f = apply(
            s,
            exec("ORDER_FILLED", {
                orderId: 555,
                tradeId: 3,
                price: 14_466_900,
                filledQuantity: 10,
                remainingQuantity: 0,
                aggressorOrderId: 555,
                passiveOrderId: 556,
            }),
        );
        expect(f.orderNotices).toHaveLength(0);
    });

    it("raises a notice for a passive fill of an own resting order", () => {
        let s = apply(withOrder(sent(1, { side: "SELL" })), exec("ORDER_ACCEPTED", { remainingQuantity: 100 }));
        // A bot's buy hits our resting sell: the EXEC names the bot as aggressor.
        s = apply(
            s,
            exec("ORDER_PARTIALLY_FILLED", {
                orderId: 700,
                tradeId: 9,
                price: 14_466_900,
                filledQuantity: 30,
                remainingQuantity: 5,
                aggressorOrderId: 700,
                passiveOrderId: 1,
            }),
        );
        expect(kinds(s)).toEqual(["submitted", "partial"]);
        expect(s.orderNotices[1].side).toBe("SELL");
        expect(s.orderNotices[1].detail).toBe("30 @ 1446.69, 70 remaining");

        s = apply(
            s,
            exec("ORDER_FILLED", {
                orderId: 701,
                tradeId: 10,
                price: 14_466_900,
                filledQuantity: 70,
                remainingQuantity: 0,
                aggressorOrderId: 701,
                passiveOrderId: 1,
            }),
        );
        expect(s.orderNotices[2].kind).toBe("filled");
        expect(s.orderNotices[2].detail).toBe("70 @ 1446.69");
    });

    it("raises nothing for a frame on a terminal order", () => {
        let s = apply(withOrder(), exec("ORDER_CANCELLED"));
        s = apply(s, exec("ORDER_CANCELLED"));
        expect(kinds(s)).toEqual(["cancelled"]);
    });

    it("is pure: no own rows, no notices", () => {
        expect(noticesForExec([], [], exec("ORDER_ACCEPTED"))).toEqual([]);
    });
});

describe("reducer notice dedupe and bounds (D4)", () => {
    it("raises a repeated EXEC seq once", () => {
        const frame = exec("ORDER_ACCEPTED", { remainingQuantity: 100, seq: 41 });
        let s = apply(withOrder(), frame);
        s = apply(s, frame);
        expect(s.orderNotices).toHaveLength(1);
    });

    it("parses the venue seq from the wire, and still accepts a frame without one", () => {
        const base = {
            type: "EXEC",
            execType: "ORDER_ACCEPTED",
            orderId: 1,
            tradeId: -1,
            price: 14_466_900,
            filledQuantity: 0,
            remainingQuantity: 100,
            aggressorOrderId: -1,
            passiveOrderId: -1,
            timestamp: 5,
        };
        const withSeq = parseServerFrame(JSON.stringify({ ...base, seq: 12 })) as ExecFrame;
        expect(withSeq.seq).toBe(12);
        const without = parseServerFrame(JSON.stringify(base)) as ExecFrame;
        expect(without).not.toBeNull();
        expect("seq" in without).toBe(false);
    });

    it("raises nothing on a BOOK snapshot or a FIX echo", () => {
        let s = withOrder();
        s = reducer(s, {
            type: "FRAME",
            frame: { type: "BOOK", bestBid: 1, bestAsk: 2, bids: [], asks: [], timestamp: 3 },
        });
        s = reducer(s, {
            type: "FRAME",
            frame: { type: "FIX", direction: "INBOUND", raw: "8=FIX", seqNum: 1, timestamp: 4 },
        });
        expect(s.orderNotices).toHaveLength(0);
        expect(s.noticeSeq).toBe(0);
    });

    it("caps the kept notices at NOTICE_CAP with increasing ids", () => {
        let s = initialState;
        for (let id = 1; id <= NOTICE_CAP + 5; id++) {
            s = reducer(s, { type: "SENT", frame: sent(id) });
            s = apply(s, exec("ORDER_ACCEPTED", { orderId: id, remainingQuantity: 100 }));
        }
        expect(s.orderNotices).toHaveLength(NOTICE_CAP);
        expect(s.noticeSeq).toBe(NOTICE_CAP + 5);
        expect(s.orderNotices[s.orderNotices.length - 1].id).toBe(NOTICE_CAP + 5);
    });
});

function notice(id: number): OrderNotice {
    return { id, kind: "filled", clOrdId: id, title: "Order filled", side: "BUY", detail: "1 @ 1.00" };
}

describe("toastQueue (D4)", () => {
    it("adds newest at the end", () => {
        const q = toastQueue(toastQueue([], { type: "add", notice: notice(1) }), { type: "add", notice: notice(2) });
        expect(q.map((t) => t.id)).toEqual([1, 2]);
    });

    it("caps at TOAST_CAP, dropping the oldest", () => {
        let q: readonly OrderNotice[] = [];
        for (let i = 1; i <= TOAST_CAP + 1; i++) q = toastQueue(q, { type: "add", notice: notice(i) });
        expect(TOAST_CAP).toBe(4);
        expect(q.map((t) => t.id)).toEqual([2, 3, 4, 5]);
    });

    it("ignores a repeated id", () => {
        const q = toastQueue([notice(1)], { type: "add", notice: notice(1) });
        expect(q).toHaveLength(1);
    });

    it("dismisses and expires a single toast", () => {
        const q: readonly OrderNotice[] = [notice(1), notice(2), notice(3)];
        expect(toastQueue(q, { type: "dismiss", id: 2 }).map((t) => t.id)).toEqual([1, 3]);
        expect(toastQueue(q, { type: "expire", id: 1 }).map((t) => t.id)).toEqual([2, 3]);
        expect(toastQueue(q, { type: "expire", id: 99 })).toBe(q);
    });
});
