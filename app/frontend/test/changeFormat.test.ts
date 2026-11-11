import { describe, expect, it } from "vitest";

import { changeSign, formatSignedDollar, formatSignedPercent, EMPTY_PRICE } from "../src/format";

// UI polish D1: one percent-change formatter shared by the tape and the header.
describe("formatSignedPercent (D1)", () => {
    it("renders a zero change as 0.00% with no sign", () => {
        expect(formatSignedPercent(0, 14_466_900)).toBe("0.00%");
    });

    it("renders a one-tick move on 1446.69 at four decimals", () => {
        // 1446.69 -> 1446.68 is -0.01, i.e. -0.00069%.
        expect(formatSignedPercent(-100, 14_466_900)).toBe("-0.0007%");
        expect(formatSignedPercent(100, 14_466_900)).toBe("+0.0007%");
    });

    it("keeps two decimals when two decimals are non-zero, rounding half away from zero", () => {
        expect(formatSignedPercent(2_500, 1_500_000)).toBe("+0.17%"); // 0.1667%
        expect(formatSignedPercent(7_250, 1_000_000)).toBe("+0.73%"); // exactly 0.725%
        expect(formatSignedPercent(-7_250, 1_000_000)).toBe("-0.73%");
        expect(formatSignedPercent(500, 1_000_000)).toBe("+0.05%");
        expect(formatSignedPercent(3_832_000, 16_168_000)).toBe("+23.70%");
    });

    it("switches to four decimals only when two would round to 0.00", () => {
        // 0.0049% rounds to 0.00 at two decimals, so it shows four.
        expect(formatSignedPercent(49, 1_000_000)).toBe("+0.0049%");
        // 0.005% rounds to 0.01 at two decimals, so it stays at two.
        expect(formatSignedPercent(50, 1_000_000)).toBe("+0.01%");
    });

    it("renders a sub-penny move that rounds to zero at four decimals as <0.0001%", () => {
        expect(formatSignedPercent(-1, 14_466_900)).toBe("-<0.0001%");
        expect(formatSignedPercent(1, 14_466_900)).toBe("+<0.0001%");
    });

    it("is symmetric: a move and its mirror differ only in sign", () => {
        const cases: readonly (readonly [number, number])[] = [
            [100, 14_466_900],
            [2_500, 1_500_000],
            [1, 14_466_900],
            [123_456, 9_876_500],
        ];
        for (const [d, ref] of cases) {
            const up = formatSignedPercent(d, ref);
            const down = formatSignedPercent(-d, ref);
            expect(up.startsWith("+")).toBe(true);
            expect(down.startsWith("-")).toBe(true);
            expect(up.slice(1)).toBe(down.slice(1));
        }
    });

    it("carries the same sign as the dollar change it sits beside", () => {
        for (const d of [-100, -1, 1, 100, 2_500, -2_500]) {
            expect(formatSignedPercent(d, 14_466_900)[0]).toBe(formatSignedDollar(d)[0]);
        }
    });

    it("returns the empty marker without a valid reference", () => {
        expect(formatSignedPercent(100, 0)).toBe(EMPTY_PRICE);
        expect(formatSignedPercent(100, -1)).toBe(EMPTY_PRICE);
        expect(formatSignedPercent(1.5, 1_000_000)).toBe(EMPTY_PRICE);
    });
});

describe("changeSign (D1 colour source)", () => {
    it("takes the direction from the dollar delta, not from a rounded percent", () => {
        expect(changeSign(-100)).toBe("down");
        expect(changeSign(1)).toBe("up");
        expect(changeSign(0)).toBe("flat");
        // A move whose percent renders "<0.0001%" still reads down.
        expect(changeSign(-1)).toBe("down");
    });
});
