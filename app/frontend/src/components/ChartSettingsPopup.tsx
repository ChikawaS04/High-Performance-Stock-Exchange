/**
 * The price chart's settings popup (P17-9, P17 D10; Charts follow-up decision 6).
 *
 * DESIGN_SYSTEM.md §10 chrome filled per §11.4: categories are disclosure buttons,
 * rows put the name on the left and a §11.2 radio set, §11.3 toggle or colour chip on
 * the right, a transparent backdrop catches an outside click. Every control persists
 * (§13). The band's chart type, bar size and Indicators controls are shortcuts to the
 * same persisted keys, so the popup and the band always agree.
 *
 * Display, in order: Background, Chart type, Bar size (candles only), then a
 * "Candles" sub-label with Up candle, Down candle and Wicks, then a "Line" sub-label
 * with Line color, Area fill and Fill color. Rows that draw nothing in the current
 * chart type show disabled (the candle rows in line mode, the line rows in candle
 * mode, P17 D7 amended); Fill color is also disabled while Area fill is off. Each
 * colour row is a chip that opens the shared palette (ColorPalette). One palette is
 * open at a time.
 *
 * Indicators: the shared IndicatorRows (the band's Indicators popup renders the same
 * component). Footer: a "Reset colors" text button left of Done restores up, down,
 * line, fill and wicks to their defaults.
 */

import { useState } from "react";

import { BAR_SIZE_IDS } from "../bars";
import type { BarSizeId } from "../bars";
import { effectiveCandleColors } from "../chartColors";
import type { ChartColors, ColorSetting } from "../chartColors";
import { CHART_TYPES } from "../chartSettings";
import type { ChartSurface, ChartType } from "../chartSettings";
import { INDICATORS } from "../indicators/registry";
import type { IndicatorDescriptor } from "../indicators/registry";
import { ColorRow } from "./ColorPalette";
import { IndicatorRows, settingsRowClass } from "./IndicatorRows";

export type { ChartBg, FillChoice } from "../chartColors";

const CHART_TYPE_LABEL: Readonly<Record<ChartType, string>> = { line: "Line", candles: "Candles" };

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
}: ChartSettingsPopupProps) {
    const [displayOpen, setDisplayOpen] = useState(true);
    const [indicatorsOpen, setIndicatorsOpen] = useState(true);
    const [palette, setPalette] = useState<ColorSetting | null>(null);

    const { bg, setBg, lineColor, gradientOn, setGradientOn, fill, wicks, setWicks, recent, pick } = colors;
    const candles = chartType === "candles";
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

                            <div className="settings-pop__sub" data-testid="pc-sub-candles">
                                Candles
                            </div>
                            {colorRow("up", "Up candle", "pc-up", shown.up, shown.up, !candles)}
                            {colorRow("down", "Down candle", "pc-down", shown.down, shown.down, !candles)}
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

                            <div className="settings-pop__sub" data-testid="pc-sub-line">
                                Line
                            </div>
                            {colorRow("line", "Line color", "pc-line", lineColor, lineColor, candles)}
                            <div className={settingsRowClass(candles)} data-testid="pc-grad-row">
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
                            {colorRow(
                                "fill",
                                "Fill color",
                                "pc-fill",
                                fill === "line" ? lineColor : fill,
                                fill,
                                candles || !gradientOn,
                            )}
                            {candles ? (
                                <div className="settings-pop__note settings-pop__note--line" data-testid="pc-line-note">
                                    Line rows apply in line mode
                                </div>
                            ) : null}
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
                            <IndicatorRows
                                surface={surface}
                                chartType={chartType}
                                indicators={indicators}
                                setIndicators={setIndicators}
                                registry={registry}
                            />
                        </div>
                    )}
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
            </div>
        </>
    );
}
