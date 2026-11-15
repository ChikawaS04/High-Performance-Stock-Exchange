import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { PriceChartOverlay } from "../src/components/PriceChartOverlay";
import { emptyBars } from "../src/bars";
import { SETTING_KEYS } from "../src/settings/persist";
import { resetLastFolders } from "../src/settings/chartSettingsTree";
import type { IndicatorDescriptor } from "../src/indicators/registry";
import { INDICATOR_FOLDERS, folderButton, openFolder, shownFolder } from "./settingsFolders";

afterEach(() => {
    cleanup();
    localStorage.clear();
    resetLastFolders();
});

// Two panes plus an overlay, so the window cap of one pane can be reached (P17-0 F13).
// "flow" is in no indicator group, so it lands in the Other folder.
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
const escape = (target: Element | Document = document.activeElement ?? document.body): void => {
    fireEvent.keyDown(target, { key: "Escape" });
};

describe("Chart type and Bar size rows (P17-9)", () => {
    it("opens on Display > Chart, on Candles with the Bar size row at 15s", () => {
        open();
        expect(shownFolder()).toBe("display/chart");
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

    it("no longer stacks the chart type or bar size rows: the landscape pane fits them", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("footprint"));
        open();
        expect(row("pc-type-row")).toBe("settings-pop__row");
        expect(row("pc-size-row")).toBe("settings-pop__row");
    });

    it("disables Line color, Area fill and Fill color in candle mode and enables them in line mode", () => {
        open();
        openFolder("display/line");
        for (const id of ["pc-line-row", "pc-grad-row", "pc-fill-row"]) expect(row(id)).toContain("settings-pop__row--disabled");
        expect((screen.getByTestId("pc-grad-off") as HTMLButtonElement).disabled).toBe(true);
        openFolder("display/chart");
        fireEvent.click(screen.getByTestId("pc-type-line"));
        openFolder("display/line");
        for (const id of ["pc-line-row", "pc-grad-row", "pc-fill-row"]) expect(row(id)).not.toContain("settings-pop__row--disabled");
    });

    it("keeps every P12-R test id", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        open();
        for (const id of ["pc-settings", "pc-settings-close", "pc-settings-done", "pc-settings-backdrop", "pc-bg-black", "pc-bg-white"]) {
            expect(screen.getByTestId(id)).not.toBeNull();
        }
        openFolder("display/line");
        for (const id of ["pc-grad-on", "pc-grad-off"]) expect(screen.getByTestId(id)).not.toBeNull();
        // The swatch ids now live in each row's palette, opened from its chip.
        fireEvent.click(screen.getByTestId("pc-line-chip"));
        expect(screen.getByTestId("pc-line-#ffb01f")).not.toBeNull();
        fireEvent.click(screen.getByTestId("pc-fill-chip"));
        expect(screen.getByTestId("pc-fill-line")).not.toBeNull();
    });
});

