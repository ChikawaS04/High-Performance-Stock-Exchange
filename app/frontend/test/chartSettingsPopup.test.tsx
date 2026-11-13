import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { emptyBars } from "../src/bars";
import { SETTING_KEYS } from "../src/settings/persist";
import type { IndicatorDescriptor } from "../src/indicators/registry";

afterEach(() => {
    cleanup();
    localStorage.clear();
});

// Two panes plus an overlay, so the window cap of one pane can be reached (P17-0 F13).
const TWO_PANES: IndicatorDescriptor[] = [
    { id: "volume", label: "Volume", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
    { id: "flow", label: "Flow", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
    { id: "vwap", label: "VWAP", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["line", "candles"] },
];

function open(surface: "page" | "window" = "page", registry?: IndicatorDescriptor[]) {
    const r = render(<PriceChart tape={[]} bars={emptyBars()} sessionOpenPx={-1} surface={surface} registry={registry} />);
    fireEvent.click(screen.getByTestId("pc-settings-gear"));
    return r;
}

const stored = (key: string): unknown => JSON.parse(localStorage.getItem(key) ?? "null");
const row = (id: string): string => screen.getByTestId(id).className;
const pressed = (id: string): string | null => screen.getByTestId(id).getAttribute("aria-pressed");

describe("Chart type and Bar size rows (P17-9)", () => {
    it("opens on Candles with the Bar size row at 15s", () => {
        open();
        expect((screen.getByTestId("pc-type-candles") as HTMLInputElement).checked).toBe(true);
        expect((screen.getByTestId("pc-size-15s") as HTMLInputElement).checked).toBe(true);
        expect(screen.getByTestId("pc-type-row").querySelector(".settings-radio")!.getAttribute("role")).toBe("radiogroup");
    });

    it("choosing Line hides the Bar size row; choosing Candles shows it; each choice persists", () => {
        open();
        fireEvent.click(screen.getByTestId("pc-type-line"));
        expect(screen.queryByTestId("pc-size-row")).toBeNull();
        expect(stored(SETTING_KEYS.priceChartChartType)).toBe("line");
        fireEvent.click(screen.getByTestId("pc-type-candles"));
        expect(screen.getByTestId("pc-size-row")).not.toBeNull();
        expect(stored(SETTING_KEYS.priceChartChartType)).toBe("candles");
    });

    it("persists the bar size", () => {
        const { unmount } = open();
        fireEvent.click(screen.getByTestId("pc-size-5m"));
        expect(stored(SETTING_KEYS.priceChartBarSize)).toBe("5m");
        unmount();
        open();
        expect((screen.getByTestId("pc-size-5m") as HTMLInputElement).checked).toBe(true);
    });

    it("disables Line color, Area fill and Fill color in candle mode and enables them in line mode", () => {
        open();
        for (const id of ["pc-line-row", "pc-grad-row", "pc-fill-row"]) expect(row(id)).toContain("settings-pop__row--disabled");
        expect((screen.getByTestId("pc-grad-off") as HTMLButtonElement).disabled).toBe(true);
        fireEvent.click(screen.getByTestId("pc-type-line"));
        for (const id of ["pc-line-row", "pc-grad-row", "pc-fill-row"]) expect(row(id)).not.toContain("settings-pop__row--disabled");
    });

    it("keeps every P12-R test id", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        open();
        for (const id of [
            "pc-settings",
            "pc-settings-close",
            "pc-settings-done",
            "pc-settings-backdrop",
            "pc-bg-black",
            "pc-bg-white",
            "pc-grad-on",
            "pc-grad-off",
        ]) {
            expect(screen.getByTestId(id)).not.toBeNull();
        }
        // The swatch ids now live in each row's palette, opened from its chip.
        fireEvent.click(screen.getByTestId("pc-line-chip"));
        expect(screen.getByTestId("pc-line-#ffb01f")).not.toBeNull();
        fireEvent.click(screen.getByTestId("pc-fill-chip"));
        expect(screen.getByTestId("pc-fill-line")).not.toBeNull();
    });
});

describe("Indicators category (P17-9)", () => {
    it("lists the surface's indicators with On / Off toggles, On first", () => {
        open();
        const rows = screen.getByTestId("pc-settings-indicators").querySelectorAll(".settings-pop__row");
        expect(Array.from(rows).map((r) => r.querySelector(".settings-pop__name")!.firstChild!.textContent)).toEqual([
            "Volume",
            "VWAP",
        ]);
        const opts = screen.getByTestId("pc-ind-vwap-row").querySelectorAll(".settings-toggle__opt");
        expect(Array.from(opts).map((o) => o.textContent)).toEqual(["On", "Off"]);
        expect(pressed("pc-ind-vwap-on")).toBe("true");
        expect(pressed("pc-ind-volume-on")).toBe("true");
    });

    it("persists each indicator toggle", () => {
        const { unmount } = open();
        fireEvent.click(screen.getByTestId("pc-ind-vwap-off"));
        expect(stored(SETTING_KEYS.priceChartIndicators)).toEqual(["volume"]);
        fireEvent.click(screen.getByTestId("pc-ind-volume-off"));
        expect(stored(SETTING_KEYS.priceChartIndicators)).toEqual([]);
        unmount();
        open();
        expect(pressed("pc-ind-vwap-off")).toBe("true");
        fireEvent.click(screen.getByTestId("pc-ind-vwap-on"));
        expect(stored(SETTING_KEYS.priceChartIndicators)).toEqual(["vwap"]);
    });

    it("disables the volume toggle in line mode with Candles only, keeping its stored state", () => {
        open();
        fireEvent.click(screen.getByTestId("pc-type-line"));
        expect(row("pc-ind-volume-row")).toContain("settings-pop__row--disabled");
        expect(screen.getByTestId("pc-ind-volume-note").textContent).toBe("Candles only");
        expect((screen.getByTestId("pc-ind-volume-on") as HTMLButtonElement).disabled).toBe(true);
        expect(pressed("pc-ind-volume-on")).toBe("true");
        expect(row("pc-ind-vwap-row")).not.toContain("settings-pop__row--disabled");
        fireEvent.click(screen.getByTestId("pc-type-candles"));
        expect(row("pc-ind-volume-row")).not.toContain("settings-pop__row--disabled");
        expect(stored(SETTING_KEYS.priceChartIndicators)).toBeNull(); // never rewritten
    });

    it("on the window, with one pane on, disables the other pane toggles and shows the limit line", () => {
        open("window", TWO_PANES);
        expect(pressed("pc-ind-volume-on")).toBe("true");
        expect(row("pc-ind-flow-row")).toContain("settings-pop__row--disabled");
        expect((screen.getByTestId("pc-ind-flow-on") as HTMLButtonElement).disabled).toBe(true);
        expect(screen.getByTestId("pc-pane-limit").textContent).toBe("Pane limit reached (1)");
        // Overlays are never disabled by the cap; the pane that is on can still be turned off.
        expect(row("pc-ind-vwap-row")).not.toContain("settings-pop__row--disabled");
        expect((screen.getByTestId("pc-ind-volume-off") as HTMLButtonElement).disabled).toBe(false);
        fireEvent.click(screen.getByTestId("pc-ind-volume-off"));
        expect(screen.queryByTestId("pc-pane-limit")).toBeNull();
        expect(row("pc-ind-flow-row")).not.toContain("settings-pop__row--disabled");
    });

    it("does not show the limit line on the page under its cap of three", () => {
        open("page", TWO_PANES);
        expect(screen.queryByTestId("pc-pane-limit")).toBeNull();
        expect(row("pc-ind-flow-row")).not.toContain("settings-pop__row--disabled");
    });

    it("never shows the limit line with the real one-pane registry", () => {
        open("window");
        expect(screen.queryByTestId("pc-pane-limit")).toBeNull();
    });
});
