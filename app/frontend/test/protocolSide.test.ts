import { describe, expect, it } from "vitest";

import { parseServerFrame } from "../src/protocol/messages";

const BASE = {
    type: "EXEC",
    orderId: 5,
    tradeId: -1,
    price: 1_500_000,
    filledQuantity: -1,
    remainingQuantity: 10,
    aggressorOrderId: -1,
    passiveOrderId: -1,
    timestamp: 1,
};

describe("parseServerFrame: EXEC sides", () => {
    it("reads the aggressor's side on a fill", () => {
        const f = parseServerFrame(JSON.stringify({ ...BASE, execType: "ORDER_FILLED", aggressorSide: "SELL" }));
        expect(f?.type === "EXEC" && f.aggressorSide).toBe("SELL");
    });

    it("reads the resting order's side on an ACCEPTED and leaves a null aggressorSide out", () => {
        const f = parseServerFrame(JSON.stringify({ ...BASE, execType: "ORDER_ACCEPTED", aggressorSide: null, side: "BUY" }));
        expect(f).not.toBeNull();
        if (f?.type !== "EXEC") throw new Error("not an EXEC");
        expect(f.side).toBe("BUY");
        expect("aggressorSide" in f).toBe(false);
    });

    it("ignores a malformed side rather than rejecting the frame", () => {
        const f = parseServerFrame(JSON.stringify({ ...BASE, execType: "ORDER_ACCEPTED", side: "LONG" }));
        if (f?.type !== "EXEC") throw new Error("not an EXEC");
        expect("side" in f).toBe(false);
    });
});
