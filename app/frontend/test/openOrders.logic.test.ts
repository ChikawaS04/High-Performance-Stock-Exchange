import { describe, expect, it } from "vitest";

import { filledOf } from "../src/components/OpenOrders";
import type { MyOrder } from "../src/state/reducer";

function order(originalQty: number, remainingQty: number): MyOrder {
    return { clOrdId: 1, side: "BUY", pricePx: 1500000, originalQty, remainingQty, status: "OPEN" };
}

describe("filledOf", () => {
    it("is original minus remaining", () => {
        expect(filledOf(order(10, 6))).toBe(4);
        expect(filledOf(order(10, 0))).toBe(10);
    });

    it("is zero for an untouched order", () => {
        expect(filledOf(order(10, 10))).toBe(0);
    });

    it("clamps at zero and never returns a negative", () => {
        // Defensive: remaining should never exceed original, but a bad pair must not
        // render a negative filled quantity.
        expect(filledOf(order(10, 12))).toBe(0);
    });
});
