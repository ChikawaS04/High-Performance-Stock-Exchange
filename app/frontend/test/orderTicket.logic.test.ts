import { describe, expect, it } from "vitest";

import {
    applyPreset,
    midChipPx,
    nudgePx,
    QTY_PRESETS,
    TICK_PX,
    validateDisplayQty,
    validateQtyInput,
} from "../src/components/OrderEntry";

describe("midChipPx", () => {
    it("returns the exact on-tick mid for an even-tick book", () => {
        expect(midChipPx(1500000, 1505000)).toBe(1502500);
        expect(midChipPx(300, 500)).toBe(400);
    });

    it("snaps an odd-cent-spread (sub-penny) mid up to the next tick", () => {
        expect(midChipPx(1500000, 1502500)).toBe(1501300); // true mid 1501250 -> up to 1501300
        expect(midChipPx(100, 200)).toBe(200); // true mid 150 -> 200
    });

    it("always yields a positive on-tick price, never a sub-penny on the price path", () => {
        for (const [b, a] of [[1500000, 1502500], [100, 200], [700, 800], [9900, 10000]] as const) {
            const m = midChipPx(b, a);
            expect(m).not.toBeNull();
            expect(Number.isInteger(m as number)).toBe(true);
            expect(m as number).toBeGreaterThan(0);
        }
    });

    it("returns null when either side is the -1 sentinel (chip disabled)", () => {
        expect(midChipPx(-1, 1502500)).toBeNull();
        expect(midChipPx(1500000, -1)).toBeNull();
        expect(midChipPx(-1, -1)).toBeNull();
    });
});

describe("nudgePx", () => {
    it("adds and subtracts one tick (one cent) by default", () => {
        expect(TICK_PX).toBe(100);
        expect(nudgePx(1500000, 1)).toBe(1500100);
        expect(nudgePx(1500000, -1)).toBe(1499900);
    });

    it("never produces a non-positive price, clamping at the one-cent floor", () => {
        expect(nudgePx(100, -1)).toBe(100);
        expect(nudgePx(100, -5)).toBe(100);
        expect(nudgePx(200, -10)).toBe(100);
    });

    it("never produces a non-integer price for integer input", () => {
        for (let c = 100; c <= 2000; c += 100) {
            for (const s of [-3, -1, 1, 3]) {
                const n = nudgePx(c, s);
                expect(Number.isInteger(n)).toBe(true);
                expect(n).toBeGreaterThan(0);
            }
        }
    });
});

describe("applyPreset", () => {
    it("fills the quantity when the field is empty (or whitespace)", () => {
        expect(applyPreset("", 100)).toBe("100");
        expect(applyPreset("   ", 50)).toBe("50");
    });

    it("does not clobber an already-typed quantity", () => {
        expect(applyPreset("7", 100)).toBe("7");
        expect(applyPreset("250", 500)).toBe("250");
    });

    it("offers round-lot presets", () => {
        expect(QTY_PRESETS).toEqual([10, 50, 100, 500]);
    });
});

describe("validateDisplayQty", () => {
    it("treats an empty or whitespace field as a plain order (display 0)", () => {
        expect(validateDisplayQty("", 100)).toEqual({ ok: true, displayQty: 0 });
        expect(validateDisplayQty("   ", 100)).toEqual({ ok: true, displayQty: 0 });
    });

    it("accepts a positive display strictly below the quantity", () => {
        expect(validateDisplayQty("10", 100)).toEqual({ ok: true, displayQty: 10 });
        expect(validateDisplayQty("99", 100)).toEqual({ ok: true, displayQty: 99 });
    });

    it("normalises display == qty to a plain order (hides nothing, D7)", () => {
        expect(validateDisplayQty("100", 100)).toEqual({ ok: true, displayQty: 0 });
    });

    it("rejects a display greater than the quantity", () => {
        const r = validateDisplayQty("101", 100);
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.reason).toMatch(/display/i);
    });

    it("rejects zero, negative, fractional, and non-numeric displays", () => {
        for (const d of ["0", "-1", "1.5", "abc", "1e2", "1,0"]) {
            const r = validateDisplayQty(d, 100);
            expect(r.ok).toBe(false);
            if (!r.ok) expect(r.reason).toMatch(/display/i);
        }
    });
});


describe("validateQtyInput", () => {
    it("accepts a positive whole number, returning the integer qty", () => {
        expect(validateQtyInput("10")).toEqual({ ok: true, qty: 10 });
        expect(validateQtyInput("  250 ")).toEqual({ ok: true, qty: 250 });
    });

    it("rejects zero, negative, fractional, non-numeric, and empty quantities", () => {
        for (const q of ["0", "-1", "1.5", "abc", "1e2", "1,0", "", "   "]) {
            const r = validateQtyInput(q);
            expect(r.ok).toBe(false);
            if (!r.ok) expect(r.reason).toMatch(/quantity/i);
        }
    });
});
