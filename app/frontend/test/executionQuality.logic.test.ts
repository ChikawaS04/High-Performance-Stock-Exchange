import { describe, expect, it } from "vitest";

import {
    buildExecutionQuality,
    costBps,
    costClass,
    DASH,
    formatBps,
    formatFilled,
    formatPct,
    formatSpreadPaid,
    qualityRow,
    spreadPaid,
} from "../src/executionQuality";
import { arrivalFrom } from "../src/arrival";
import { foldExec } from "../src/state/reducer";
import type { ExecAggregate, FillRole, MyOrder } from "../src/state/reducer";
import type { Side } from "../src/protocol/messages";
import { EMPTY_PRICE } from "../src/format";

// P18-9: hand-computed execution quality. Arrival book: bid 1_000_000, ask 1_000_200,
// so the mid is 1_000_100 and the half-spread 100 units.

const ARRIVAL = arrivalFrom(1_000_000, 1_000_200, 1_000_050, 1);

function exec(fills: readonly [px: number, qty: number, role: FillRole][], lastVwap = 1_000_050): ExecAggregate {
    let e: ExecAggregate | undefined;
    for (const [px, qty, role] of fills) e = foldExec(e, px, qty, role, lastVwap);
    return e!;
}

function order(side: Side, fills: readonly [number, number, FillRole][], over: Partial<MyOrder> = {}): MyOrder {
    return {
        clOrdId: 1,
        side,
        pricePx: 1_000_200,
        originalQty: 100,
        remainingQty: 0,
        status: "FILLED",
        ordType: "LIMIT",
        tif: "GTC",
        displayQty: 0,
        arrival: ARRIVAL,
        exec: exec(fills),
        ...over,
    };
}

describe("vs Arrival (P18 D9)", () => {
    it("a buy above the arrival mid is a positive cost", () => {
        const r = qualityRow(order("BUY", [[1_000_200, 100, "aggressive"]]))!;
        // (1_000_200 - 1_000_100) / 1_000_100 x 10_000
        expect(r.vsArrivalBps).toBeCloseTo((100 / 1_000_100) * 10_000, 6);
        expect(r.vsArrivalBps!).toBeGreaterThan(0);
    });

    it("a sell below the arrival mid is a positive cost too (sign flipped)", () => {
        const r = qualityRow(order("SELL", [[1_000_000, 100, "aggressive"]]))!;
        expect(r.vsArrivalBps).toBeCloseTo((100 / 1_000_100) * 10_000, 6);
    });

    it("a fill better than the mid is a negative bps (improvement), for either side", () => {
        expect(qualityRow(order("BUY", [[1_000_000, 100, "passive"]]))!.vsArrivalBps!).toBeLessThan(0);
        expect(qualityRow(order("SELL", [[1_000_200, 100, "passive"]]))!.vsArrivalBps!).toBeLessThan(0);
    });

    it("has no value without an arrival mid", () => {
        const oneSided = order("BUY", [[1_000_200, 100, "aggressive"]], { arrival: arrivalFrom(1_000_000, -1, -1, 1) });
        const r = qualityRow(oneSided)!;
        expect(r.vsArrivalBps).toBeNull();
        expect(r.spreadPaid).toBeNull();
        expect(qualityRow(order("BUY", [[1_000_200, 100, "aggressive"]], { arrival: undefined }))!.vsArrivalBps).toBeNull();
    });
});

describe("vs VWAP (P18 D9)", () => {
    it("measures the average against the session VWAP after the order's last fill", () => {
        const r = qualityRow(order("BUY", [[1_000_200, 100, "aggressive"]]))!;
        expect(r.vsVwapBps).toBeCloseTo((150 / 1_000_050) * 10_000, 6);
        const noVwap = qualityRow({ ...order("BUY", [[1_000_200, 100, "aggressive"]]), exec: exec([[1_000_200, 100, "aggressive"]], -1) })!;
        expect(noVwap.vsVwapBps).toBeNull();
    });
});

