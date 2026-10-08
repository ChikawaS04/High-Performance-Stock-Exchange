import { describe, expect, it } from "vitest";

import { aggressorSideFor, initialState, reducer } from "../src/state/reducer";
import type { Action, AppState, MyOrder } from "../src/state/reducer";
import { newOrderFrame } from "../src/protocol/encode";
import type { ExecFrame } from "../src/protocol/messages";

function run(state: AppState, ...actions: readonly Action[]): AppState {
    return actions.reduce(reducer, state);
}

function fill(
    aggressor: number,
    passive: number,
    opts: { tradeId: number; price: number; filled: number },
): Action {
    const frame: ExecFrame = {
        type: "EXEC",
        execType: "ORDER_FILLED",
        orderId: aggressor,
        tradeId: opts.tradeId,
        price: opts.price,
        filledQuantity: opts.filled,
        remainingQuantity: 0,
        aggressorOrderId: aggressor,
        passiveOrderId: passive,
        timestamp: 1,
    };
    return { type: "FRAME", frame };
}

const sent = (f: ReturnType<typeof newOrderFrame>): Action => ({ type: "SENT", frame: f });

describe("aggressorSideFor (pure)", () => {
    const orders: MyOrder[] = [
        { clOrdId: 1, side: "BUY", pricePx: 1500000, originalQty: 10, remainingQty: 10, status: "OPEN" },
        { clOrdId: 2, side: "SELL", pricePx: 1502500, originalQty: 5, remainingQty: 5, status: "OPEN" },
    ];

    it("returns our own side when we are the aggressor", () => {
        expect(aggressorSideFor(orders, 1, 99)).toBe("BUY");
        expect(aggressorSideFor(orders, 2, 99)).toBe("SELL");
    });

    it("returns the opposite of our side when we are the passive side", () => {
        expect(aggressorSideFor(orders, 99, 1)).toBe("SELL"); // our resting BUY was hit by a seller
        expect(aggressorSideFor(orders, 99, 2)).toBe("BUY"); // our resting SELL was lifted by a buyer
    });

    it("returns undefined for a foreign trade and never matches a -1 NA id", () => {
        expect(aggressorSideFor(orders, 98, 99)).toBeUndefined();
        expect(aggressorSideFor(orders, -1, -1)).toBeUndefined();
    });
});

describe("reducer attaches aggressorSide to the tape print", () => {
    it("tags our aggressor fill with our side", () => {
        const state = run(
            initialState,
            sent(newOrderFrame(1, "BUY", 1500000, 10)),
            fill(1, 99, { tradeId: 1, price: 1500000, filled: 10 }),
        );
        expect(state.tape[0].aggressorSide).toBe("BUY");
        expect(state.tape[0].mine).toBe(true);
    });

    it("tags our passive fill with the opposite side", () => {
        const state = run(
            initialState,
            sent(newOrderFrame(1, "SELL", 1502500, 10)),
            fill(99, 1, { tradeId: 2, price: 1502500, filled: 4 }),
        );
        expect(state.tape[0].aggressorSide).toBe("BUY");
        expect(state.tape[0].mine).toBe(true);
    });

    it("leaves a foreign print without a side, matching mine=false", () => {
        const state = run(
            initialState,
            sent(newOrderFrame(1, "BUY", 1500000, 10)),
            fill(98, 99, { tradeId: 3, price: 1500000, filled: 1 }),
        );
        expect(state.tape[0].aggressorSide).toBeUndefined();
        expect(state.tape[0].mine).toBe(false);
    });
});
