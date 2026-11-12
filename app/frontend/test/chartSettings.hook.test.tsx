import { afterEach, describe, expect, it } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";

import { useChartSettings } from "../src/chartSettings";

afterEach(() => {
    cleanup();
    localStorage.clear();
});

describe("useChartSettings (P17-4)", () => {
    it("returns the defaults on first run", () => {
        const { result } = renderHook(() => useChartSettings("page"));
        expect(result.current.chartType).toBe("candles");
        expect(result.current.barSize).toBe("15s");
        expect(result.current.indicators).toEqual(["volume", "vwap"]);
    });

    it("keeps separate values for the page and the window", () => {
        const page = renderHook(() => useChartSettings("page"));
        const win = renderHook(() => useChartSettings("window"));
        act(() => {
            page.result.current.setChartType("line");
            win.result.current.setBarSize("5m");
            win.result.current.setIndicators(["vwap"]);
        });
        expect(page.result.current.chartType).toBe("line");
        expect(page.result.current.barSize).toBe("15s");
        expect(page.result.current.indicators).toEqual(["volume", "vwap"]);
        expect(win.result.current.chartType).toBe("candles");
        expect(win.result.current.barSize).toBe("5m");
        expect(win.result.current.indicators).toEqual(["vwap"]);
    });

    it("survives an unmount and remount", () => {
        const first = renderHook(() => useChartSettings("window"));
        act(() => {
            first.result.current.setChartType("line");
            first.result.current.setBarSize("1m");
            first.result.current.setIndicators([]);
        });
        first.unmount();
        const again = renderHook(() => useChartSettings("window"));
        expect(again.result.current.chartType).toBe("line");
        expect(again.result.current.barSize).toBe("1m");
        expect(again.result.current.indicators).toEqual([]);
    });

    it("cleans a stored set with unknown ids without losing the key", () => {
        localStorage.setItem("hpse.v1.priceChart.indicators", JSON.stringify(["bogus", "vwap", "vwap"]));
        const { result } = renderHook(() => useChartSettings("page"));
        expect(result.current.indicators).toEqual(["vwap"]);
        expect(localStorage.getItem("hpse.v1.priceChart.indicators")).not.toBeNull();
    });

    it("falls back to the default for an unknown chart type and removes it", () => {
        localStorage.setItem("hpse.v1.priceWindow.chartType", JSON.stringify("heatmap"));
        const { result } = renderHook(() => useChartSettings("window"));
        expect(result.current.chartType).toBe("candles");
        expect(localStorage.getItem("hpse.v1.priceWindow.chartType")).toBeNull();
    });
});
