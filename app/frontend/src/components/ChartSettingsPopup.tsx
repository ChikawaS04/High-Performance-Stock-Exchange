/**
 * The price chart's settings popup (P17-9, P17 D10), moved out of PriceChart.
 *
 * DESIGN_SYSTEM.md §10 chrome filled per §11.4: categories are disclosure buttons,
 * rows put the name on the left and a §11.2 radio set or §11.3 toggle on the right,
 * the silver Done closes, a transparent backdrop catches an outside click. Every
 * control persists (§13) through the values and setters PriceChart passes in. Every
 * test id from the inline P12-R popup is kept.
 *
 * Display: Background, then Chart type (Line, Candles) and, in candles mode only,
 * Bar size (5s, 15s, 1m, 5m). Line color, Area fill and Fill color draw nothing in
 * candles mode, so those rows show disabled there (P17 D7 amended); Fill color is
 * also disabled while Area fill is off, as built.
 *
 * Indicators: one row per indicator the surface offers, name left, the standard
 * On / Off toggle right. An indicator the current chart type cannot draw (volume in
 * line mode) shows disabled with the dim note "Candles only" and keeps its stored
 * state. Lower panes are capped per surface (PANE_CAP): once the stored set holds
 * the cap, the toggles of panes that are off are disabled and the category shows
 * one dim line, "Pane limit reached (N)" (only when a pane is actually held off, so
 * the real one-pane registry never shows it). Overlays are never disabled by the cap.
 * The registry is a prop so a test can reach the cap with a fixture (P17-0 F13).
 */

import { useState } from "react";

import { BAR_SIZE_IDS } from "../bars";
import type { BarSizeId } from "../bars";
import { CHART_TYPES } from "../chartSettings";
import type { ChartSurface, ChartType } from "../chartSettings";
import { INDICATORS, PANE_CAP, enabledPaneIds, onSurface } from "../indicators/registry";
import type { IndicatorDescriptor } from "../indicators/registry";

export type ChartBg = "black" | "white";
/** "line" matches the price line; any other value is a literal fill hex. */
export type FillChoice = "line" | string;

export interface Swatch {
    readonly name: string;
    readonly hex: string;
}

// A broad line-colour palette (display config, deliberately literal, not semantic).
export const LINE_COLORS: readonly Swatch[] = [
    { name: "Amber", hex: "#ffb01f" },
    { name: "Green", hex: "#12d170" },
    { name: "Red", hex: "#ff4d4f" },
    { name: "Blue", hex: "#3b93ff" },
    { name: "Cyan", hex: "#1fd6e8" },
    { name: "Teal", hex: "#0fd9b6" },
    { name: "Violet", hex: "#8a6bff" },
    { name: "Magenta", hex: "#f14fe6" },
    { name: "Orange", hex: "#ff7a18" },
    { name: "Yellow", hex: "#ffe234" },
    { name: "White", hex: "#ffffff" },
    { name: "Slate", hex: "#a9b4c4" },
];

// A brief fill palette: match the line, or a few presets.
export const FILL_CHOICES: readonly { readonly key: FillChoice; readonly name: string }[] = [
    { key: "line", name: "Match line" },
    { key: "#ffb01f", name: "Amber" },
    { key: "#12d170", name: "Green" },
    { key: "#3b93ff", name: "Blue" },
];

const CHART_TYPE_LABEL: Readonly<Record<ChartType, string>> = { line: "Line", candles: "Candles" };

export interface ChartSettingsPopupProps {
    readonly surface: ChartSurface;
    readonly onClose: () => void;
    readonly bg: ChartBg;
    readonly setBg: (next: ChartBg) => void;
    readonly lineColor: string;
    readonly setLineColor: (next: string) => void;
    readonly gradientOn: boolean;
    readonly setGradientOn: (next: boolean) => void;
    readonly fill: FillChoice;
    readonly setFill: (next: FillChoice) => void;
    readonly chartType: ChartType;
    readonly setChartType: (next: ChartType) => void;
    readonly barSize: BarSizeId;
    readonly setBarSize: (next: BarSizeId) => void;
    readonly indicators: readonly string[];
    readonly setIndicators: (next: readonly string[]) => void;
    readonly registry?: readonly IndicatorDescriptor[];
}

function rowClass(disabled: boolean, stack = false): string {
    const base = stack ? "settings-pop__row settings-pop__row--stack" : "settings-pop__row";
    return disabled ? `${base} settings-pop__row--disabled` : base;
}

