import { describe, expect, it } from "vitest";

import { newOrderFrame } from "../src/protocol/encode";
import { parseServerFrame } from "../src/protocol/messages";
import type { ExecFrame } from "../src/protocol/messages";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";

// P16-8: under the venue's disk hard stop, a NEW is refused before sequencing and only its sender gets
// an EXEC ORDER_REJECTED with no seq. This pins how the terminal handles that frame, byte for byte as
// the backend writes it (publisher/ExecutionFrames.refusalJson).
const REFUSAL_JSON =
    '{"type":"EXEC","execType":"ORDER_REJECTED","orderId":7,"tradeId":-1,"price":-1,"filledQuantity":-1,' +
    '"remainingQuantity":-1,"aggressorOrderId":-1,"passiveOrderId":-1,"aggressorSide":null,' +
    '"aggressorParticipantId":-1,"passiveParticipantId":-1,"timestamp":1700000000123456789}';

function run(state: AppState, ...actions: readonly Action[]): AppState {
    return actions.reduce(reducer, state);
}

const sentNew = (clOrdId: number): Action => ({
    type: "SENT",
    frame: newOrderFrame(clOrdId, { side: "BUY", ordType: "LIMIT", tif: "GTC", pricePx: 1500000, qty: 10, displayQty: 0 }),
});

function parsedRefusal(json = REFUSAL_JSON): ExecFrame {
    const frame = parseServerFrame(json);
    if (frame === null || frame.type !== "EXEC") throw new Error("the refusal did not parse as an EXEC");
    return frame;
}

describe("a seq-less ORDER_REJECTED (the disk hard stop)", () => {
    it("parses as an EXEC reject with no seq", () => {
        const frame = parsedRefusal();
        expect(frame.execType).toBe("ORDER_REJECTED");
        expect(frame.orderId).toBe(7);
        expect(frame.seq).toBeUndefined();
        expect(frame.aggressorSide).toBeUndefined();
    });

    it("marks the order rejected, raises its notice and records no venue seq", () => {
        const state = run(initialState, sentNew(7), { type: "FRAME", frame: parsedRefusal() });
        expect(state.myOrders).toHaveLength(1);
        expect(state.myOrders[0].clOrdId).toBe(7);
        expect(state.myOrders[0].status).toBe("REJECTED");
        expect(state.orderNotices.length).toBe(1);
        expect(state.recentExecSeqs).toEqual([]);
        expect(state.book).toBe(initialState.book);
        expect(state.tape).toEqual([]);
    });

    it("leaves the seq memory of real reports alone and keeps working after it", () => {
        const withSeq = { ...parsedRefusal(REFUSAL_JSON.replace('"orderId":7', '"orderId":8')), seq: 41 };
        const state = run(
            initialState,
            sentNew(7),
            sentNew(8),
            { type: "FRAME", frame: parsedRefusal() },
            { type: "FRAME", frame: withSeq },
        );
        expect(state.myOrders.map((o) => o.status)).toEqual(["REJECTED", "REJECTED"]);
        expect(state.recentExecSeqs).toEqual([41]);
        expect(state.orderNotices.length).toBe(2);
    });
});
