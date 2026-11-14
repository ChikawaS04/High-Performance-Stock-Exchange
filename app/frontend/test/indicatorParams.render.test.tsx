import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { emptyBars } from "../src/bars";
import type { IndicatorDescriptor } from "../src/indicators/registry";
import { INDICATORS } from "../src/indicators/registry";
import { SETTING_KEYS } from "../src/settings/persist";

// P19-7: indicator parameters in the gear popup. A fixture registry carries the
// params, since the shipped indicators that have them arrive in P19-8 and P19-9.

afterEach(() => {
    cleanup();
    localStorage.clear();
});

const FIXTURE: readonly IndicatorDescriptor[] = [
    ...INDICATORS,
    {
        id: "band",
        label: "Band",
        placement: "overlay",
        surfaces: ["page", "window"],
        chartTypes: ["candles"],
        params: [
            { id: "length", label: "Length", kind: "int", min: 2, max: 200, default: 20, presets: [10, 20, 50] },
            { id: "width", label: "Std dev", kind: "int", min: 5, max: 50, default: 20, presets: [10, 15, 20, 25, 30], display: "tenths" },
        ],
    },
];

function chart(surface: "page" | "window" = "page") {
    return render(<PriceChart tape={[]} bars={emptyBars()} sessionOpenPx={-1} surface={surface} registry={FIXTURE} />);
}

const stored = (key: string): unknown => JSON.parse(localStorage.getItem(key) ?? "null");
const input = (id: string): HTMLInputElement => screen.getByTestId(`pc-param-band-${id}-input`) as HTMLInputElement;

function openParams(): void {
    fireEvent.click(screen.getByTestId("pc-settings-gear"));
    fireEvent.click(screen.getByTestId("pc-ind-band-params"));
}

describe("indicator parameters (P19-7)", () => {
    it("shows a disclosure only on indicators with params, collapsed", () => {
        chart();
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect(screen.getByTestId("pc-ind-band-params").textContent).toBe("▸");
        expect(screen.queryByTestId("pc-ind-vwap-params")).toBeNull();
        expect(screen.queryByTestId("pc-param-band-length-row")).toBeNull();
    });

    it("expands to one row per param with the stored or default value", () => {
        chart();
        openParams();
        expect(screen.getByTestId("pc-ind-band-params").textContent).toBe("▾");
        expect(screen.getByTestId("pc-ind-band-params").getAttribute("aria-expanded")).toBe("true");
        expect(input("length").value).toBe("20");
        expect(input("width").value).toBe("2.0");
    });

    it("the band's Indicators popup has no parameter disclosure", () => {
        chart();
        fireEvent.click(screen.getByTestId("pc-ind-button"));
        expect(screen.getByTestId("pc-ind-band-row")).toBeTruthy();
        expect(screen.queryByTestId("pc-ind-band-params")).toBeNull();
    });

    it("stores a typed valid value", () => {
        chart();
        openParams();
        fireEvent.change(input("length"), { target: { value: "50" } });
        expect(stored(SETTING_KEYS.priceChartIndicatorParams)).toEqual({ band: { length: 50 } });
        fireEvent.change(input("width"), { target: { value: "2.5" } });
        expect(stored(SETTING_KEYS.priceChartIndicatorParams)).toEqual({ band: { length: 50, width: 25 } });
    });

    it("does not store an invalid draft and shows the stored value again on blur", () => {
        chart();
        openParams();
        fireEvent.change(input("length"), { target: { value: "500" } });
        expect(localStorage.getItem(SETTING_KEYS.priceChartIndicatorParams)).toBeNull();
        expect(input("length").value).toBe("500");
        fireEvent.blur(input("length"));
        expect(input("length").value).toBe("20");
    });

    it("stores a preset picked from the list", () => {
        chart();
        openParams();
        fireEvent.focus(input("width"));
        fireEvent.mouseDown(screen.getByTestId("pc-param-band-width-opt-1.5"));
        expect(stored(SETTING_KEYS.priceChartIndicatorParams)).toEqual({ band: { width: 15 } });
        expect(input("width").value).toBe("1.5");
    });

    it("an invalid stored value falls back to the default and the others survive", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicatorParams, JSON.stringify({ band: { length: 50, width: 999 } }));
        chart();
        openParams();
        expect(input("length").value).toBe("50");
        expect(input("width").value).toBe("2.0");
    });

    it("keeps each surface's parameters apart", () => {
        localStorage.setItem(SETTING_KEYS.priceChartIndicatorParams, JSON.stringify({ band: { length: 50 } }));
        chart("window");
        openParams();
        expect(input("length").value).toBe("20");
        fireEvent.change(input("length"), { target: { value: "30" } });
        expect(stored(SETTING_KEYS.priceWindowIndicatorParams)).toEqual({ band: { length: 30 } });
        expect(stored(SETTING_KEYS.priceChartIndicatorParams)).toEqual({ band: { length: 50 } });
    });
});