describe("spread paid (P18 D9)", () => {
    it("exactly the half-spread gives 1.0, a midpoint fill 0, and an improvement goes negative", () => {
        expect(qualityRow(order("BUY", [[1_000_200, 100, "aggressive"]]))!.spreadPaid).toBe(1);
        expect(qualityRow(order("SELL", [[1_000_000, 100, "aggressive"]]))!.spreadPaid).toBe(1);
        expect(qualityRow(order("BUY", [[1_000_100, 100, "aggressive"]]))!.spreadPaid).toBe(0);
        expect(qualityRow(order("BUY", [[1_000_050, 100, "aggressive"]]))!.spreadPaid).toBe(-0.5);
        expect(qualityRow(order("SELL", [[1_000_150, 100, "aggressive"]]))!.spreadPaid).toBe(-0.5);
    });

    it("reads aggressive fills only, and a fully passive order has none", () => {
        const mixed = qualityRow(order("BUY", [[1_000_200, 50, "aggressive"], [1_000_000, 50, "passive"]]))!;
        expect(mixed.spreadPaid).toBe(1);
        expect(qualityRow(order("BUY", [[1_000_000, 100, "passive"]]))!.spreadPaid).toBeNull();
    });

    it("guards a zero half-spread: no value, no divide", () => {
        expect(spreadPaid("BUY", 100n * 1_000_000n, 100, 1_000_000, 1_000_000, 1_000_000)).toBeNull();
        expect(spreadPaid("BUY", 100n * 1_000_000n, 100, 1_000_100, 1_000_000, 1_000_050)).toBeNull();
    });
});

describe("avg, filled and passive share (P18 D9)", () => {
    it("averages by quantity, reports filled of original, and the passive share", () => {
        const r = qualityRow(order("BUY", [[1_000_200, 30, "aggressive"], [1_000_000, 70, "passive"]], { originalQty: 200 }))!;
        // (1_000_200 x 30 + 1_000_000 x 70) / 100 = 1_000_060
        expect(r.avgPx).toBe(1_000_060);
        expect(r.filledQty).toBe(100);
        expect(r.filledFrac).toBe(0.5);
        expect(r.passivePct).toBe(70);
    });

    it("an order with no fill has no row", () => {
        expect(qualityRow(order("BUY", [], { exec: undefined }))).toBeNull();
    });

    it("stays exact for notionals past Number.MAX_SAFE_INTEGER", () => {
        const px = 99_999_900;
        const qty = 1_000_000_000;
        const e = exec([
            [px, qty, "aggressive"],
            [px + 100, qty, "aggressive"],
        ]);
        expect(e.notional > BigInt(Number.MAX_SAFE_INTEGER)).toBe(true);
        // Average 99_999_950 against a mid of 99_999_900: exactly 50 / 99_999_900 x 10_000 bps.
        expect(costBps("BUY", e.notional, e.qty, px)).toBeCloseTo((50 / px) * 10_000, 6);
        expect(spreadPaid("BUY", e.aggNotional, e.aggQty, px - 100, px + 100, px)).toBe(0.5);
    });
});

describe("buildExecutionQuality (P18 D9)", () => {
    it("lists orders with a fill, newest submit first", () => {
        const a = order("BUY", [[1_000_200, 10, "aggressive"]], { clOrdId: 1, sentAtNanos: 100 });
        const b = order("SELL", [[1_000_000, 10, "aggressive"]], { clOrdId: 2, sentAtNanos: 300 });
        const c = order("BUY", [], { clOrdId: 3, exec: undefined, sentAtNanos: 400 });
        const d = order("BUY", [[1_000_200, 10, "aggressive"]], { clOrdId: 4, sentAtNanos: 200 });
        expect(buildExecutionQuality([a, b, c, d]).map((r) => r.clOrdId)).toEqual([2, 4, 1]);
    });
});

describe("display", () => {
    it("formats bps signed with one decimal, spread with two, and the dash for missing", () => {
        expect(formatBps(3.24)).toBe("+3.2");
        expect(formatBps(-0.86)).toBe("-0.9");
        expect(formatBps(0.04)).toBe("0.0");
        expect(formatBps(null)).toBe(DASH);
        expect(DASH).toBe(EMPTY_PRICE);
        expect(formatSpreadPaid(1)).toBe("1.00");
        expect(formatSpreadPaid(-0.5)).toBe("-0.50");
        expect(formatSpreadPaid(null)).toBe(DASH);
        expect(formatPct(66.6)).toBe("67%");
        expect(formatFilled({ filledQty: 300, originalQty: 500 })).toBe("300/500");
    });

    it("classes cost red and improvement green, a flat value neither", () => {
        expect(costClass(1.5)).toBe("cost");
        expect(costClass(-0.2)).toBe("improve");
        expect(costClass(0.001)).toBe("flat");
        expect(costClass(null)).toBe("none");
    });
});