export function ChartSettingsPopup({
    surface,
    onClose,
    bg,
    setBg,
    lineColor,
    setLineColor,
    gradientOn,
    setGradientOn,
    fill,
    setFill,
    chartType,
    setChartType,
    barSize,
    setBarSize,
    indicators,
    setIndicators,
    registry = INDICATORS,
}: ChartSettingsPopupProps) {
    const [displayOpen, setDisplayOpen] = useState(true);
    const [indicatorsOpen, setIndicatorsOpen] = useState(true);

    const candles = chartType === "candles";
    const offered = onSurface(surface, registry);
    const cap = PANE_CAP[surface];
    const atCap = enabledPaneIds(indicators, registry).length >= cap;
    // The dim line shows only when the cap actually holds a pane off. With the real
    // registry (one pane) it never shows, rather than reading as a warning by default.
    const capHoldsOne = atCap && offered.some((d) => d.placement === "pane" && !indicators.includes(d.id));

    const setOn = (id: string, on: boolean): void => {
        const has = indicators.includes(id);
        if (on && !has) setIndicators([...indicators, id]);
        if (!on && has) setIndicators(indicators.filter((x) => x !== id));
    };

    return (
        <>
            <div className="settings-pop__backdrop" data-testid="pc-settings-backdrop" onClick={onClose} />
            <div className="settings-pop settings-pop--chart" role="dialog" aria-label="Chart settings" data-testid="pc-settings">
                <div className="settings-pop__head">
                    <span className="settings-pop__title">Settings</span>
                    <button
                        type="button"
                        className="settings-pop__x"
                        aria-label="Close"
                        data-testid="pc-settings-close"
                        onClick={onClose}
                    >
                        {"×"}
                    </button>
                </div>

                <div className="settings-pop__body">
                    <button
                        type="button"
                        className="settings-pop__cat"
                        aria-expanded={displayOpen}
                        aria-controls={`pc-settings-display-${surface}`}
                        onClick={() => setDisplayOpen((o) => !o)}
                    >
                        <span>Display</span>
                        <span className="settings-pop__caret" aria-hidden="true">{"▸"}</span>
                    </button>

                    {displayOpen && (
                        <div className="settings-pop__panel" id={`pc-settings-display-${surface}`}>
                            <div className="settings-pop__row">
                                <span className="settings-pop__name">Background</span>
                                <span className="settings-toggle" role="group" aria-label="Chart background">
                                    <button
                                        type="button"
                                        className="settings-toggle__opt settings-toggle__opt--sans"
                                        aria-pressed={bg === "black"}
                                        data-testid="pc-bg-black"
                                        onClick={() => setBg("black")}
                                    >
                                        Black
                                    </button>
                                    <button
                                        type="button"
                                        className="settings-toggle__opt settings-toggle__opt--sans"
                                        aria-pressed={bg === "white"}
                                        data-testid="pc-bg-white"
                                        onClick={() => setBg("white")}
                                    >
                                        White
                                    </button>
                                </span>
                            </div>

                            <div className="settings-pop__row" data-testid="pc-type-row">
                                <span className="settings-pop__name">Chart type</span>
                                <span className="settings-radio" role="radiogroup" aria-label="Chart type">
                                    {CHART_TYPES.map((t) => (
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

                            {candles ? (
                                <div className="settings-pop__row" data-testid="pc-size-row">
                                    <span className="settings-pop__name">Bar size</span>
                                    <span className="settings-radio" role="radiogroup" aria-label="Bar size">
                                        {BAR_SIZE_IDS.map((s) => (
                                            <label key={s} className="settings-radio__opt">
                                                <input
                                                    type="radio"
                                                    name={`pc-bar-size-${surface}`}
                                                    checked={barSize === s}
                                                    onChange={() => setBarSize(s)}
                                                    data-testid={`pc-size-${s}`}
                                                />
                                                {s}
                                            </label>
                                        ))}
                                    </span>
                                </div>
                            ) : null}

                            <div className={rowClass(candles, true)} data-testid="pc-line-row">
                                <span className="settings-pop__name">Line color</span>
                                <span className="settings-swatches" role="radiogroup" aria-label="Line color">
                                    {LINE_COLORS.map((c) => (
                                        <button
                                            key={c.hex}
                                            type="button"
                                            role="radio"
                                            aria-checked={lineColor === c.hex}
                                            aria-label={c.name}
                                            title={c.name}
                                            disabled={candles}
                                            className={
                                                lineColor === c.hex ? "settings-swatch settings-swatch--on" : "settings-swatch"
                                            }
                                            style={{ background: c.hex }}
                                            data-testid={`pc-line-${c.hex}`}
                                            onClick={() => setLineColor(c.hex)}
                                        />
                                    ))}
                                </span>
                            </div>

                            <div className={rowClass(candles)} data-testid="pc-grad-row">
                                <span className="settings-pop__name">Area fill</span>
                                <span className="settings-toggle" role="group" aria-label="Area fill">
                                    <button
                                        type="button"
                                        className="settings-toggle__opt settings-toggle__opt--sans"
                                        aria-pressed={gradientOn}
                                        disabled={candles}
                                        data-testid="pc-grad-on"
                                        onClick={() => setGradientOn(true)}
                                    >
                                        On
                                    </button>
                                    <button
                                        type="button"
                                        className="settings-toggle__opt settings-toggle__opt--sans"
                                        aria-pressed={!gradientOn}
                                        disabled={candles}
                                        data-testid="pc-grad-off"
                                        onClick={() => setGradientOn(false)}
                                    >
                                        Off
                                    </button>
                                </span>
                            </div>

                            <div className={rowClass(candles || !gradientOn, true)} data-testid="pc-fill-row">
                                <span className="settings-pop__name">Fill color</span>
                                <span className="settings-swatches" role="radiogroup" aria-label="Fill color">
                                    {FILL_CHOICES.map((fc) => {
                                        const sw = fc.key === "line" ? lineColor : fc.key;
                                        const on = fill === fc.key;
                                        return (
                                            <button
                                                key={fc.key}
                                                type="button"
                                                role="radio"
                                                aria-checked={on}
                                                aria-label={fc.name}
                                                title={fc.name}
                                                disabled={candles}
                                                className={on ? "settings-swatch settings-swatch--on" : "settings-swatch"}
                                                style={{ background: sw }}
                                                data-testid={`pc-fill-${fc.key}`}
                                                onClick={() => setFill(fc.key)}
                                            />
                                        );
                                    })}
                                </span>
                            </div>
                        </div>
                    )}

                    <button
                        type="button"
                        className="settings-pop__cat"
                        aria-expanded={indicatorsOpen}
                        aria-controls={`pc-settings-indicators-${surface}`}
                        data-testid="pc-settings-indicators-cat"
                        onClick={() => setIndicatorsOpen((o) => !o)}
                    >
                        <span>Indicators</span>
                        <span className="settings-pop__caret" aria-hidden="true">{"▸"}</span>
                    </button>

                    {indicatorsOpen && (
                        <div
                            className="settings-pop__panel"
                            id={`pc-settings-indicators-${surface}`}
                            data-testid="pc-settings-indicators"
                        >
                            {offered.map((d) => {
                                const on = indicators.includes(d.id);
                                const typeBlocked = !d.chartTypes.includes(chartType);
                                const capBlocked = d.placement === "pane" && !on && atCap;
                                const disabled = typeBlocked || capBlocked;
                                return (
                                    <div key={d.id} className={rowClass(disabled)} data-testid={`pc-ind-${d.id}-row`}>
                                        <span className="settings-pop__name">
                                            {d.label}
                                            {typeBlocked ? (
                                                <span className="settings-pop__note" data-testid={`pc-ind-${d.id}-note`}>
                                                    Candles only
                                                </span>
                                            ) : null}
                                        </span>
                                        <span className="settings-toggle" role="group" aria-label={d.label}>
                                            <button
                                                type="button"
                                                className="settings-toggle__opt settings-toggle__opt--sans"
                                                aria-pressed={on}
                                                disabled={disabled}
                                                data-testid={`pc-ind-${d.id}-on`}
                                                onClick={() => setOn(d.id, true)}
                                            >
                                                On
                                            </button>
                                            <button
                                                type="button"
                                                className="settings-toggle__opt settings-toggle__opt--sans"
                                                aria-pressed={!on}
                                                disabled={disabled}
                                                data-testid={`pc-ind-${d.id}-off`}
                                                onClick={() => setOn(d.id, false)}
                                            >
                                                Off
                                            </button>
                                        </span>
                                    </div>
                                );
                            })}
                            {capHoldsOne ? (
                                <div className="settings-pop__note settings-pop__note--line" data-testid="pc-pane-limit">
                                    {`Pane limit reached (${cap})`}
                                </div>
                            ) : null}
                        </div>
                    )}
                </div>

                <div className="settings-pop__foot">
                    <button type="button" className="settings-pop__done" onClick={onClose} data-testid="pc-settings-done">
                        Done
                    </button>
                </div>
            </div>
        </>
    );
}
