import { describe, expect, it } from "vitest";

import {
    CHART_TYPES,
    PAGE_CHART_TYPES,
    barSizeFor,
    chartTypeValidator,
    chartTypesFor,
    isBarChart,
    isChartType,
    isPageChartType,
} from "../src/chartSettings";
import { cellText, compactQty } from "../src/footprint";
import { INDICATORS, availableIndicators } from "../src/indicators/registry";
import { typeNote } from "../src/components/IndicatorRows";

describe("footprint chart type settings (P19-6)", () => {
    it("is offered on the Charts page only", () => {
        // P20-5 adds the heatmap, also page only.
        expect(chartTypesFor("page")).toEqual(["line", "candles", "footprint", "heatmap"]);
        expect(chartTypesFor("window")).toEqual(["line", "candles"]);
        expect(PAGE_CHART_TYPES).toContain("footprint");
        expect(CHART_TYPES).not.toContain("footprint");
    });

    it("the window's validator rejects a stored footprint, the page's accepts it", () => {
        expect(isChartType("footprint")).toBe(false);
        expect(isPageChartType("footprint")).toBe(true);
        expect(chartTypeValidator("window")("footprint")).toBe(false);
        expect(chartTypeValidator("page")("footprint")).toBe(true);
    });

    it("reads 5s and 15s as 1m in footprint and leaves other types alone", () => {
        expect(barSizeFor("footprint", "5s")).toBe("1m");
        expect(barSizeFor("footprint", "15s")).toBe("1m");
        expect(barSizeFor("footprint", "1m")).toBe("1m");
        expect(barSizeFor("footprint", "5m")).toBe("5m");
        expect(barSizeFor("candles", "5s")).toBe("5s");
        expect(barSizeFor("line", "15s")).toBe("15s");
    });

    it("treats candles and footprint as bar charts", () => {
        expect(isBarChart("candles")).toBe(true);
        expect(isBarChart("footprint")).toBe(true);
        expect(isBarChart("line")).toBe(false);
    });

    it("offers every existing indicator in footprint on the page", () => {
        expect(availableIndicators("page", "footprint").map((d) => d.id)).toEqual(INDICATORS.map((d) => d.id));
    });

    it("notes where a blocked indicator draws", () => {
        const volume = INDICATORS.find((d) => d.id === "volume")!;
        expect(typeNote(volume)).toBe("Bar charts only");
        expect(typeNote({ ...volume, chartTypes: ["candles"] })).toBe("Candles only");
        expect(typeNote({ ...volume, chartTypes: ["footprint"] })).toBe("Footprint only");
    });
});

describe("footprint cell text (P19-6)", () => {
    it("keeps quantities short enough for a 64px column", () => {
        expect(compactQty(0)).toBe("0");
        expect(compactQty(9_999)).toBe("9999");
        expect(compactQty(10_000)).toBe("10K");
        expect(compactQty(999_999)).toBe("999K");
        expect(compactQty(1_250_000)).toBe("1.2M");
        expect(cellText({ sell: 1_234, buy: 56 })).toBe("1234 x 56");
        expect(cellText({ sell: 9_999, buy: 9_999 }).length).toBeLessThanOrEqual(11);
    });
});
