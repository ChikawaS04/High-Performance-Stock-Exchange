import { afterEach, describe, expect, it } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";

import { useChartColors } from "../src/chartColors";
import { SETTING_KEYS } from "../src/settings/persist";

// Colour settings persistence (Charts follow-up, decision 6; DESIGN_SYSTEM.md §13).

afterEach(() => {
    cleanup();
    localStorage.clear();
});

const stored = (key: string): unknown => JSON.parse(localStorage.getItem(key) ?? "null");

describe("useChartColors", () => {
    it("starts at the defaults with nothing stored", () => {
        const { result } = renderHook(() => useChartColors());
        expect(result.current.candleUp).toBe("default");
        expect(result.current.candleDown).toBe("default");
        expect(result.current.wicks).toBe("body");
        expect(result.current.lineColor).toBe("#ffb01f");
        expect(result.current.fill).toBe("line");
        expect(result.current.recent).toEqual([]);
    });

    it("round-trips every new key across a remount", () => {
        const first = renderHook(() => useChartColors());
        act(() => first.result.current.pick("up", "#0fd9b6"));
        act(() => first.result.current.pick("down", "#ff7a18"));
        act(() => first.result.current.setWicks("grey"));
        expect(stored(SETTING_KEYS.priceChartCandleUp)).toBe("#0fd9b6");
        expect(stored(SETTING_KEYS.priceChartCandleDown)).toBe("#ff7a18");
        expect(stored(SETTING_KEYS.priceChartWicks)).toBe("grey");
        expect(stored(SETTING_KEYS.priceChartRecentColors)).toEqual(["#ff7a18", "#0fd9b6"]);
        first.unmount();

        const { result } = renderHook(() => useChartColors());
        expect(result.current.candleUp).toBe("#0fd9b6");
        expect(result.current.candleDown).toBe("#ff7a18");
        expect(result.current.wicks).toBe("grey");
        expect(result.current.recent).toEqual(["#ff7a18", "#0fd9b6"]);
    });

    it("keys follow the hpse.v1.priceChart scheme", () => {
        expect(SETTING_KEYS.priceChartCandleUp).toBe("hpse.v1.priceChart.candleUp");
        expect(SETTING_KEYS.priceChartCandleDown).toBe("hpse.v1.priceChart.candleDown");
        expect(SETTING_KEYS.priceChartWicks).toBe("hpse.v1.priceChart.wicks");
        expect(SETTING_KEYS.priceChartRecentColors).toBe("hpse.v1.priceChart.recentColors");
    });

    it("falls back to the default on an invalid stored value", () => {
        localStorage.setItem(SETTING_KEYS.priceChartCandleUp, JSON.stringify("green"));
        localStorage.setItem(SETTING_KEYS.priceChartWicks, JSON.stringify("gray"));
        localStorage.setItem(SETTING_KEYS.priceChartLineColor, JSON.stringify("#ffb01"));
        localStorage.setItem(SETTING_KEYS.priceChartFill, JSON.stringify("auto"));
        localStorage.setItem(SETTING_KEYS.priceChartRecentColors, "not json");
        const { result } = renderHook(() => useChartColors());
        expect(result.current.candleUp).toBe("default");
        expect(result.current.wicks).toBe("body");
        expect(result.current.lineColor).toBe("#ffb01f");
        expect(result.current.fill).toBe("line");
        expect(result.current.recent).toEqual([]);
        expect(localStorage.getItem(SETTING_KEYS.priceChartCandleUp)).toBeNull();
    });

    it("accepts any #rrggbb line and fill colour, not only the old swatch list", () => {
        localStorage.setItem(SETTING_KEYS.priceChartLineColor, JSON.stringify("#123456"));
        localStorage.setItem(SETTING_KEYS.priceChartFill, JSON.stringify("#abcdef"));
        const { result } = renderHook(() => useChartColors());
        expect(result.current.lineColor).toBe("#123456");
        expect(result.current.fill).toBe("#abcdef");
    });

    it("keeps the last four recent picks in order without duplicates", () => {
        const { result } = renderHook(() => useChartColors());
        for (const c of ["#111111", "#222222", "#333333", "#444444", "#555555", "#333333"]) {
            act(() => result.current.pick("line", c));
        }
        expect(result.current.recent).toEqual(["#333333", "#555555", "#444444", "#222222"]);
    });

    it("Match line stores the fill keyword and does not touch Recent", () => {
        const { result } = renderHook(() => useChartColors());
        act(() => result.current.pick("fill", "#12d170"));
        act(() => result.current.pick("fill", "line"));
        expect(result.current.fill).toBe("line");
        expect(result.current.recent).toEqual(["#12d170"]);
    });

    it("Reset colors restores up, down, line, fill and wicks, and keeps Recent", () => {
        const { result } = renderHook(() => useChartColors());
        act(() => result.current.pick("up", "#0fd9b6"));
        act(() => result.current.pick("down", "#ff7a18"));
        act(() => result.current.pick("line", "#3b93ff"));
        act(() => result.current.pick("fill", "#12d170"));
        act(() => result.current.setWicks("grey"));
        act(() => result.current.resetColors());
        expect(result.current.candleUp).toBe("default");
        expect(result.current.candleDown).toBe("default");
        expect(result.current.lineColor).toBe("#ffb01f");
        expect(result.current.fill).toBe("line");
        expect(result.current.wicks).toBe("body");
        expect(result.current.recent).toHaveLength(4);
    });
});
