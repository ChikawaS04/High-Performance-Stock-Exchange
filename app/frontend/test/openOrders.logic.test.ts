import { describe, expect, it } from "vitest";

import { filledOf, orderTypeLabel } from "../src/components/OpenOrders";
import type { MyOrder } from "../src/state/reducer";

function order(originalQty: number, remainingQty: number): MyOrder {
    return {
        clOrdId: 1,
        side: "BUY",
        pricePx: 1500000,
        originalQty,
        remainingQty,
        status: "OPEN",
        ordType: "LIMIT",
        tif: "GTC",
        displayQty: 0,
    };
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

describe("orderTypeLabel", () => {
    const withDisplay = (displayQty: number): MyOrder => ({
        clOrdId: 1,
        side: "BUY",
        pricePx: 1500000,
        originalQty: 100,
        remainingQty: 100,
        status: "OPEN",
        ordType: "LIMIT",
        tif: "GTC",
        displayQty,
    });

    it("is ICE when a display quantity is set", () => {
        expect(orderTypeLabel(withDisplay(10))).toBe("ICE");
    });

    it("is LMT when there is no display quantity", () => {
        expect(orderTypeLabel(withDisplay(0))).toBe("LMT");
    });

    it("is MID for a midpoint peg, ahead of the display check", () => {
        const peg: MyOrder = {
            clOrdId: 1,
            side: "BUY",
            pricePx: -1,
            originalQty: 100,
            remainingQty: 100,
            status: "OPEN",
            ordType: "PEG_MID",
            tif: "GTC",
            displayQty: 0,
        };
        expect(orderTypeLabel(peg)).toBe("MID");
    });
});
