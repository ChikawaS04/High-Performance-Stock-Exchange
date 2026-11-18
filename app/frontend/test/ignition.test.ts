import { describe, expect, it } from "vitest";

import {
    MAX_ATTEMPTS,
    OTHER_RETRY_MS,
    nextRetryDelayMs,
    parseIgnitionOpen,
    parseRetryAfter,
} from "../src/market/ignition";

const GOOD = { symbol: "ASML", openPx: 1498000, date: 20261118 };

describe("parseIgnitionOpen", () => {
    it("returns openPx from a well-formed venue body", () => {
        expect(parseIgnitionOpen(GOOD)).toBe(1498000);
        expect(parseIgnitionOpen({ ...GOOD, openPx: 7201500 })).toBe(7201500);
    });

    it("rejects a missing or empty symbol", () => {
        expect(parseIgnitionOpen({ openPx: 1498000, date: 20261118 })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, symbol: "" })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, symbol: 7 })).toBeNull();
    });

    it("rejects an openPx that is not a positive integer on the cent", () => {
        expect(parseIgnitionOpen({ ...GOOD, openPx: undefined })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, openPx: "1498000" })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, openPx: 149.8 })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, openPx: 1498050 })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, openPx: 0 })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, openPx: -100 })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, openPx: Number.NaN })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, openPx: Number.POSITIVE_INFINITY })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, openPx: 2 ** 60 })).toBeNull();
    });

    it("rejects a date that is not an 8-digit integer", () => {
        expect(parseIgnitionOpen({ ...GOOD, date: undefined })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, date: "20261118" })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, date: 2026111 })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, date: 202611180 })).toBeNull();
        expect(parseIgnitionOpen({ ...GOOD, date: 20261118.5 })).toBeNull();
    });

    it("rejects the old Alpaca snapshot shape", () => {
        expect(parseIgnitionOpen({ dailyBar: { o: 149.8 } })).toBeNull();
    });

    it("returns null for null or non-object input without throwing", () => {
        expect(parseIgnitionOpen(null)).toBeNull();
        expect(parseIgnitionOpen(undefined)).toBeNull();
        expect(parseIgnitionOpen(42)).toBeNull();
        expect(parseIgnitionOpen("ASML")).toBeNull();
    });
});

describe("parseRetryAfter", () => {
    it("reads whole seconds", () => {
        expect(parseRetryAfter("5")).toBe(5);
        expect(parseRetryAfter(" 60 ")).toBe(60);
        expect(parseRetryAfter("0")).toBe(0);
    });

    it("returns null when absent or not whole seconds", () => {
        expect(parseRetryAfter(null)).toBeNull();
        expect(parseRetryAfter("")).toBeNull();
        expect(parseRetryAfter("-1")).toBeNull();
        expect(parseRetryAfter("1.5")).toBeNull();
        expect(parseRetryAfter("Wed, 18 Nov 2026 15:00:00 GMT")).toBeNull();
    });
});

describe("nextRetryDelayMs", () => {
    const unavailable = (retryAfterS: number | null) => ({ kind: "unavailable" as const, retryAfterS });
    const failed = { kind: "failed" as const };

    it("waits a 503's Retry-After, clamped to 1 to 60 s", () => {
        expect(nextRetryDelayMs(unavailable(5), 1, false)).toBe(5000);
        expect(nextRetryDelayMs(unavailable(300), 1, false)).toBe(60000);
        expect(nextRetryDelayMs(unavailable(0), 1, false)).toBe(1000);
        expect(nextRetryDelayMs(unavailable(null), 1, false)).toBe(1000);
    });

    it("gives any other failure one retry only", () => {
        expect(nextRetryDelayMs(failed, 1, false)).toBe(OTHER_RETRY_MS);
        expect(nextRetryDelayMs(failed, 2, true)).toBeNull();
    });

    it("a spent other-failure retry does not stop 503 retries", () => {
        expect(nextRetryDelayMs(unavailable(3), 3, true)).toBe(3000);
    });

    it("stops at the attempt cap whatever the failure", () => {
        expect(nextRetryDelayMs(unavailable(5), MAX_ATTEMPTS - 1, false)).toBe(5000);
        expect(nextRetryDelayMs(unavailable(5), MAX_ATTEMPTS, false)).toBeNull();
        expect(nextRetryDelayMs(failed, MAX_ATTEMPTS, false)).toBeNull();
    });
});
