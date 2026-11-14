import { describe, expect, it } from "vitest";

import {
    BACKING_PAD_X,
    BACKING_PAD_Y,
    MONO_ADVANCE_EM,
    READOUT_FONT_PX,
    READOUT_KEY_GAP,
    READOUT_PART_GAP,
    readoutBacking,
    readoutTextWidth,
} from "../src/readoutBacking";

const ADV = READOUT_FONT_PX * MONO_ADVANCE_EM; // 6px per glyph at 10px

describe("readout backing (P19-10 open item)", () => {
    it("measures a bare value by its character count", () => {
        expect(ADV).toBe(6);
        expect(readoutTextWidth([["", "1445.12"]])).toBe(7 * ADV);
    });

    it("adds the key gap after each key and the part gap before every keyed part but the first", () => {
        const w = readoutTextWidth([
            ["O", "1445.12"],
            ["H", "1446.00"],
        ]);
        expect(w).toBe(1 * ADV + READOUT_KEY_GAP + 7 * ADV + READOUT_PART_GAP + 1 * ADV + READOUT_KEY_GAP + 7 * ADV);
    });

    it("grows with the readout's length", () => {
        const base: [string, string][] = [
            ["O", "1445.12"],
            ["C", "1445.20"],
        ];
        const longer: [string, string][] = [...base, ["SMA20", "1445.1234"]];
        const longerValue: [string, string][] = [["O", "1445.1234"], ["C", "1445.20"]];
        expect(readoutTextWidth(longer)).toBeGreaterThan(readoutTextWidth(base));
        expect(readoutTextWidth(longerValue) - readoutTextWidth(base)).toBe(2 * ADV);
    });

    it("wraps the text with the padding on every side", () => {
        const parts: [string, string][] = [["V", "120"]];
        const r = readoutBacking(parts, 14, 24);
        expect(r.x).toBe(14 - BACKING_PAD_X);
        expect(r.width).toBe(readoutTextWidth(parts) + 2 * BACKING_PAD_X);
        expect(r.y).toBe(24 - READOUT_FONT_PX / 2 - BACKING_PAD_Y);
        expect(r.height).toBe(READOUT_FONT_PX + 2 * BACKING_PAD_Y);
        expect([BACKING_PAD_X, BACKING_PAD_Y]).toEqual([4, 2]);
    });
});
