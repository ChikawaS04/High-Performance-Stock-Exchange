/**
 * The price chart's settings view (P17-9, P17 D10; reworked into the centred folder
 * view, DESIGN_SYSTEM.md §10 settings view exception and §11.4).
 *
 * Placement. Rendered into document.body and centred in the viewport on both surfaces,
 * so the floating window on the Trading page can never clip it. It sits above floating
 * windows and toasts (z 350 backdrop, 360 dialog) and below the FieldMenu list portal
 * (400), which must open over it. No scrim and no blur: the chart stays fully visible
 * and live behind it. A transparent backdrop catches an outside click to close; the ×
 * and Done close it too.
 *
 * Escape (capture phase, stopped there so the floating window behind never also
 * closes): with a colour palette open it closes only the palette; with a FieldMenu
 * list open it is left to the FieldMenu, which consumes it; otherwise it closes the
 * view.
 *
 * Layout: a fixed header, a body of two panes (the folder tree on the left, the
 * breadcrumb and the selected folder's content on the right), and a fixed footer
 * (Reset colors, Done). Only the tree and the content scroll (.term-scroll). The
 * folders come from settingsTree (src/settings/chartSettingsTree.ts): a parent shows
 * its subfolders as entries, a leaf its rows. Dim folders stay navigable and their
 * rows show disabled, as before. The view reopens at the last folder viewed on its
 * surface (session memory only).
 *
 * Rows keep their §11 look and every test id: Chart (Background, Chart type, Bar
 * size for the bar chart types, with 5s and 15s disabled in footprint), Candles (Up,
 * Down, Wicks), Line (Line color, Area fill, Fill color), Heatmap on the page
 * (Contrast, Trades; P20-5), and the indicator groups through the shared
 * IndicatorRows. Colour chips open the shared palette in a layer inside the dialog,
 * outside the scrolling pane, so it is never clipped. One palette is open at a time.
 *
 * Focus moves to the current folder's tree button on open. The band returns it to the
 * gear on close.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { BAR_SIZE_IDS, hasFootprint } from "../bars";
import type { BarSizeId } from "../bars";
import { effectiveCandleColors } from "../chartColors";
import type { ChartColors, ColorSetting } from "../chartColors";
import { chartTypesFor, isBarChart } from "../chartSettings";
import type { ChartSurface, ChartType } from "../chartSettings";
import { INDICATORS } from "../indicators/registry";
import type { IndicatorDescriptor } from "../indicators/registry";
import type { IndicatorParams } from "../indicatorParams";
import { HEAT_CONTRASTS } from "../heatmap/layout";
import type { HeatContrast } from "../heatmap/layout";
import type { HeatmapSettings } from "../heatmap/heatmapSettings";
import { crumbs, flatten, lastFolder, onCount, resolveFolder, setLastFolder, settingsTree } from "../settings/chartSettingsTree";
import type { Folder } from "../settings/chartSettingsTree";
import { ColorRow, PaletteLayerContext } from "./ColorPalette";
import { IndicatorRows, settingsRowClass } from "./IndicatorRows";

export type { ChartBg, FillChoice } from "../chartColors";

const CHART_TYPE_LABEL: Readonly<Record<ChartType, string>> = {
    line: "Line",
    candles: "Candles",
    footprint: "Footprint",
    heatmap: "Heatmap",
};
const CONTRAST_LABEL: Readonly<Record<HeatContrast, string>> = { low: "Low", normal: "Normal", high: "High" };

/** A folder path as a test id fragment: "display/candles" -> "display-candles", "" -> "root". */
export function folderTestId(path: string): string {
    return path === "" ? "root" : path.replace(/\//g, "-");
}

export interface ChartSettingsPopupProps {
    readonly surface: ChartSurface;
    readonly onClose: () => void;
    readonly colors: ChartColors;
    readonly chartType: ChartType;
    readonly setChartType: (next: ChartType) => void;
    readonly barSize: BarSizeId;
    readonly setBarSize: (next: BarSizeId) => void;
    readonly indicators: readonly string[];
    readonly setIndicators: (next: readonly string[]) => void;
    readonly registry?: readonly IndicatorDescriptor[];
    /** Indicator parameters (P19-7), edited here only. */
    readonly params?: IndicatorParams;
    readonly setParam?: (id: string, paramId: string, value: number) => void;
    /** The heatmap's Contrast and Trades (P20-5); absent, the Heatmap folder is not shown. */
    readonly heatmap?: HeatmapSettings;
}

export function ChartSettingsPopup({
    surface,
    onClose,
    colors,
    chartType,
    setChartType,
    barSize,
    setBarSize,
    indicators,
    setIndicators,
    registry = INDICATORS,
    params,
    setParam,
    heatmap,
}: ChartSettingsPopupProps) {
    const [palette, setPalette] = useState<ColorSetting | null>(null);
    const [path, setPath] = useState<string>(() => lastFolder(surface));
    const [layer, setLayer] = useState<HTMLDivElement | null>(null);
    const treeRef = useRef<HTMLDivElement>(null);

    const heatmapOffered = heatmap !== undefined && chartTypesFor(surface).includes("heatmap");
    const tree = useMemo(
        () => settingsTree({ surface, chartType, registry, heatmapOffered }),
        [surface, chartType, registry, heatmapOffered],
    );
    const current = resolveFolder(tree, path);
    const trail = crumbs(tree, current.path);

    const go = (to: string): void => {
        setPalette(null);
        setPath(to);
        setLastFolder(surface, to);
    };

    // Escape: the innermost open thing first, and never the floating window behind.
    const paletteRef = useRef(palette);
    paletteRef.current = palette;
    const closeRef = useRef(onClose);
    closeRef.current = onClose;
    useEffect(() => {
        const onKey = (e: KeyboardEvent): void => {
            if (e.key !== "Escape") return;
            // An open FieldMenu list (a parameter combobox) closes itself and consumes
            // the key, so the view and the window behind stay open.
            if (document.querySelector(".field-menu__menu--portal") !== null) return;
            e.stopPropagation();
            if (paletteRef.current !== null) setPalette(null);
            else closeRef.current();
        };
        document.addEventListener("keydown", onKey, true);
        return () => document.removeEventListener("keydown", onKey, true);
    }, []);

    // Focus moves into the view on open: the current folder's tree button.
    useEffect(() => {
        const btn = treeRef.current?.querySelector<HTMLButtonElement>('[aria-current="true"]');
        btn?.focus();
    }, []);

    const { bg, setBg, lineColor, gradientOn, setGradientOn, fill, wicks, setWicks, recent, pick } = colors;
    const candles = chartType === "candles";
    // Candles and footprint draw bars: the bar size and the up and down colours apply.
    const bars = isBarChart(chartType);
    const footprint = chartType === "footprint";
    // The line rows apply in line mode only; the heatmap has its own rows and ground (P20-5).
    const lineMode = chartType === "line";
    const heatmapMode = chartType === "heatmap";
    const shown = effectiveCandleColors(bg, colors.candleUp, colors.candleDown);

    const colorRow = (setting: ColorSetting, label: string, prefix: string, hex: string, value: string, disabled: boolean) => (
        <ColorRow
            label={label}
            prefix={prefix}
            hex={hex}
            value={value}
            disabled={disabled}
            open={palette === setting}
            onToggle={() => setPalette((p) => (p === setting ? null : setting))}
            onClose={() => setPalette(null)}
            recent={recent}
            onPick={(v) => pick(setting, v)}
            matchLine={setting === "fill"}
            rowClass={settingsRowClass(disabled)}
        />
    );

    const chartRows = (
        <>
            <div className={settingsRowClass(heatmapMode)} data-testid="pc-bg-row">
                <span className="settings-pop__name">
                    Background
                    {heatmapMode ? (
                        <span className="settings-pop__note" data-testid="pc-bg-note">
                            Heatmap draws on black
                        </span>
                    ) : null}
                </span>
                <span className="settings-toggle" role="group" aria-label="Chart background">
                    <button
                        type="button"
                        className="settings-toggle__opt settings-toggle__opt--sans"
                        aria-pressed={bg === "black"}
                        disabled={heatmapMode}
                        data-testid="pc-bg-black"
                        onClick={() => setBg("black")}
                    >
                        Black
                    </button>
                    <button
                        type="button"
                        className="settings-toggle__opt settings-toggle__opt--sans"
                        aria-pressed={bg === "white"}
                        disabled={heatmapMode}
                        data-testid="pc-bg-white"
                        onClick={() => setBg("white")}
                    >
                        White
                    </button>
                </span>
            </div>

            <div className={settingsRowClass(false)} data-testid="pc-type-row">
                <span className="settings-pop__name">Chart type</span>
                <span className="settings-radio" role="radiogroup" aria-label="Chart type">
                    {chartTypesFor(surface).map((t) => (
                        <label key={t} className="settings-radio__opt">
                            <input
                                type="radio"
                                name={`pc-chart-type-${surface}`}
                                checked={chartType === t}
                                onChange={() => setChartType(t)}
                                data-testid={`pc-type-${t}`}
                            />
                            {CHART_TYPE_LABEL[t]}
                        </label>
                    ))}
                </span>
            </div>

            {bars ? (
                <div className={settingsRowClass(false)} data-testid="pc-size-row">
                    <span className="settings-pop__name">
                        Bar size
                        {footprint ? (
                            <span className="settings-pop__note" data-testid="pc-size-note">
                                1m and 5m only
                            </span>
                        ) : null}
                    </span>
                    <span className="settings-radio" role="radiogroup" aria-label="Bar size">
                        {BAR_SIZE_IDS.map((s) => {
                            const off = footprint && !hasFootprint(s);
                            return (
                                <label key={s} className={off ? "settings-radio__opt settings-radio__opt--disabled" : "settings-radio__opt"}>
                                    <input
                                        type="radio"
                                        name={`pc-bar-size-${surface}`}
                                        checked={barSize === s}
                                        disabled={off}
                                        onChange={() => setBarSize(s)}
                                        data-testid={`pc-size-${s}`}
                                    />
                                    {s}
                                </label>
                            );
                        })}
                    </span>
                </div>
            ) : null}
        </>
    );

    const candleRows = (
        <>
            {colorRow("up", "Up candle", "pc-up", shown.up, shown.up, !bars)}
            {colorRow("down", "Down candle", "pc-down", shown.down, shown.down, !bars)}
            <div className={settingsRowClass(!candles)} data-testid="pc-wicks-row">
                <span className="settings-pop__name">Wicks</span>
                <span className="settings-toggle" role="group" aria-label="Wicks">
                    <button
                        type="button"
                        className="settings-toggle__opt settings-toggle__opt--sans"
                        aria-pressed={wicks === "body"}
                        disabled={!candles}
                        data-testid="pc-wicks-body"
                        onClick={() => setWicks("body")}
                    >
                        Match body
                    </button>
                    <button
                        type="button"
                        className="settings-toggle__opt settings-toggle__opt--sans"
                        aria-pressed={wicks === "grey"}
                        disabled={!candles}
                        data-testid="pc-wicks-grey"
                        onClick={() => setWicks("grey")}
                    >
                        Grey
                    </button>
                </span>
            </div>
        </>
    );

    const lineRows = (
        <>
            {colorRow("line", "Line color", "pc-line", lineColor, lineColor, !lineMode)}
            <div className={settingsRowClass(!lineMode)} data-testid="pc-grad-row">
                <span className="settings-pop__name">Area fill</span>
                <span className="settings-toggle" role="group" aria-label="Area fill">
                    <button
                        type="button"
                        className="settings-toggle__opt settings-toggle__opt--sans"
                        aria-pressed={gradientOn}
                        disabled={!lineMode}
                        data-testid="pc-grad-on"
                        onClick={() => setGradientOn(true)}
                    >
                        On
                    </button>
                    <button
                        type="button"
                        className="settings-toggle__opt settings-toggle__opt--sans"
                        aria-pressed={!gradientOn}
                        disabled={!lineMode}
                        data-testid="pc-grad-off"
                        onClick={() => setGradientOn(false)}
                    >
                        Off
                    </button>
                </span>
            </div>
            {colorRow("fill", "Fill color", "pc-fill", fill === "line" ? lineColor : fill, fill, !lineMode || !gradientOn)}
            {!lineMode ? (
                <div className="settings-pop__note settings-pop__note--line" data-testid="pc-line-note">
                    Line rows apply in line mode
                </div>
            ) : null}
        </>
    );

    const heatmapRows =
        heatmap === undefined ? null : (
            <>
                <div className={settingsRowClass(!heatmapMode)} data-testid="pc-contrast-row">
                    <span className="settings-pop__name">Contrast</span>
                    <span className="settings-radio" role="radiogroup" aria-label="Heatmap contrast">
                        {HEAT_CONTRASTS.map((c) => (
                            <label key={c} className={heatmapMode ? "settings-radio__opt" : "settings-radio__opt settings-radio__opt--disabled"}>
                                <input
                                    type="radio"
                                    name={`pc-heat-contrast-${surface}`}
                                    checked={heatmap.contrast === c}
                                    disabled={!heatmapMode}
                                    onChange={() => heatmap.setContrast(c)}
                                    data-testid={`pc-contrast-${c}`}
                                />
                                {CONTRAST_LABEL[c]}
                            </label>
                        ))}
                    </span>
                </div>
                <div className={settingsRowClass(!heatmapMode)} data-testid="pc-trades-row">
                    <span className="settings-pop__name">Trades</span>
                    <span className="settings-toggle" role="group" aria-label="Heatmap trades">
                        <button
                            type="button"
                            className="settings-toggle__opt settings-toggle__opt--sans"
                            aria-pressed={heatmap.trades}
                            disabled={!heatmapMode}
                            data-testid="pc-trades-on"
                            onClick={() => heatmap.setTrades(true)}
                        >
                            On
                        </button>
                        <button
                            type="button"
                            className="settings-toggle__opt settings-toggle__opt--sans"
                            aria-pressed={!heatmap.trades}
                            disabled={!heatmapMode}
                            data-testid="pc-trades-off"
                            onClick={() => heatmap.setTrades(false)}
                        >
                            Off
                        </button>
                    </span>
                </div>
            </>
        );

    /** A parent folder's listing: its subfolders as entries. */
    const entries = (f: Folder) => (
        <div className="settings-view__entries" role="list">
            {f.children.map((c) => {
                const n = c.indicatorIds.length > 0 ? onCount(c, indicators) : null;
                const summary = c.note ?? (n !== null ? `${n} on` : null);
                return (
                    <div key={c.path} role="listitem">
                        <button
                            type="button"
                            className={c.dim ? "settings-view__entry settings-view__entry--dim" : "settings-view__entry"}
                            data-testid={`pc-entry-${folderTestId(c.path)}`}
                            onClick={() => go(c.path)}
                        >
                            <span className="settings-view__entry-name">{c.label}</span>
                            {summary !== null ? <span className="settings-pop__note settings-view__entry-note">{summary}</span> : null}
                            <span className="settings-view__entry-caret" aria-hidden="true">
                                {"▸"}
                            </span>
                        </button>
                    </div>
                );
            })}
        </div>
    );

    const content = (f: Folder) => {
        if (f.children.length > 0 || f.indicatorIds.length === 0) {
            switch (f.path) {
                case "display/chart":
                    return chartRows;
                case "display/candles":
                    return candleRows;
                case "display/line":
                    return lineRows;
                case "display/heatmap":
                    return heatmapRows;
                default:
                    return entries(f);
            }
        }
        return (
            <div data-testid="pc-settings-indicators">
                <IndicatorRows
                    surface={surface}
                    chartType={chartType}
                    indicators={indicators}
                    setIndicators={setIndicators}
                    registry={registry}
                    ids={f.indicatorIds}
                    showParams
                    params={params}
                    setParam={setParam}
                />
            </div>
        );
    };

    const inTrail = (p: string): boolean => trail.some((t) => t.path === p);

    return createPortal(
        <>
            <div className="settings-pop__backdrop settings-pop__backdrop--view" data-testid="pc-settings-backdrop" onClick={onClose} />
            <div
                className="settings-pop settings-pop--chart settings-pop--view"
                role="dialog"
                aria-label="Chart settings"
                data-testid="pc-settings"
                data-surface={surface}
            >
                <div className="settings-pop__head">
                    <span className="settings-pop__title">Settings</span>
                    <button type="button" className="settings-pop__x" aria-label="Close" data-testid="pc-settings-close" onClick={onClose}>
                        {"×"}
                    </button>
                </div>

                <div className="settings-view__body">
                    <nav className="settings-view__tree term-scroll" aria-label="Settings folders" data-testid="pc-settings-tree" ref={treeRef}>
                        {flatten(tree).map(({ folder: f, depth }) => {
                            const selected = f.path === current.path;
                            const cls = [
                                "settings-view__node",
                                depth > 0 ? "settings-view__node--sub" : "",
                                selected ? "settings-view__node--on" : "",
                                !selected && inTrail(f.path) ? "settings-view__node--trail" : "",
                                f.dim ? "settings-view__node--dim" : "",
                            ]
                                .filter((c) => c !== "")
                                .join(" ");
                            return (
                                <button
                                    key={f.path}
                                    type="button"
                                    className={cls}
                                    aria-current={selected ? "true" : undefined}
                                    data-testid={f.path === "indicators" ? "pc-settings-indicators-cat" : `pc-folder-${folderTestId(f.path)}`}
                                    onClick={() => go(f.path)}
                                >
                                    {f.label}
                                </button>
                            );
                        })}
                    </nav>

                    <div className="settings-view__main">
                        <nav className="settings-view__crumbs" aria-label="Breadcrumb" data-testid="pc-settings-crumbs">
                            {trail.map((f, i) => (
                                <span key={f.path} className="settings-view__crumb-item">
                                    {i > 0 ? (
                                        <span className="settings-view__sep" aria-hidden="true">
                                            {"›"}
                                        </span>
                                    ) : null}
                                    <button
                                        type="button"
                                        className={i === trail.length - 1 ? "settings-view__crumb settings-view__crumb--on" : "settings-view__crumb"}
                                        aria-current={i === trail.length - 1 ? "page" : undefined}
                                        data-testid={`pc-crumb-${i}`}
                                        onClick={() => go(f.path)}
                                    >
                                        {f.label}
                                    </button>
                                </span>
                            ))}
                        </nav>
                        <div className="settings-view__content term-scroll" data-testid="pc-settings-content" data-folder={current.path}>
                            <PaletteLayerContext.Provider value={layer}>{content(current)}</PaletteLayerContext.Provider>
                        </div>
                    </div>
                </div>

                <div className="settings-pop__foot settings-pop__foot--split">
                    <button
                        type="button"
                        className="settings-pop__textbtn"
                        data-testid="pc-reset-colors"
                        onClick={() => {
                            setPalette(null);
                            colors.resetColors();
                        }}
                    >
                        Reset colors
                    </button>
                    <button type="button" className="settings-pop__done" onClick={onClose} data-testid="pc-settings-done">
                        Done
                    </button>
                </div>

                {/* The palette layer: palettes render here, outside the scrolling pane. */}
                <div className="settings-view__layer" ref={setLayer} />
            </div>
        </>,
        document.body,
    );
}