describe("Indicators folders (P17-9)", () => {
    it("lists the surface's indicators across the Price, Volume and Trading folders, On first", () => {
        open();
        const names: string[] = [];
        for (const f of INDICATOR_FOLDERS) {
            openFolder(f);
            const rows = screen.getByTestId("pc-settings-indicators").querySelectorAll(".settings-pop__row");
            names.push(...Array.from(rows).map((r) => r.querySelector(".settings-pop__name")!.firstChild!.textContent!));
        }
        expect(names).toEqual([
            "VWAP",
            "SMA",
            "SMA 2",
            "EMA",
            "EMA 2",
            "Bollinger",
            "Volume",
            "Volume profile",
            "CVD",
            "Executions",
            "Orders",
            "Bid/Ask",
        ]);
        openFolder("indicators/price");
        const opts = screen.getByTestId("pc-ind-vwap-row").querySelectorAll(".settings-toggle__opt");
        expect(Array.from(opts).map((o) => o.textContent)).toEqual(["On", "Off"]);
        expect(pressed("pc-ind-vwap-on")).toBe("true");
    });

    it("shows each group's count of indicators on in the Indicators listing", () => {
        open();
        openFolder("indicators");
        expect(screen.getByTestId("pc-entry-indicators-price").textContent).toContain("1 on");
        expect(screen.getByTestId("pc-entry-indicators-volume").textContent).toContain("1 on");
        expect(screen.getByTestId("pc-entry-indicators-trading").textContent).toContain("2 on");
    });

    it("persists each indicator toggle", () => {
        const { unmount } = open();
        openFolder("indicators/price");
        fireEvent.click(screen.getByTestId("pc-ind-vwap-off"));
        expect(stored(SETTING_KEYS.priceChartIndicators)).toEqual(["volume", "executions", "orders"]);
        openFolder("indicators/volume");
        fireEvent.click(screen.getByTestId("pc-ind-volume-off"));
        openFolder("indicators/trading");
        fireEvent.click(screen.getByTestId("pc-ind-executions-off"));
        fireEvent.click(screen.getByTestId("pc-ind-orders-off"));
        expect(stored(SETTING_KEYS.priceChartIndicators)).toEqual([]);
        unmount();
        open();
        // It reopens on the last folder viewed: Trading.
        expect(shownFolder()).toBe("indicators/trading");
        expect(pressed("pc-ind-executions-off")).toBe("true");
        openFolder("indicators/price");
        expect(pressed("pc-ind-vwap-off")).toBe("true");
        fireEvent.click(screen.getByTestId("pc-ind-vwap-on"));
        expect(stored(SETTING_KEYS.priceChartIndicators)).toEqual(["vwap"]);
    });

    it("disables the volume toggle in line mode with Bar charts only, keeping its stored state", () => {
        open();
        fireEvent.click(screen.getByTestId("pc-type-line"));
        openFolder("indicators/volume");
        expect(row("pc-ind-volume-row")).toContain("settings-pop__row--disabled");
        expect(screen.getByTestId("pc-ind-volume-note").textContent).toBe("Bar charts only");
        expect((screen.getByTestId("pc-ind-volume-on") as HTMLButtonElement).disabled).toBe(true);
        expect(pressed("pc-ind-volume-on")).toBe("true");
        openFolder("indicators/price");
        expect(row("pc-ind-vwap-row")).not.toContain("settings-pop__row--disabled");
        openFolder("display/chart");
        fireEvent.click(screen.getByTestId("pc-type-candles"));
        openFolder("indicators/volume");
        expect(row("pc-ind-volume-row")).not.toContain("settings-pop__row--disabled");
        expect(stored(SETTING_KEYS.priceChartIndicators)).toBeNull(); // never rewritten
    });

    it("on the window, with one pane on, disables the other pane toggles and shows the limit line", () => {
        open("window", TWO_PANES);
        openFolder("indicators/other");
        expect(row("pc-ind-flow-row")).toContain("settings-pop__row--disabled");
        expect((screen.getByTestId("pc-ind-flow-on") as HTMLButtonElement).disabled).toBe(true);
        expect(screen.getByTestId("pc-pane-limit").textContent).toBe("Pane limit reached (1)");
        // Overlays are never disabled by the cap; the pane that is on can still be turned off.
        openFolder("indicators/price");
        expect(row("pc-ind-vwap-row")).not.toContain("settings-pop__row--disabled");
        expect(screen.queryByTestId("pc-pane-limit")).toBeNull(); // no pane of this folder is held off
        openFolder("indicators/volume");
        expect(pressed("pc-ind-volume-on")).toBe("true");
        expect((screen.getByTestId("pc-ind-volume-off") as HTMLButtonElement).disabled).toBe(false);
        fireEvent.click(screen.getByTestId("pc-ind-volume-off"));
        openFolder("indicators/other");
        expect(screen.queryByTestId("pc-pane-limit")).toBeNull();
        expect(row("pc-ind-flow-row")).not.toContain("settings-pop__row--disabled");
    });

    it("does not show the limit line on the page under its cap of three", () => {
        open("page", TWO_PANES);
        openFolder("indicators/other");
        expect(screen.queryByTestId("pc-pane-limit")).toBeNull();
        expect(row("pc-ind-flow-row")).not.toContain("settings-pop__row--disabled");
    });

    it("never shows the limit line with the real one-pane registry", () => {
        open("window");
        openFolder("indicators/volume");
        expect(screen.queryByTestId("pc-pane-limit")).toBeNull();
    });
});

