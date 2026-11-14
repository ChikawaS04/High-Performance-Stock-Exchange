import { describe, expect, it } from "vitest";

import { chartSettingKeys } from "../src/chartSettings";
import {
    cleanParams,
    formatParam,
    isParamRecord,
    paramValue,
    parseParam,
    resolveParams,
    validParam,
    withParam,
} from "../src/indicatorParams";
import type { IndicatorDescriptor, IndicatorParam } from "../src/indicators/registry";
import { SETTING_KEYS } from "../src/settings/persist";

const LEN: IndicatorParam = { id: "length", label: "Length", kind: "int", min: 2, max: 200, default: 20, presets: [10, 20, 50] };
const WIDTH: IndicatorParam = {
    id: "width",
    label: "Std dev",
    kind: "int",
    min: 5,
    max: 50,
    default: 20,
    presets: [10, 15, 20, 25, 30],
    display: "tenths",
};

const REG: readonly IndicatorDescriptor[] = [
    { id: "avg", label: "Avg", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["candles"], params: [LEN] },
    { id: "band", label: "Band", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["candles"], params: [LEN, WIDTH] },
    { id: "pageonly", label: "P", placement: "overlay", surfaces: ["page"], chartTypes: ["candles"], params: [LEN] },
    { id: "plain", label: "Plain", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["candles"] },
];
const band = REG[1];

describe("parameter validation (P19-7, D7)", () => {
    it("accepts integers within [min, max] only", () => {
        expect(validParam(LEN, 2)).toBe(true);
        expect(validParam(LEN, 200)).toBe(true);
        for (const bad of [1, 201, 20.5, "20", null, undefined, Number.NaN]) expect(validParam(LEN, bad)).toBe(false);
    });

    it("the stored record is read loosely: any plain object", () => {
        expect(isParamRecord({})).toBe(true);
        expect(isParamRecord({ avg: { length: "x" } })).toBe(true);
        for (const bad of [null, [], "x", 3]) expect(isParamRecord(bad)).toBe(false);
    });
});

describe("cleaning per value (P19-7, amended)", () => {
    it("drops one bad value and keeps the others", () => {
        const raw = { band: { length: 50, width: 999 }, avg: { length: 30 } };
        expect(cleanParams(raw, "page", REG)).toEqual({ band: { length: 50 }, avg: { length: 30 } });
    });

    it("drops unknown indicators, unknown params, indicators without params and off-surface ids", () => {
        const raw = { ghost: { length: 30 }, avg: { length: 30, extra: 4 }, plain: { length: 30 }, pageonly: { length: 30 } };
        expect(cleanParams(raw, "window", REG)).toEqual({ avg: { length: 30 } });
        expect(cleanParams(raw, "page", REG)).toEqual({ avg: { length: 30 }, pageonly: { length: 30 } });
    });

    it("reads anything but an object as no overrides", () => {
        expect(cleanParams(null, "page", REG)).toEqual({});
        expect(cleanParams({ avg: 7 }, "page", REG)).toEqual({});
    });

    it("an invalid stored value falls back to its default", () => {
        expect(paramValue({ band: { width: 999 } }, band, "width")).toBe(20);
        expect(paramValue({}, band, "length")).toBe(20);
        expect(resolveParams({ band: { length: 50 } }, band)).toEqual({ length: 50, width: 20 });
    });

    it("withParam sets one value and keeps the rest", () => {
        expect(withParam({ band: { length: 50 }, avg: { length: 9 } }, "band", "width", 25)).toEqual({
            band: { length: 50, width: 25 },
            avg: { length: 9 },
        });
    });

    it("persists per surface under separate keys", () => {
        expect(chartSettingKeys("page").indicatorParams).toBe(SETTING_KEYS.priceChartIndicatorParams);
        expect(chartSettingKeys("window").indicatorParams).toBe(SETTING_KEYS.priceWindowIndicatorParams);
        expect(SETTING_KEYS.priceChartIndicatorParams).toBe("hpse.v1.priceChart.indicatorParams");
        expect(SETTING_KEYS.priceWindowIndicatorParams).toBe("hpse.v1.priceWindow.indicatorParams");
    });
});

describe("edit parse and format (P19-7, amendment 5)", () => {
    it("integer params take whole numbers in range", () => {
        expect(parseParam(LEN, "50")).toBe(50);
        expect(parseParam(LEN, " 7 ")).toBe(7);
        for (const bad of ["", "1", "201", "2.5", "-3", "abc", "1e2"]) expect(parseParam(LEN, bad)).toBeNull();
    });

    it("a tenths param shows one decimal and parses exactly", () => {
        expect(formatParam(WIDTH, 20)).toBe("2.0");
        expect(formatParam(WIDTH, 25)).toBe("2.5");
        expect(formatParam(WIDTH, 5)).toBe("0.5");
        expect(formatParam(LEN, 20)).toBe("20");
        expect(parseParam(WIDTH, "2.5")).toBe(25);
        expect(parseParam(WIDTH, "2")).toBe(20);
        expect(parseParam(WIDTH, "2.")).toBe(20);
        expect(parseParam(WIDTH, "0.3")).toBeNull(); // below 0.5
        for (const bad of ["2.55", "6", ".5", "x"]) expect(parseParam(WIDTH, bad)).toBeNull();
    });
});
