/**
 * Indicator registry (P17-5, P17 D6).
 *
 * One descriptor per indicator: where it draws (an overlay on the price area or a
 * lower pane), which chart surfaces offer it, and which chart types can draw it.
 * Phase 17 registers volume (a pane, candles only, since volume per bar needs bars)
 * and VWAP (an overlay, both chart types). Later phases add descriptors here, and a
 * params field arrives with its first consumer (Phase 19).
 *
 * Overlays are unlimited. Lower panes are capped per surface (PANE_CAP), because
 * past that the price area gets too short to read. Every function that reads the
 * registry takes the descriptor list as a parameter, defaulting to INDICATORS, so a
 * test can pass a fixture with two or more panes (P17-0 F13): with the real
 * registry the window cap of 1 can never be reached.
 */

export type ChartSurface = "page" | "window";
export type ChartType = "line" | "candles";
export type IndicatorPlacement = "overlay" | "pane";

export interface IndicatorDescriptor {
    readonly id: string;
    readonly label: string;
    readonly placement: IndicatorPlacement;
    readonly surfaces: readonly ChartSurface[];
    readonly chartTypes: readonly ChartType[];
}

export const INDICATORS: readonly IndicatorDescriptor[] = [
    { id: "volume", label: "Volume", placement: "pane", surfaces: ["page", "window"], chartTypes: ["candles"] },
    { id: "vwap", label: "VWAP", placement: "overlay", surfaces: ["page", "window"], chartTypes: ["line", "candles"] },
];

/** Most lower panes a surface lays out: 1 on the floating window, 3 on the Charts page. */
export const PANE_CAP: Readonly<Record<ChartSurface, number>> = { window: 1, page: 3 };

/** Every indicator a surface offers, whatever the chart type, in registry order. */
export function onSurface(
    surface: ChartSurface,
    registry: readonly IndicatorDescriptor[] = INDICATORS,
): readonly IndicatorDescriptor[] {
    return registry.filter((d) => d.surfaces.includes(surface));
}

/** Indicators a surface can draw for the given chart type, in registry order. */
export function availableIndicators(
    surface: ChartSurface,
    chartType: ChartType,
    registry: readonly IndicatorDescriptor[] = INDICATORS,
): readonly IndicatorDescriptor[] {
    return onSurface(surface, registry).filter((d) => d.chartTypes.includes(chartType));
}

/** Enabled ids the surface can draw for the chart type, in registry order. */
export function drawableIds(
    surface: ChartSurface,
    chartType: ChartType,
    enabledIds: readonly string[],
    registry: readonly IndicatorDescriptor[] = INDICATORS,
): readonly string[] {
    return availableIndicators(surface, chartType, registry)
        .filter((d) => enabledIds.includes(d.id))
        .map((d) => d.id);
}

/** Enabled pane ids in registry order, before any cap. */
export function enabledPaneIds(
    enabledIds: readonly string[],
    registry: readonly IndicatorDescriptor[] = INDICATORS,
): readonly string[] {
    return registry.filter((d) => d.placement === "pane" && enabledIds.includes(d.id)).map((d) => d.id);
}