describe("the settings view: placement and closing", () => {
    it("is portalled into the page body and centred on both surfaces, with no scrim", () => {
        for (const surface of ["page", "window"] as const) {
            open(surface);
            const dialog = screen.getByTestId("pc-settings");
            const backdrop = screen.getByTestId("pc-settings-backdrop");
            expect(dialog.parentElement).toBe(document.body);
            expect(backdrop.parentElement).toBe(document.body);
            expect(dialog.className).toContain("settings-pop--view");
            expect(backdrop.className).toContain("settings-pop__backdrop--view");
            expect(dialog.getAttribute("data-surface")).toBe(surface);
            // No scrim element or class anywhere in the view.
            expect(document.querySelector("[class*='scrim']")).toBeNull();
            cleanup();
        }
    });

    it("closes on Escape, the ×, Done and an outside click", () => {
        for (const how of ["escape", "x", "done", "outside"] as const) {
            open();
            if (how === "escape") escape();
            if (how === "x") fireEvent.click(screen.getByTestId("pc-settings-close"));
            if (how === "done") fireEvent.click(screen.getByTestId("pc-settings-done"));
            if (how === "outside") fireEvent.click(screen.getByTestId("pc-settings-backdrop"));
            expect(screen.queryByTestId("pc-settings"), how).toBeNull();
            expect(screen.queryByTestId("pc-settings-backdrop"), how).toBeNull();
            cleanup();
        }
    });

    it("Escape with a palette open closes only the palette", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        open();
        openFolder("display/line");
        fireEvent.click(screen.getByTestId("pc-line-chip"));
        expect(screen.getByTestId("pc-line-palette")).not.toBeNull();
        escape(screen.getByTestId("pc-line-hex"));
        expect(screen.queryByTestId("pc-line-palette")).toBeNull();
        expect(screen.getByTestId("pc-settings")).not.toBeNull();
    });

    it("renders the palette in the dialog's layer, outside the scrolling content", () => {
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        open();
        openFolder("display/line");
        fireEvent.click(screen.getByTestId("pc-line-chip"));
        const pal = screen.getByTestId("pc-line-palette");
        expect(pal.parentElement!.className).toBe("settings-view__layer");
        expect(screen.getByTestId("pc-settings-content").contains(pal)).toBe(false);
        expect(screen.getByTestId("pc-settings").contains(pal)).toBe(true);
    });

    it("keeps the header and footer outside the scrolling content", () => {
        open();
        const content = screen.getByTestId("pc-settings-content");
        const dialog = screen.getByTestId("pc-settings");
        expect(content.className).toContain("term-scroll");
        expect(screen.getByTestId("pc-settings-tree").className).toContain("term-scroll");
        for (const id of ["pc-settings-done", "pc-reset-colors", "pc-settings-close", "pc-settings-crumbs"]) {
            expect(content.contains(screen.getByTestId(id)), id).toBe(false);
        }
        const foot = screen.getByTestId("pc-settings-done").closest(".settings-pop__foot")!;
        expect(foot.parentElement).toBe(dialog);
        expect(dialog.querySelector(".settings-pop__head")!.parentElement).toBe(dialog);
    });
});

describe("the settings view inside the floating window", () => {
    const renderWindow = (onClose: () => void, isOpen = true) => (
        <PriceChartOverlay open={isOpen} onClose={onClose} tape={[]} bars={emptyBars()} sessionOpenPx={-1} />
    );

    it("Escape closes the view, not the floating window", () => {
        const onClose = vi.fn();
        render(renderWindow(onClose));
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        escape();
        expect(screen.queryByTestId("pc-settings")).toBeNull();
        expect(onClose).not.toHaveBeenCalled();
        // With the view closed, Escape reaches the window as before.
        escape();
        expect(onClose).toHaveBeenCalledTimes(1);
    });

    it("Escape with a parameter FieldMenu open closes only the menu, then the view, never the window", () => {
        const onClose = vi.fn();
        render(renderWindow(onClose));
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        openFolder("indicators/price");
        fireEvent.click(screen.getByTestId("pc-ind-sma-params"));
        const input = screen.getByTestId("pc-param-sma-length-input");
        fireEvent.click(input);
        expect(screen.getByRole("listbox")).not.toBeNull();
        escape(input);
        expect(screen.queryByRole("listbox")).toBeNull();
        expect(screen.getByTestId("pc-settings")).not.toBeNull();
        expect(onClose).not.toHaveBeenCalled();
        escape(input);
        expect(screen.queryByTestId("pc-settings")).toBeNull();
        expect(onClose).not.toHaveBeenCalled();
    });

    it("closing the floating window with the view open removes the view and its backdrop cleanly", () => {
        const onClose = vi.fn();
        const before = document.body.childElementCount;
        const { rerender } = render(renderWindow(onClose));
        const withWindow = document.body.childElementCount;
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect(document.body.childElementCount).toBe(withWindow + 2); // backdrop and dialog
        expect(() => rerender(renderWindow(onClose, false))).not.toThrow();
        expect(screen.queryByTestId("pc-settings")).toBeNull();
        expect(screen.queryByTestId("pc-settings-backdrop")).toBeNull();
        expect(document.querySelector(".settings-pop--view, .settings-pop__backdrop--view, .settings-view__layer")).toBeNull();
        // Only the test's own render container is left in the body.
        expect(document.body.childElementCount).toBe(before + 1);
    });
});

