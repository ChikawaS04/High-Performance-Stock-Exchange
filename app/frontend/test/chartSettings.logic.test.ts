import { describe, expect, it } from "vitest";

import {
    chartSettingKeys,
    DEFAULT_BAR_SIZE,
    DEFAULT_CHART_TYPE,
    DEFAULT_INDICATORS,
    isBarSize,
    isChartType,
    isStringArray,
    migrateIndicators,
    sanitizeIndicators,
    SEEN_BEFORE_P18,
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

    it("defaults are candles, 15s, volume, VWAP and Executions (P18-2)", () => {
        expect(DEFAULT_CHART_TYPE).toBe("candles");
        expect(DEFAULT_BAR_SIZE).toBe("15s");
        expect(DEFAULT_INDICATORS).toEqual(["volume", "vwap", "executions"]);
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
            indicatorsSeen: "hpse.v1.priceChart.indicatorsSeen",
        });
        expect(chartSettingKeys("window")).toEqual({
            chartType: "hpse.v1.priceWindow.chartType",
            barSize: "hpse.v1.priceWindow.barSize",
            indicators: "hpse.v1.priceWindow.indicators",
            indicatorsSeen: "hpse.v1.priceWindow.indicatorsSeen",
        });
    });
});

describe("migrateIndicators (P18-2)", () => {
    const LATER: IndicatorDescriptor[] = [
        { id: "volume", label: "Volume", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"], defaultOn: true },
        { id: "vwap", label: "VWAP", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["line", "candles"], defaultOn: true },
        { id: "executions", label: "Executions", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["line", "candles"], defaultOn: true },
        { id: "bidask", label: "Bid/Ask", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["line", "candles"] },
        { id: "pageOnly", label: "Page only", placement: "overlay", surfaces: ["page"], chartTypes: ["candles"], defaultOn: true },
    ];

    it("switches on an unseen defaultOn id once, keeps a user's Phase 17 choices, and marks every new id seen", () => {
        // A Phase 17 user who had turned VWAP off.
        const m = migrateIndicators(["volume"], SEEN_BEFORE_P18, "window", LATER);
        expect(m.changed).toBe(true);
        expect(m.indicators).toEqual(["volume", "executions"]);
        expect(m.seen).toEqual(["volume", "vwap", "executions", "bidask"]);
    });

    it("leaves a seen id alone, so a user who turned it off keeps it off", () => {
        const seen = ["volume", "vwap", "executions", "bidask", "pageOnly"];
        const m = migrateIndicators(["vwap"], seen, "page", LATER);
        expect(m.changed).toBe(false);
        expect(m.indicators).toEqual(["vwap"]);
        expect(m.seen).toBe(seen);
    });

    it("only offers ids the surface has", () => {
        const page = migrateIndicators([], SEEN_BEFORE_P18, "page", LATER);
        expect(page.indicators).toEqual(["executions", "pageOnly"]);
        const win = migrateIndicators([], SEEN_BEFORE_P18, "window", LATER);
        expect(win.indicators).toEqual(["executions"]);
    });

    it("does not duplicate an id already enabled", () => {
        const stored = ["executions", "vwap"];
        const m = migrateIndicators(stored, SEEN_BEFORE_P18, "window", LATER);
        expect(m.indicators).toBe(stored);
        expect(m.changed).toBe(true);
    });
});
