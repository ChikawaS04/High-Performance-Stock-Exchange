import { describe, expect, it } from "vitest";

import { parseIgnitionOpen } from "../src/market/ignition";

describe("parseIgnitionOpen", () => {
    it("parses a well-formed snapshot to integer units", () => {
        expect(parseIgnitionOpen({ dailyBar: { o: 149.8 } })).toBe(1498000);
        expect(parseIgnitionOpen({ dailyBar: { o: 720.15 } })).toBe(7201500);
    });

    it("rounds to the nearest cent at the edge", () => {
        expect(parseIgnitionOpen({ dailyBar: { o: 149.876 } })).toBe(1498800);
        expect(parseIgnitionOpen({ dailyBar: { o: 149.872 } })).toBe(1498700);
    });

    it("returns null for a missing dailyBar", () => {
        expect(parseIgnitionOpen({})).toBeNull();
    });

    it("returns null for a missing o", () => {
        expect(parseIgnitionOpen({ dailyBar: {} })).toBeNull();
    });

    it("returns null for a non-number o", () => {
        expect(parseIgnitionOpen({ dailyBar: { o: "149.80" } })).toBeNull();
    });

    it("returns null for a non-finite o", () => {
        expect(parseIgnitionOpen({ dailyBar: { o: Number.POSITIVE_INFINITY } })).toBeNull();
        expect(parseIgnitionOpen({ dailyBar: { o: Number.NaN } })).toBeNull();
    });

    it("returns null for a non-positive o", () => {
        expect(parseIgnitionOpen({ dailyBar: { o: 0 } })).toBeNull();
        expect(parseIgnitionOpen({ dailyBar: { o: -5 } })).toBeNull();
    });

    it("returns null for null or non-object input without throwing", () => {
        expect(parseIgnitionOpen(null)).toBeNull();
        expect(parseIgnitionOpen(undefined)).toBeNull();
        expect(parseIgnitionOpen(42)).toBeNull();
    });
});
