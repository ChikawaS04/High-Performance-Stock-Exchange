import { describe, expect, it } from "vitest";

import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState, MyOrder, SendMeta } from "../src/state/reducer";
import { beginReprice, replacementIntent, stepReprices, REPRICE_TIMEOUT_MS } from "../src/reprice";
import { cancelOrderFrame, newOrderFrame } from "../src/protocol/encode";
import type { ExecFrame, ExecType, Side } from "../src/protocol/messages";
import { BOT_BLOCK } from "../src/participants";
import { NOTICE_TITLES } from "../src/notices";

// P18-5: the reprice state machine, driven through the reducer by EXEC frames and
// the two local actions, on every path the guide and the greenlight amendments name.

const ORIG = 1_763_000_000_001;
const MAKER = BOT_BLOCK + 3;
let nextId = 1_763_000_100_000;
let ts = 1_763_000_000_000_000_000;

function run(state: AppState, ...actions: Action[]): AppState {
    return actions.reduce(reducer, state);
}

function sentNew(id: number, side: Side, px: number, qty: number, opts: { displayQty?: number; meta?: SendMeta } = {}): Action {
    return {
        type: "SENT",
        frame: newOrderFrame(id, { side, ordType: "LIMIT", tif: "GTC", pricePx: px, qty, displayQty: opts.displayQty ?? 0 }),
        ...(opts.meta !== undefined ? { meta: opts.meta } : {}),
    };
}
function sentCancel(orig: number, meta?: SendMeta): Action {
    nextId += 1;
    return { type: "SENT", frame: cancelOrderFrame(nextId, orig), ...(meta !== undefined ? { meta } : {}) };
}
function exec(execType: ExecType, orderId: number, over: Partial<ExecFrame> = {}): Action {
    ts += 1_000;
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
let tradeId = 0;
/** A bot aggressor hits our resting order for `qty`. */
function hit(pas: number, px: number, qty: number): Action {
    tradeId += 1;
    return exec("ORDER_FILLED", MAKER + tradeId, {
        tradeId,
        price: px,
        filledQuantity: qty,
        remainingQuantity: 0,
        aggressorOrderId: MAKER + tradeId,
        passiveOrderId: pas,
        aggressorSide: "SELL",
    });
}

/** ORIG rests: BUY 100 @ 1_000_000, accepted. */
function resting(qty = 100, displayQty = 0): AppState {
    return run(
        initialState,
        sentNew(ORIG, "BUY", 1_000_000, qty, { displayQty }),
        exec("ORDER_ACCEPTED", ORIG, { price: 1_000_000, remainingQuantity: qty }),
    );
}

const startReprice = (newPx = 999_700): Action => sentCancel(ORIG, { reprice: { newPx } });
const kinds = (s: AppState): string[] => s.orderNotices.map((n) => n.kind);
const row = (s: AppState, id = ORIG): MyOrder => s.myOrders.find((o) => o.clOrdId === id)!;

describe("reprice: the normal path", () => {
    it("cancelSent on the cancel's write, ready with the remaining at ORDER_CANCELLED, removed on the replacement's write", () => {
        const s0 = resting();
        const toastsBefore = s0.orderNotices.length;
        const s1 = reducer(s0, startReprice());
        expect(s1.reprices).toEqual([{ origClOrdId: ORIG, side: "BUY", oldPx: 1_000_000, newPx: 999_700, qty: 100, status: "cancelSent" }]);
        const s2 = reducer(s1, exec("ORDER_CANCELLED", ORIG));
        expect(s2.reprices).toEqual([{ ...s1.reprices[0], status: "ready", qty: 100 }]);
        // The cancel is an internal step: no "Order cancelled" toast.
        expect(s2.orderNotices.length).toBe(toastsBefore);
        const s3 = reducer(s2, sentNew(nextId + 50, "BUY", 999_700, 100, { meta: { repriceOf: ORIG } }));
        expect(s3.reprices).toEqual([]);
        expect(row(s3, nextId + 50).status).toBe("PENDING");
    });

    it("a ready entry absorbs later frames for the original", () => {
        const s = run(resting(), startReprice(), exec("ORDER_CANCELLED", ORIG));
        const after = run(s, exec("ORDER_REJECTED", ORIG), exec("ORDER_CANCELLED", ORIG));
        expect(after.reprices).toBe(s.reprices);
        expect(after.orderNotices).toBe(s.orderNotices);
    });

    it("refuses a second reprice of the same order while one is in flight", () => {
        const s1 = run(resting(), startReprice(999_700));
        const s2 = reducer(s1, startReprice(999_500));
        expect(s2.reprices).toBe(s1.reprices);
        expect(s2.reprices[0].newPx).toBe(999_700);
    });

    it("never starts for an order that is not cancellable", () => {
        const pending = run(initialState, sentNew(ORIG, "BUY", 1_000_000, 10));
        expect(reducer(pending, startReprice()).reprices).toEqual([]);
        expect(reducer(initialState, startReprice()).reprices).toEqual([]);
    });
});

describe("reprice: the fill race", () => {
    it("a full fill before the cancel drops the replacement with one toast, and the cancel reject that follows adds none", () => {
        const s1 = run(resting(), startReprice());
        const s2 = reducer(s1, hit(ORIG, 1_000_000, 100));
        expect(s2.reprices).toEqual([]);
        expect(kinds(s2).filter((k) => k === "modifyNotSent")).toHaveLength(1);
        expect(s2.orderNotices[s2.orderNotices.length - 1].title).toBe(NOTICE_TITLES.modifyNotSent);
        expect(NOTICE_TITLES.modifyNotSent).toBe("Modify not sent: order filled");
        // The engine refuses the cancel on a filled order.
        const s3 = reducer(s2, exec("ORDER_REJECTED", ORIG));
        expect(s3.orderNotices).toBe(s2.orderNotices);
        expect(row(s3).status).toBe("FILLED");
        expect(kinds(s3)).not.toContain("modifyFailed");
        expect(kinds(s3)).not.toContain("rejected");
    });

    it("a partial fill then the cancel re-sends the quantity remaining at cancel, not at drag time", () => {
        const s = run(resting(100), startReprice(), hit(ORIG, 1_000_000, 30), exec("ORDER_CANCELLED", ORIG));
        expect(s.reprices[0]).toMatchObject({ status: "ready", qty: 70 });
        expect(replacementIntent(row(s), s.reprices[0])).toEqual({
            side: "BUY",
            ordType: "LIMIT",
            tif: "GTC",
            pricePx: 999_700,
            qty: 70,
            displayQty: 0,
        });
    });
});

describe("reprice: aborts", () => {
    it("a cancel reject aborts with Modify failed and leaves the order as it was", () => {
        const s = run(resting(), startReprice(), exec("ORDER_REJECTED", ORIG));
        expect(s.reprices).toEqual([]);
        expect(kinds(s)).toContain("modifyFailed");
        expect(kinds(s)).not.toContain("rejected");
        expect(row(s).status).toBe("OPEN");
    });

    it("a timeout aborts with Modify failed; a late ORDER_CANCELLED then shows the normal cancelled toast and sends nothing", () => {
        expect(REPRICE_TIMEOUT_MS).toBe(2000);
        const s1 = run(resting(), startReprice(), { type: "REPRICE_TIMEOUT", origClOrdId: ORIG });
        expect(s1.reprices).toEqual([]);
        expect(kinds(s1).filter((k) => k === "modifyFailed")).toHaveLength(1);
        const s2 = reducer(s1, exec("ORDER_CANCELLED", ORIG));
        expect(s2.reprices).toEqual([]);
        expect(kinds(s2)[kinds(s2).length - 1]).toBe("cancelled");
        expect(row(s2).status).toBe("CANCELLED");
    });

    it("a timeout is ignored unless its own entry is still cancelSent", () => {
        const ready = run(resting(), startReprice(), exec("ORDER_CANCELLED", ORIG));
        expect(reducer(ready, { type: "REPRICE_TIMEOUT", origClOrdId: ORIG })).toBe(ready);
        const waiting = run(resting(), startReprice());
        expect(reducer(waiting, { type: "REPRICE_TIMEOUT", origClOrdId: ORIG + 1 })).toBe(waiting);
    });

    it("a failed replacement write aborts a ready entry, and is ignored otherwise", () => {
        const ready = run(resting(), startReprice(), exec("ORDER_CANCELLED", ORIG));
        const s = reducer(ready, { type: "REPRICE_SEND_FAILED", origClOrdId: ORIG });
        expect(s.reprices).toEqual([]);
        expect(kinds(s)[kinds(s).length - 1]).toBe("modifyFailed");
        const waiting = run(resting(), startReprice());
        expect(reducer(waiting, { type: "REPRICE_SEND_FAILED", origClOrdId: ORIG })).toBe(waiting);
    });

    it("a plain cancel from the blotter while the reprice waits ends it silently, so no replacement follows", () => {
        const s1 = run(resting(), startReprice(), sentCancel(ORIG));
        expect(s1.reprices).toEqual([]);
        const s2 = reducer(s1, exec("ORDER_CANCELLED", ORIG));
        expect(s2.reprices).toEqual([]);
        expect(kinds(s2)[kinds(s2).length - 1]).toBe("cancelled");
    });
});

describe("the reject guard (P18-0 F3)", () => {
    it("a reject naming an accepted order leaves it unchanged; a PENDING order is still rejected", () => {
        const open = reducer(resting(), exec("ORDER_REJECTED", ORIG));
        expect(row(open).status).toBe("OPEN");
        expect(kinds(open)).not.toContain("rejected");
        const pending = run(initialState, sentNew(ORIG, "BUY", 1_000_000, 10), exec("ORDER_REJECTED", ORIG));
        expect(row(pending).status).toBe("REJECTED");
    });
});

describe("pure helpers", () => {
    it("stepReprices keeps the array when no entry is named", () => {
        const s = run(resting(), startReprice());
        const step = stepReprices(s.reprices, (exec("ORDER_CANCELLED", ORIG + 9) as { frame: ExecFrame }).frame, s.myOrders);
        expect(step.reprices).toBe(s.reprices);
        expect(step.drafts).toEqual([]);
    });

    it("a replacement keeps an iceberg's display only while it still hides something", () => {
        const s = resting(500, 100);
        const r = beginReprice(row(s), 999_700);
        expect(replacementIntent(row(s), r).displayQty).toBe(100);
        expect(replacementIntent(row(s), { ...r, qty: 100 }).displayQty).toBe(0);
        expect(replacementIntent(row(s), { ...r, qty: 60 }).displayQty).toBe(0);
        expect(replacementIntent({ ...row(s), tif: "GTC" }, r).tif).toBe("GTC");
    });
});
