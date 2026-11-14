import { describe, expect, it } from "vitest";

import { arrivalFrom } from "../src/arrival";
import type { Arrival } from "../src/arrival";
import { venueMid } from "../src/prices";
import { venueMid as chartVenueMid } from "../src/chartOverlays";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState, SendMeta } from "../src/state/reducer";
import { cancelOrderFrame, newOrderFrame } from "../src/protocol/encode";

// P18-8: the market at submit, stored on the new order's row.

const ORIG = 1_763_000_000_001;
const NEXT = 1_763_000_000_009;

function sentNew(id: number, px: number, meta?: SendMeta): Action {
    return {
        type: "SENT",
        frame: newOrderFrame(id, { side: "BUY", ordType: "LIMIT", tif: "GTC", pricePx: px, qty: 10, displayQty: 0 }),
        ...(meta !== undefined ? { meta } : {}),
    };
}

describe("arrivalFrom (P18 D8)", () => {
    it("records bid, ask, the exact mid, VWAP and time", () => {
        expect(arrivalFrom(1_000_000, 1_000_100, 1_000_020, 77)).toEqual({ bid: 1_000_000, ask: 1_000_100, mid: 1_000_050, vwap: 1_000_020, t: 77 });
    });

    it("records no mid for a one-sided book, and no VWAP before the first trade", () => {
        expect(arrivalFrom(1_000_000, -1, -1, 5)).toEqual({ bid: 1_000_000, ask: -1, mid: -1, vwap: -1, t: 5 });
        expect(arrivalFrom(-1, 1_000_100, 1_000_000, 5).mid).toBe(-1);
    });

    it("uses the one venue mid the chart uses", () => {
        expect(chartVenueMid).toBe(venueMid);
    });
});

describe("arrival in the reducer (P18-8)", () => {
    const A: Arrival = arrivalFrom(1_000_000, 1_000_200, 1_000_050, 123);

    it("stores the submit's arrival on the new row; a row sent without one has none", () => {
        const s = reducer(initialState, sentNew(ORIG, 1_000_000, { arrival: A }));
        expect(s.myOrders[0].arrival).toEqual(A);
        expect(reducer(initialState, sentNew(ORIG, 1_000_000)).myOrders[0].arrival).toBeUndefined();
    });

    it("a reprice replacement inherits its original's arrival, not its own", () => {
        const later = arrivalFrom(999_000, 999_200, 999_100, 999);
        const actions: Action[] = [
            sentNew(ORIG, 1_000_000, { arrival: A }),
            { type: "FRAME", frame: { type: "EXEC", execType: "ORDER_ACCEPTED", orderId: ORIG, tradeId: -1, price: 1_000_000, filledQuantity: -1, remainingQuantity: 10, aggressorOrderId: -1, passiveOrderId: -1, timestamp: 1 } },
            { type: "SENT", frame: cancelOrderFrame(ORIG + 1, ORIG), meta: { reprice: { newPx: 999_700 } } },
            { type: "FRAME", frame: { type: "EXEC", execType: "ORDER_CANCELLED", orderId: ORIG, tradeId: -1, price: -1, filledQuantity: -1, remainingQuantity: -1, aggressorOrderId: -1, passiveOrderId: -1, timestamp: 2 } },
            sentNew(NEXT, 999_700, { repriceOf: ORIG, arrival: later }),
        ];
        const s: AppState = actions.reduce(reducer, initialState);
        const replacement = s.myOrders.find((o) => o.clOrdId === NEXT)!;
        expect(replacement.arrival).toEqual(A);
    });

    it("a replacement of an original with no arrival keeps its own, if any", () => {
        const own = arrivalFrom(1, 3, -1, 9);
        const s = [sentNew(ORIG, 1_000_000), sentNew(NEXT, 999_700, { repriceOf: ORIG, arrival: own })].reduce(reducer, initialState);
        expect(s.myOrders.find((o) => o.clOrdId === NEXT)!.arrival).toEqual(own);
    });
});