describe("the settings view: folders and breadcrumbs", () => {
    const crumbLabels = (): string[] =>
        Array.from(screen.getByTestId("pc-settings-crumbs").querySelectorAll("button")).map((b) => b.textContent!);

    it("selects a folder from the tree and shows the trail", () => {
        open();
        openFolder("display/candles");
        expect(shownFolder()).toBe("display/candles");
        expect(crumbLabels()).toEqual(["Settings", "Display", "Candles"]);
        expect(folderButton("display/candles").getAttribute("aria-current")).toBe("true");
        expect(screen.getByTestId("pc-crumb-2").getAttribute("aria-current")).toBe("page");
        expect(screen.getByTestId("pc-up-row")).not.toBeNull();
    });

    it("goes up a level from a crumb and down again from a folder entry", () => {
        open();
        openFolder("display/candles");
        fireEvent.click(screen.getByTestId("pc-crumb-1"));
        expect(shownFolder()).toBe("display");
        expect(crumbLabels()).toEqual(["Settings", "Display"]);
        for (const e of ["display-chart", "display-candles", "display-line", "display-heatmap"]) {
            expect(screen.getByTestId(`pc-entry-${e}`)).not.toBeNull();
        }
        fireEvent.click(screen.getByTestId("pc-entry-display-line"));
        expect(shownFolder()).toBe("display/line");
        fireEvent.click(screen.getByTestId("pc-crumb-0"));
        expect(shownFolder()).toBe("");
        expect(screen.getByTestId("pc-entry-display")).not.toBeNull();
        expect(screen.getByTestId("pc-entry-indicators")).not.toBeNull();
    });

    it("hides the Heatmap folder on the window and dims it outside heatmap mode on the page", () => {
        open("window");
        expect(screen.queryByTestId("pc-folder-display-heatmap")).toBeNull();
        cleanup();
        resetLastFolders();
        open("page");
        const heat = folderButton("display/heatmap");
        expect(heat.className).toContain("settings-view__node--dim");
        // Dim folders stay navigable; their rows show disabled.
        fireEvent.click(heat);
        expect(shownFolder()).toBe("display/heatmap");
        expect(row("pc-contrast-row")).toContain("settings-pop__row--disabled");
        openFolder("display");
        expect(screen.getByTestId("pc-entry-display-heatmap").textContent).toContain("Heatmap only");
    });

    it("dims the Candles and Volume folders in line mode and lifts them in candles", () => {
        open("window");
        fireEvent.click(screen.getByTestId("pc-type-line"));
        expect(folderButton("display/candles").className).toContain("settings-view__node--dim");
        expect(folderButton("indicators/volume").className).toContain("settings-view__node--dim");
        expect(folderButton("display/line").className).not.toContain("settings-view__node--dim");
        fireEvent.click(screen.getByTestId("pc-type-candles"));
        expect(folderButton("display/candles").className).not.toContain("settings-view__node--dim");
    });

    it("reopens at the last folder viewed, per surface", () => {
        const page = open("page");
        openFolder("display/line");
        fireEvent.click(screen.getByTestId("pc-settings-done"));
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect(shownFolder()).toBe("display/line");
        page.unmount();
        // The window has its own memory, still at the default.
        const win = open("window");
        expect(shownFolder()).toBe("display/chart");
        openFolder("indicators/trading");
        win.unmount();
        open("page");
        expect(shownFolder()).toBe("display/line");
    });

    it("falls back to the nearest folder that exists when the last one is hidden", () => {
        // An Other folder exists only while an ungrouped indicator is offered.
        const page = open("page", TWO_PANES);
        openFolder("indicators/other");
        page.unmount();
        open("page");
        expect(shownFolder()).toBe("indicators");
    });
});

describe("the settings view: keyboard", () => {
    it("moves focus to the current folder on open and back to the gear on close", () => {
        open();
        expect(document.activeElement).toBe(folderButton("display/chart"));
        fireEvent.click(screen.getByTestId("pc-settings-done"));
        expect(document.activeElement).toBe(screen.getByTestId("pc-settings-gear"));
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        escape();
        expect(document.activeElement).toBe(screen.getByTestId("pc-settings-gear"));
    });

    it("makes every tree folder and crumb a button", () => {
        open();
        openFolder("display/candles");
        const tree = screen.getByTestId("pc-settings-tree");
        const nodes = Array.from(tree.children);
        expect(nodes.length).toBe(9);
        for (const n of nodes) expect(n.tagName).toBe("BUTTON");
        for (const c of Array.from(screen.getByTestId("pc-settings-crumbs").querySelectorAll("[data-testid^='pc-crumb-']"))) {
            expect(c.tagName).toBe("BUTTON");
        }
    });
});
