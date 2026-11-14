import { describe, expect, it } from "vitest";

import { emptySeries, foldFill } from "../src/bars";
import { bollinger, bollingerValues, roundUnits } from "../src/indicators/bollinger";
import { INDICATORS } from "../src/indicators/registry";
import { formatParam } from "../src/indicatorParams";

describe("Bollinger bands (P19-9, D9)", () => {
    it("matches hand computation with the population SD", () => {
        // [10, 20, 30]: mean 20, variance (100 + 0 + 100) / 3, SD sqrt(200 / 3).
        const p = bollingerValues([10, 20, 30], 3, 20)[2]!;
        const sd = Math.sqrt(200 / 3);
        expect(p.mid).toBe(20);
        expect(p.sd).toBeCloseTo(sd, 12);
        expect(p.upper).toBeCloseTo(20 + 2 * sd, 12);
        expect(p.lower).toBeCloseTo(20 - 2 * sd, 12);
    });

    it("scales the band half-width with the width parameter in tenths", () => {
        const two = bollingerValues([10, 20, 30], 3, 20)[2]!;
        const one = bollingerValues([10, 20, 30], 3, 10)[2]!;
        const twoHalf = bollingerValues([10, 20, 30], 3, 25)[2]!;
        expect(two.upper - two.mid).toBeCloseTo(2 * (one.upper - one.mid), 12);
        expect(twoHalf.upper - twoHalf.mid).toBeCloseTo(2.5 * (one.upper - one.mid), 12);
    });

    it("a flat series gives zero width and no NaN", () => {
        const p = bollingerValues([1_500_000, 1_500_000, 1_500_000, 1_500_000], 3, 20)[3]!;
        expect(p).toEqual({ mid: 1_500_000, upper: 1_500_000, lower: 1_500_000, sd: 0 });
        expect(Number.isNaN(p.upper)).toBe(false);
    });

    it("stays accurate on realistic prices", () => {
        // A window of 1,500,000 and 1,500,100: SD exactly 50 units.
        const p = bollingerValues([1_500_000, 1_500_100], 2, 20)[1]!;
        expect(p.sd).toBe(50);
        expect(p.upper).toBe(1_500_150);
        expect(p.lower).toBe(1_499_950);
    });

    it("is null before the window fills", () => {
        expect(bollingerValues([10, 20], 3, 20)).toEqual([null, null]);
    });

    it("rounds readout values half up", () => {
        expect(roundUnits(100.5)).toBe(101);
        expect(roundUnits(100.49)).toBe(100);
    });

    it("memoises on series, length and width", () => {
        const NS = 15_000_000_000;
        let s = emptySeries();
        for (let k = 0; k < 4; k++) s = foldFill(s, NS, k * NS + 1, 100 + k, 1, 100);
        expect(bollinger(s, 3, 20)).toBe(bollinger(s, 3, 20));
        expect(bollinger(s, 3, 25)).not.toBe(bollinger(s, 3, 20));
        expect(bollinger(s, 2, 20)).not.toBe(bollinger(s, 3, 20));
        expect([...bollinger(s, 3, 20).keys()]).toEqual([2, 3]);
    });

    it("is registered with length 20 and a width shown as a standard deviation", () => {
        const d = INDICATORS.find((x) => x.id === "bollinger")!;
        expect(d).toMatchObject({ placement: "overlay", surfaces: ["page", "window"], chartTypes: ["candles", "footprint"] });
        const [length, width] = d.params!;
        expect(length).toMatchObject({ id: "length", default: 20, min: 2, max: 200 });
        expect(width).toMatchObject({ id: "width", label: "Std dev", default: 20, display: "tenths" });
        expect(formatParam(width, width.default)).toBe("2.0");
        expect(width.presets.map((v) => formatParam(width, v))).toEqual(["1.0", "1.5", "2.0", "2.5", "3.0"]);
    });
});
