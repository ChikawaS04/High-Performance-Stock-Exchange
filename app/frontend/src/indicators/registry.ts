/**
 * Indicator registry (P17-5, P17 D6).
 *
 * One descriptor per indicator: where it draws (an overlay on the price area or a
 * lower pane), which chart surfaces offer it, and which chart types can draw it.
 * Phase 17 registers volume (a pane, candles only, since volume per bar needs bars)
 * and VWAP (an overlay, both chart types). Later phases add descriptors here, and a
 * params field arrives with its first consumer (Phase 19). Phase 18 adds the own
 * fill markers (Executions), the working order lines (Orders), the best bid and
 * ask lines (Bid/Ask) and the defaultOn flag. Phase 19 adds the order-flow tools
 * (volume profile, CVD) and the bar indicators.
 *
 * Overlays are unlimited. Lower panes are capped per surface (PANE_CAP), because
 * past that the price area gets too short to read. Every function that reads the
 * registry takes the descriptor list as a parameter, defaulting to INDICATORS, so a
 * test can pass a fixture with two or more panes (P17-0 F13): with the real
 * registry the window cap of 1 can never be reached.
 */

export type ChartSurface = "page" | "window";
/**
 * Line and candles on both surfaces; footprint (P19-6, P19 D6) and the liquidity
 * heatmap (P20-5, P20 D6) on the Charts page only (chartSettings.chartTypesFor).
 * Candles and footprint are the bar chart types: both draw the selected bar series and
 * lay out lower panes. The heatmap draws on the line chart's time axis with no panes.
 */
export type ChartType = "line" | "candles" | "footprint" | "heatmap";
export type IndicatorPlacement = "overlay" | "pane";

/**
 * One editable parameter of an indicator (P19-7, P19 D7). Always an integer within
 * [min, max]; `presets` are the combobox's quick picks, in stored units. A "tenths"
 * param (the Bollinger width) is stored in tenths and shown with one decimal place.
 */
export interface IndicatorParam {
    readonly id: string;
    readonly label: string;
    readonly kind: "int";
    readonly min: number;
    readonly max: number;
    readonly default: number;
    readonly presets: readonly number[];
    readonly display?: "tenths";
}

export interface IndicatorDescriptor {
    readonly id: string;
    readonly label: string;
    readonly placement: IndicatorPlacement;
    readonly surfaces: readonly ChartSurface[];
    readonly chartTypes: readonly ChartType[];
    /**
     * On in a fresh install (P18); absent means off. An id added to the registry
     * after a user already stored a set is switched on for them once
     * (chartSettings.migrateIndicators).
     */
    readonly defaultOn?: boolean;
    /** Editable parameters (P19-7), edited in the gear popup's Indicators category. */
    readonly params?: readonly IndicatorParam[];
}

/** A bar-count length parameter (P19-8, P19-9): 2 to 200 bars. */
function lengthParam(def: number): IndicatorParam {
    return { id: "length", label: "Length", kind: "int", min: 2, max: 200, default: def, presets: [5, 10, 20, 50, 100, 200] };
}

export const INDICATORS: readonly IndicatorDescriptor[] = [
    // P19-6: every indicator drawn per bar slot or on the price scale also draws in
    // footprint, which shares the candle slots and price scale.
    {
        id: "volume",
        label: "Volume",
        placement: "pane",
        surfaces: ["page", "window"],
        chartTypes: ["candles", "footprint"],
        defaultOn: true,
    },
    {
        id: "vwap",
        label: "VWAP",
        placement: "overlay",
        surfaces: ["page", "window"],
        // P20-5: the heatmap draws the time-axis overlays: VWAP, own fills and orders.
        // Bid/Ask is built into the heatmap, the volume profile would cover its cells.
        chartTypes: ["line", "candles", "footprint", "heatmap"],
        defaultOn: true,
    },
    // P18-2: own fills as markers (P18 D2), on both surfaces and both chart types.
    {
        id: "executions",
        label: "Executions",
        placement: "overlay",
        surfaces: ["page", "window"],
        chartTypes: ["line", "candles", "footprint", "heatmap"],
        defaultOn: true,
    },
    // P18-3: own working orders as lines (P18 D3), on both surfaces and both chart types.
    {
        id: "orders",
        label: "Orders",
        placement: "overlay",
        surfaces: ["page", "window"],
        chartTypes: ["line", "candles", "footprint", "heatmap"],
        defaultOn: true,
    },
    // P18-7: best bid and ask over time as step lines (P18 D7), off by default.
    {
        id: "bidask",
        label: "Bid/Ask",
        placement: "overlay",
        surfaces: ["page", "window"],
        chartTypes: ["line", "candles", "footprint"],
    },
    // P19-3: session volume at price as a horizontal histogram (P19 D4), Charts page only,
    // off by default. Session-wide, so it needs no bars and draws in line mode too.
    {
        id: "volprofile",
        label: "Volume profile",
        placement: "overlay",
        surfaces: ["page"],
        chartTypes: ["line", "candles", "footprint"],
    },
    // P19-4: cumulative volume delta as a lower pane (P19 D5), Charts page only, off by
    // default. It reads each bar's cvd, so it needs bars; it counts toward PANE_CAP.
    { id: "cvd", label: "CVD", placement: "pane", surfaces: ["page"], chartTypes: ["candles", "footprint"] },
    // P19-8: simple and exponential moving averages on bar closes (P19 D8), each twice so
    // two lengths can show at once (the second instance dashed). Both surfaces, bar
    // chart types only, off by default.
    ...(["sma", "sma2", "ema", "ema2"] as const).map(
        (id): IndicatorDescriptor => ({
            id,
            label: `${id.startsWith("sma") ? "SMA" : "EMA"}${id.endsWith("2") ? " 2" : ""}`,
            placement: "overlay",
            surfaces: ["page", "window"],
            chartTypes: ["candles", "footprint"],
            params: [lengthParam(id.endsWith("2") ? 50 : 20)],
        }),
    ),
    // P19-9: Bollinger bands (P19 D9), length and width in tenths of a standard deviation
    // (shown as "Std dev", 2.0 by default). Both surfaces, bar chart types, off by default.
    {
        id: "bollinger",
        label: "Bollinger",
        placement: "overlay",
        surfaces: ["page", "window"],
        chartTypes: ["candles", "footprint"],
        params: [
            lengthParam(20),
            {
                id: "width",
                label: "Std dev",
                kind: "int",
                min: 5,
                max: 50,
                default: 20,
                presets: [10, 15, 20, 25, 30],
                display: "tenths",
            },
        ],
    },
];

/** The ids a fresh install starts with: every descriptor marked defaultOn, in registry order. */
export function defaultIndicatorIds(registry: readonly IndicatorDescriptor[] = INDICATORS): readonly string[] {
    return registry.filter((d) => d.defaultOn === true).map((d) => d.id);
}

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
