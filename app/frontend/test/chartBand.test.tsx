import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import { PriceChartOverlay } from "../src/components/PriceChartOverlay";
import { FieldMenu } from "../src/components/FieldMenu";
import { emptyBars } from "../src/bars";
import { SETTING_KEYS } from "../src/settings/persist";
import type { IndicatorDescriptor } from "../src/indicators/registry";

// The price chart band toolbar (Charts follow-up, decisions 1 and 2).

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    localStorage.clear();
});

const stored = (key: string): unknown => JSON.parse(localStorage.getItem(key) ?? "null");

function page(onView = vi.fn(), registry?: IndicatorDescriptor[]) {
    render(
        <PriceChart tape={[]} bars={emptyBars()} sessionOpenPx={-1} onViewInTrading={onView} registry={registry} />,
    );
    return onView;
}

describe("page band", () => {
    it("reads View, chart type, bar size, Indicators and the gear, in order", () => {
        page();
        const controls = document.querySelector(".price-chart__controls")!;
        const order = Array.from(controls.querySelectorAll("[data-testid]"))
            .map((el) => el.getAttribute("data-testid"))
            .filter((id) => ["pc-view-caret", "pc-band-type-trigger", "pc-band-size-trigger", "pc-ind-button", "pc-settings-gear"].includes(id!));
        expect(order).toEqual(["pc-view-caret", "pc-band-type-trigger", "pc-band-size-trigger", "pc-ind-button", "pc-settings-gear"]);
        expect(screen.getByTestId("pc-view-caret").textContent).toContain("View");
        expect(screen.getByTestId("pc-view-caret").className).toContain("chart-btn");
        expect(screen.getByTestId("pc-ind-button").textContent).toBe("Indicators");
        expect(controls.querySelectorAll(".price-chart__divider")).toHaveLength(2);
    });

    it("the View button opens the white menu and View in trading still works", () => {
        const onView = page();
        fireEvent.click(screen.getByTestId("pc-view-caret"));
        expect(screen.getByTestId("pc-view-caret").getAttribute("aria-expanded")).toBe("true");
        const menu = screen.getByTestId("pc-view-menu");
        expect(menu.getAttribute("role")).toBe("menu");
        expect(menu.querySelectorAll('[role="menuitem"]')).toHaveLength(1);
        fireEvent.click(screen.getByTestId("pc-view-trading"));
        expect(onView).toHaveBeenCalledTimes(1);
        expect(screen.queryByTestId("pc-view-menu")).toBeNull();
    });

    it("the chart type trigger shows the current type's icon and name; each option carries its icon", () => {
        page();
        const trigger = screen.getByTestId("pc-band-type-trigger");
        expect(trigger.textContent).toBe("Candles");
        expect(trigger.querySelector('svg[data-icon="candles"]')).not.toBeNull();
        expect(trigger.querySelector("svg")!.getAttribute("aria-hidden")).toBe("true");
        fireEvent.click(trigger);
        const candles = screen.getByTestId("pc-band-type-opt-candles");
        const line = screen.getByTestId("pc-band-type-opt-line");
        expect(candles.textContent).toBe("Candles");
        expect(line.textContent).toBe("Line");
        expect(candles.querySelector('svg[data-icon="candles"]')).not.toBeNull();
        expect(line.querySelector('svg[data-icon="line"]')).not.toBeNull();
        expect(line.querySelector("svg")!.getAttribute("aria-hidden")).toBe("true");
    });

    it("choosing Line persists, hides the bar size and agrees with the gear popup", () => {
        page();
        fireEvent.click(screen.getByTestId("pc-band-type-trigger"));
        fireEvent.mouseDown(screen.getByTestId("pc-band-type-opt-line"));
        expect(stored(SETTING_KEYS.priceChartChartType)).toBe("line");
        expect(screen.queryByTestId("pc-band-size-trigger")).toBeNull();
        expect(screen.getByTestId("pc-band-type-trigger").textContent).toBe("Line");
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect((screen.getByTestId("pc-type-line") as HTMLInputElement).checked).toBe(true);
    });

    it("bar size options are mono and right-aligned, and a pick persists", () => {
        page();
        fireEvent.click(screen.getByTestId("pc-band-size-trigger"));
        const opt = screen.getByTestId("pc-band-size-opt-1m");
        expect(opt.className).toContain("field-menu__opt--num");
        fireEvent.mouseDown(opt);
        expect(stored(SETTING_KEYS.priceChartBarSize)).toBe("1m");
        expect(screen.getByTestId("pc-band-size-trigger").textContent).toBe("1m");
    });

    it("the Indicators popup and the gear's Indicators category share state", () => {
        page();
        fireEvent.click(screen.getByTestId("pc-ind-button"));
        const pop = screen.getByTestId("pc-ind-pop");
        expect(pop.className).toContain("settings-pop");
        fireEvent.click(screen.getByTestId("pc-ind-vwap-off"));
        expect(stored(SETTING_KEYS.priceChartIndicators)).toEqual(["volume", "executions", "orders"]);
        fireEvent.click(screen.getByTestId("pc-ind-pop-close"));
        expect(screen.queryByTestId("pc-ind-pop")).toBeNull();
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect(screen.getByTestId("pc-ind-vwap-off").getAttribute("aria-pressed")).toBe("true");
        fireEvent.click(screen.getByTestId("pc-ind-vwap-on"));
        fireEvent.click(screen.getByTestId("pc-settings-close"));
        fireEvent.click(screen.getByTestId("pc-ind-button"));
        expect(screen.getByTestId("pc-ind-vwap-on").getAttribute("aria-pressed")).toBe("true");
    });

    it("the Indicators popup carries the pane-cap note", () => {
        const TWO: IndicatorDescriptor[] = [
            { id: "volume", label: "Volume", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
            { id: "flow", label: "Flow", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
        ];
        render(<PriceChart tape={[]} bars={emptyBars()} sessionOpenPx={-1} surface="window" registry={TWO} />);
        fireEvent.click(screen.getByTestId("pc-ind-button"));
        expect(screen.getByTestId("pc-pane-limit").textContent).toBe("Pane limit reached (1)");
    });

    it("opens one band popup at a time", () => {
        page();
        fireEvent.click(screen.getByTestId("pc-ind-button"));
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect(screen.queryByTestId("pc-ind-pop")).toBeNull();
        expect(screen.getByTestId("pc-settings")).not.toBeNull();
    });
});

describe("floating window band", () => {
    it("is compact: no View, icon-only chart type and Indicators with aria-labels, bar size, gear and close", () => {
        render(<PriceChartOverlay open onClose={vi.fn()} tape={[]} bars={emptyBars()} sessionOpenPx={-1} />);
        expect(screen.queryByTestId("pc-view-caret")).toBeNull();
        const type = screen.getByTestId("pc-band-type-trigger");
        expect(type.textContent).toBe("");
        expect(type.getAttribute("aria-label")).toBe("Chart type: Candles");
        expect(type.querySelector('svg[data-icon="candles"]')).not.toBeNull();
        const ind = screen.getByTestId("pc-ind-button");
        expect(ind.textContent).toBe("");
        expect(ind.getAttribute("aria-label")).toBe("Indicators");
        expect(screen.getByTestId("pc-band-size-trigger").textContent).toBe("15s");
        expect(screen.getByTestId("pc-settings-gear")).not.toBeNull();
        expect(screen.getByTestId("pc-close")).not.toBeNull();
        expect(document.querySelector(".price-chart__band--compact")).not.toBeNull();
    });
});

describe("dropdown proportions (FieldMenu)", () => {
    function stubRects(trigger: { left: number; width: number }, panelRight: number, listWidth: number): void {
        vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
            const isPanel = this.matches("[data-menu-boundary], .price-chart, .panel");
            const left = isPanel ? 0 : trigger.left;
            const width = isPanel ? panelRight : trigger.width;
            return { left, width, right: left + width, top: 40, bottom: 62, height: 22, x: left, y: 40, toJSON: () => ({}) } as DOMRect;
        });
        vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(listWidth);
    }

    const menu = () => (
        <div className="panel">
            <FieldMenu
                testId="m"
                ariaLabel="Size"
                compact
                options={[
                    { value: "a", label: "A" },
                    { value: "b", label: "Bee" },
                ]}
                value="a"
                onSelect={vi.fn()}
            />
        </div>
    );

    it("is at least its trigger's width, no fixed width, opening under it with left edges aligned", () => {
        stubRects({ left: 100, width: 60 }, 1000, 40);
        render(menu());
        fireEvent.click(screen.getByTestId("m-trigger"));
        const list = screen.getByRole("listbox");
        expect(list.style.minWidth).toBe("60px");
        expect(list.style.width).toBe("");
        expect(list.style.left).toBe("100px");
        expect(list.style.right).toBe("");
        expect(list.style.top).toBe("64px");
    });

    it("aligns its right edge to the trigger's near the panel's right edge", () => {
        stubRects({ left: 950, width: 40 }, 1000, 120);
        render(menu());
        fireEvent.click(screen.getByTestId("m-trigger"));
        const list = screen.getByRole("listbox");
        expect(list.style.left).toBe("");
        expect(list.style.right).toBe(`${window.innerWidth - 990}px`);
    });
});
