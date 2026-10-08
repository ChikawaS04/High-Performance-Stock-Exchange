import { describe, expect, it } from "vitest";

import { initialState, passiveFill, reducer } from "../src/state/reducer";
import type { Action, AppState, MyOrder } from "../src/state/reducer";
import { newOrderFrame } from "../src/protocol/encode";
import type { ExecFrame } from "../src/protocol/messages";

function run(state: AppState, ...actions: readonly Action[]): AppState {
    return actions.reduce(reducer, state);
}

const sent = (f: ReturnType<typeof newOrderFrame>): Action => ({ type: "SENT", frame: f });

/** A generic EXEC action; every NA field defaults to -1, override what matters. */
function execAction(execType: ExecFrame["execType"], overrides: Partial<ExecFrame>): Action {
    const frame: ExecFrame = {
        type: "EXEC",
        execType,
        orderId: -1,
        tradeId: -1,
        price: -1,
        filledQuantity: -1,
        remainingQuantity: -1,
        aggressorOrderId: -1,
        passiveOrderId: -1,
        timestamp: 1,
        ...overrides,
    };
    return { type: "FRAME", frame };
}

/** A fill EXEC naming an aggressor and a passive order. remaining is the AGGRESSOR's. */
const fill = (
    aggressor: number,
    passive: number,
    opts: { tradeId: number; price: number; filled: number; remaining?: number },
): Action =>
    execAction("ORDER_FILLED", {
        orderId: aggressor,
        tradeId: opts.tradeId,
        price: opts.price,
        filledQuantity: opts.filled,
        remainingQuantity: opts.remaining ?? 0,
        aggressorOrderId: aggressor,
        passiveOrderId: passive,
    });

function order(overrides: Partial<MyOrder> = {}): MyOrder {
    return {
        clOrdId: 1,
        side: "BUY",
        pricePx: 1500000,
        originalQty: 10,
        remainingQty: 10,
        status: "OPEN",
        ...overrides,
    };
}

describe("passiveFill (pure)", () => {
    it("decrements remaining and marks PARTIALLY_FILLED on a partial consumption", () => {
        const after = passiveFill(order({ remainingQty: 10 }), 4);
        expect(after.remainingQty).toBe(6);
        expect(after.status).toBe("PARTIALLY_FILLED");
    });

    it("marks FILLED when the decrement reaches zero", () => {
        const after = passiveFill(order({ remainingQty: 4 }), 4);
        expect(after.remainingQty).toBe(0);
        expect(after.status).toBe("FILLED");
    });

    it("clamps remaining at zero and never goes negative", () => {
        const after = passiveFill(order({ remainingQty: 3 }), 10);
        expect(after.remainingQty).toBe(0);
        expect(after.status).toBe("FILLED");
    });

    it("never resurrects a terminal row (returns the same reference)", () => {
        for (const status of ["FILLED", "CANCELLED", "REJECTED"] as const) {
            const before = order({ remainingQty: 5, status });
            expect(passiveFill(before, 2)).toBe(before);
        }
    });
});

describe("reducer applies the passive decrement via passiveOrderId (P7-8)", () => {
    const resting = (): AppState => run(initialState, sent(newOrderFrame(1, "BUY", 1500000, 10)));

    it("decrements our resting order when it is the passive side of a fill", () => {
        const after = run(resting(), fill(99, 1, { tradeId: 1, price: 1500000, filled: 4 }));
        expect(after.myOrders[0].clOrdId).toBe(1);
        expect(after.myOrders[0].remainingQty).toBe(6);
        expect(after.myOrders[0].status).toBe("PARTIALLY_FILLED");
    });

    it("never matches a -1 NA passiveOrderId to an order", () => {
        // A foreign-aggressor fill carrying the NA sentinel as its passive id must not
        // touch our resting row: client clOrdIds are positive, -1 can never match.
        const after = run(resting(), fill(5, -1, { tradeId: 1, price: 1500000, filled: 4 }));
        expect(after.myOrders[0].remainingQty).toBe(10);
        expect(after.myOrders[0].status).toBe("PENDING");
    });

    it("does not resurrect a terminal row via the passive path", () => {
        const cancelled = run(resting(), execAction("ORDER_CANCELLED", { orderId: 1 }));
        expect(cancelled.myOrders[0].status).toBe("CANCELLED");

        const after = run(cancelled, fill(99, 1, { tradeId: 2, price: 1500000, filled: 4 }));
        expect(after.myOrders[0].status).toBe("CANCELLED");
        expect(after.myOrders[0].remainingQty).toBe(cancelled.myOrders[0].remainingQty);
    });

    it("is correct when the same order is aggressor on one trade and passive on another", () => {
        // Trade A: order 1 is the aggressor, EXEC-authoritative remaining 6.
        const afterA = run(
            resting(),
            execAction("ORDER_PARTIALLY_FILLED", {
                orderId: 1,
                tradeId: 1,
                price: 1500000,
                filledQuantity: 4,
                remainingQuantity: 6,
                aggressorOrderId: 1,
                passiveOrderId: 77,
            }),
        );
        expect(afterA.myOrders[0].remainingQty).toBe(6);
        expect(afterA.myOrders[0].status).toBe("PARTIALLY_FILLED");

        // Trade B: order 1 is now the passive side, decremented locally by 2 -> 4.
        // filled = original(10) - remaining(4) = 6, correct across both roles.
        const afterB = run(afterA, fill(88, 1, { tradeId: 2, price: 1500000, filled: 2 }));
        expect(afterB.myOrders[0].remainingQty).toBe(4);
        expect(afterB.myOrders[0].status).toBe("PARTIALLY_FILLED");
    });
});
