import { describe, expect, it } from "vitest";

import {
    chartSettingKeys,
    DEFAULT_BAR_SIZE,
    DEFAULT_CHART_TYPE,
    DEFAULT_INDICATORS,
    isBarSize,
    isChartType,
    isStringArray,
    sanitizeIndicators,
} from "../src/chartSettings";
import type { IndicatorDescriptor } from "../src/indicators/registry";

describe("chart settings validators (P17-4)", () => {
    it("chart type accepts line and candles only", () => {
        expect(isChartType("line")).toBe(true);
        expect(isChartType("candles")).toBe(true);
        for (const bad of ["heatmap", "Candles", "", 1, null, undefined, ["line"]]) expect(isChartType(bad)).toBe(false);
    });

    it("bar size accepts the four sizes only", () => {
        for (const ok of ["5s", "15s", "1m", "5m"]) expect(isBarSize(ok)).toBe(true);
        for (const bad of ["30s", "1h", "5", 5, null]) expect(isBarSize(bad)).toBe(false);
    });

    it("indicators are read leniently: any array of strings", () => {
        expect(isStringArray([])).toBe(true);
        expect(isStringArray(["volume", "nonsense"])).toBe(true);
        expect(isStringArray(["volume", 3])).toBe(false);
        expect(isStringArray("volume")).toBe(false);
        expect(isStringArray({ 0: "volume" })).toBe(false);
    });

    it("defaults are candles, 15s, volume and VWAP", () => {
        expect(DEFAULT_CHART_TYPE).toBe("candles");
        expect(DEFAULT_BAR_SIZE).toBe("15s");
        expect(DEFAULT_INDICATORS).toEqual(["volume", "vwap"]);
    });
});

describe("sanitizeIndicators (P17-4, D5 amended)", () => {
    it("drops unknown ids and duplicates, keeping stored order", () => {
        expect(sanitizeIndicators(["vwap", "bogus", "volume", "vwap"], "page")).toEqual(["vwap", "volume"]);
    });

    it("drops ids the surface does not offer", () => {
        const registry: IndicatorDescriptor[] = [
            { id: "profile", label: "Profile", placement: "pane", surfaces: ["page"], chartTypes: ["candles"] },
            { id: "vwap", label: "VWAP", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["line", "candles"] },
        ];
        expect(sanitizeIndicators(["profile", "vwap"], "window", registry)).toEqual(["vwap"]);
        expect(sanitizeIndicators(["profile", "vwap"], "page", registry)).toEqual(["profile", "vwap"]);
    });

    it("keeps an id that is only unavailable for the current chart type (volume in line mode)", () => {
        // sanitize is chart-type blind by design: volume stays stored while in line mode.
        expect(sanitizeIndicators(["volume"], "window")).toEqual(["volume"]);
    });
});

describe("per-surface keys (P17-4)", () => {
    it("builds page keys under priceChart and window keys under priceWindow", () => {
        expect(chartSettingKeys("page")).toEqual({
            chartType: "hpse.v1.priceChart.chartType",
            barSize: "hpse.v1.priceChart.barSize",
            indicators: "hpse.v1.priceChart.indicators",
        });
        expect(chartSettingKeys("window")).toEqual({
            chartType: "hpse.v1.priceWindow.chartType",
            barSize: "hpse.v1.priceWindow.barSize",
            indicators: "hpse.v1.priceWindow.indicators",
        });
    });
});
