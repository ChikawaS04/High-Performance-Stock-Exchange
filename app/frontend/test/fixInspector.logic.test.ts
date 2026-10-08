import { describe, expect, it } from "vitest";

import { matchesFilter } from "../src/components/FixInspector";
import type { InspectorEntry } from "../src/state/reducer";

const NEW_RAW =
    "8=FIX.4.2\u00019=51\u000135=D\u000111=7\u000155=ASML\u000154=1\u000138=100\u000144=150.25\u000110=123\u0001";
const CANCEL_RAW = "8=FIX.4.2\u00019=23\u000135=F\u000111=9\u000141=7\u000110=045\u0001";

function fixEntry(raw: string, seqNum = 1): InspectorEntry {
    return { type: "FIX", direction: "INBOUND", raw, seqNum, timestamp: 1 };
}

function execEntry(): InspectorEntry {
    return {
        type: "EXEC",
        execType: "ORDER_FILLED",
        orderId: 1,
        tradeId: 1,
        price: 1500000,
        filledQuantity: 4,
        remainingQuantity: 0,
        aggressorOrderId: 1,
        passiveOrderId: 2,
        timestamp: 1,
    };
}

describe("matchesFilter", () => {
    it("'all' matches everything", () => {
        expect(matchesFilter(fixEntry(NEW_RAW), "all")).toBe(true);
        expect(matchesFilter(execEntry(), "all")).toBe(true);
    });

    it("'new' matches only a FIX entry with 35=D", () => {
        expect(matchesFilter(fixEntry(NEW_RAW), "new")).toBe(true);
        expect(matchesFilter(fixEntry(CANCEL_RAW), "new")).toBe(false);
        expect(matchesFilter(execEntry(), "new")).toBe(false);
    });

    it("'cancel' matches only a FIX entry with 35=F", () => {
        expect(matchesFilter(fixEntry(CANCEL_RAW), "cancel")).toBe(true);
        expect(matchesFilter(fixEntry(NEW_RAW), "cancel")).toBe(false);
        expect(matchesFilter(execEntry(), "cancel")).toBe(false);
    });

    it("'exec' matches any EXEC entry regardless of execType", () => {
        expect(matchesFilter(execEntry(), "exec")).toBe(true);
        expect(matchesFilter(fixEntry(NEW_RAW), "exec")).toBe(false);
    });
});
