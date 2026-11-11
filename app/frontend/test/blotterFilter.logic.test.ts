import { describe, expect, it } from "vitest";

import {
    DEFAULT_STATUS_FILTER,
    STATUS_FILTER_OPTIONS,
    emptyFilterMessage,
    filterOrders,
    isStatusFilter,
    matchesStatusFilter,
} from "../src/blotterFilter";
import type { MyOrder, OrderStatus } from "../src/state/reducer";

function order(clOrdId: number, status: OrderStatus, remainingQty = 10, originalQty = 10): MyOrder {
    return {
        clOrdId,
        side: "BUY",
        pricePx: 1_500_000,
        originalQty,
        remainingQty,
        status,
        ordType: "LIMIT",
        tif: "GTC",
        displayQty: 0,
    };
}

const PENDING = order(1, "PENDING");
const OPEN = order(2, "OPEN");
const PARTIAL = order(3, "PARTIALLY_FILLED", 4);
const FILLED = order(4, "FILLED", 0);
const CANCELLED = order(5, "CANCELLED");
const PARTIAL_THEN_CANCELLED = order(6, "CANCELLED", 4);
const REJECTED = order(7, "REJECTED");
const EXPIRED = order(8, "EXPIRED", 3, 5);
const ALL = [PENDING, OPEN, PARTIAL, FILLED, CANCELLED, PARTIAL_THEN_CANCELLED, REJECTED, EXPIRED];

const ids = (rows: readonly MyOrder[]): number[] => rows.map((o) => o.clOrdId);

describe("blotter status filter (D6)", () => {
    it("defaults to ALL ORDERS and lists the options in order, all caps", () => {
        expect(DEFAULT_STATUS_FILTER).toBe("ALL");
        expect(STATUS_FILTER_OPTIONS.map((o) => o.label)).toEqual([
            "ALL ORDERS",
            "ACTIVE",
            "PARTIALLY FILLED",
            "FILLED",
            "CANCELLED",
        ]);
    });

    it("ALL ORDERS shows every row, including rejected and expired, in order", () => {
        expect(ids(filterOrders(ALL, "ALL"))).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    });

    it("ACTIVE shows every working order: pending, open and partially filled", () => {
        expect(ids(filterOrders(ALL, "ACTIVE"))).toEqual([1, 2, 3]);
    });

    it("PARTIALLY FILLED shows working orders with at least one fill, overlapping ACTIVE", () => {
        expect(ids(filterOrders(ALL, "PARTIALLY_FILLED"))).toEqual([3]);
        // a cancelled remainder after partial fills is no longer working
        expect(matchesStatusFilter(PARTIAL_THEN_CANCELLED, "PARTIALLY_FILLED")).toBe(false);
    });

    it("FILLED shows only fully filled orders", () => {
        expect(ids(filterOrders(ALL, "FILLED"))).toEqual([4]);
    });

    it("CANCELLED includes a cancelled remainder after partial fills", () => {
        expect(ids(filterOrders(ALL, "CANCELLED"))).toEqual([5, 6]);
    });

    it("rejected and expired rows appear under ALL ORDERS only", () => {
        for (const f of ["ACTIVE", "PARTIALLY_FILLED", "FILLED", "CANCELLED"] as const) {
            expect(matchesStatusFilter(REJECTED, f)).toBe(false);
            expect(matchesStatusFilter(EXPIRED, f)).toBe(false);
        }
    });

    it("gives a dim one-line message per filter", () => {
        expect(emptyFilterMessage("FILLED")).toBe("No filled orders");
        expect(emptyFilterMessage("ACTIVE")).toBe("No active orders");
        expect(emptyFilterMessage("PARTIALLY_FILLED")).toBe("No partially filled orders");
        expect(emptyFilterMessage("CANCELLED")).toBe("No cancelled orders");
    });

    it("validates stored values", () => {
        expect(isStatusFilter("FILLED")).toBe(true);
        expect(isStatusFilter("filled")).toBe(false);
        expect(isStatusFilter(3)).toBe(false);
        expect(isStatusFilter(null)).toBe(false);
    });
});
