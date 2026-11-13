/**
 * The price chart's header band (Charts follow-up, decision 1; DESIGN_SYSTEM.md §16.7).
 *
 * Left: the PRICE CHART title. Right, in order:
 *  - View (Charts page only): a raised charcoal button, "View ▾", opening the white
 *    §11.5 list as a menu with one item, View in trading. Then a 1px divider.
 *  - Chart type: the compact white single-select (FieldMenu), the trigger showing the
 *    current type's icon and name, each option its icon and label.
 *  - Bar size: the compact single-select, mono options right-aligned. Hidden in line mode.
 *  - Indicators: a raised charcoal button with the indicator icon, opening a §10
 *    popup anchored under it that holds the same IndicatorRows as the gear's
 *    Indicators category.
 *  - A divider, then the §11.1 settings gear, unchanged.
 * The band controls are shortcuts to the persisted keys the gear popup writes, so the
 * two always agree. One band popup or menu is open at a time.
 *
 * The floating window's band is compact: no View button, the chart type trigger and
 * the Indicators button are icon only (each with an aria-label), the bar size stays,
 * then the gear and the close ×. At the 360px minimum window it needs about 330px
 * (title about 81px, chart type 50px, bar size 57px, Indicators 34px, divider, gear
 * 20px, close 22px, gaps and padding), so it does not wrap; the band is nowrap and
 * the title ellipsises as a last resort.
 */

import { useRef, useState } from "react";
import type { ReactNode } from "react";
import { createPortal } from "react-dom";

import { BAR_SIZE_IDS } from "../bars";
import type { BarSizeId } from "../bars";
import type { ChartSurface, ChartType } from "../chartSettings";
import { INDICATORS } from "../indicators/registry";
import type { IndicatorDescriptor } from "../indicators/registry";
import { FieldMenu, useListPlacement } from "./FieldMenu";
import type { MenuOption } from "./FieldMenu";
import { IndicatorRows } from "./IndicatorRows";
import { CandleIcon, GearIcon, IndicatorIcon, LineIcon } from "./chartIcons";

type BandPopup = "view" | "ind" | "settings";

const TYPE_LABEL: Readonly<Record<ChartType, string>> = { candles: "Candles", line: "Line" };

const TYPE_OPTIONS: readonly MenuOption[] = [
    { value: "candles", label: "Candles", icon: <CandleIcon /> },
    { value: "line", label: "Line", icon: <LineIcon /> },
];

const SIZE_OPTIONS: readonly MenuOption[] = BAR_SIZE_IDS.map((s) => ({ value: s, label: s }));

export interface ChartBandProps {
    readonly surface: ChartSurface;
    readonly onViewInTrading?: () => void;
    readonly onClose?: () => void;
    readonly chartType: ChartType;
    readonly setChartType: (next: ChartType) => void;
    readonly barSize: BarSizeId;
    readonly setBarSize: (next: BarSizeId) => void;
    readonly indicators: readonly string[];
    readonly setIndicators: (next: readonly string[]) => void;
    readonly registry?: readonly IndicatorDescriptor[];
    /** The gear's settings popup, rendered while it is open. */
    readonly renderSettings: (close: () => void) => ReactNode;
}

export function ChartBand({
    surface,
    onViewInTrading,
    onClose,
    chartType,
    setChartType,
    barSize,
    setBarSize,
    indicators,
    setIndicators,
    registry = INDICATORS,
    renderSettings,
}: ChartBandProps) {
    const [open, setOpen] = useState<BandPopup | null>(null);
    const toggle = (p: BandPopup): void => setOpen((o) => (o === p ? null : p));
    const close = (): void => setOpen(null);
    const compact = surface === "window";

    const viewRef = useRef<HTMLDivElement>(null);
    const viewMenuRef = useRef<HTMLDivElement>(null);
    const viewStyle = useListPlacement(open === "view", viewRef, viewMenuRef);

    return (
        <div className={compact ? "price-chart__band price-chart__band--compact" : "price-chart__band"}>
            <span className="price-chart__title">Price chart</span>
            <div className="price-chart__controls">
                {onViewInTrading ? (
                    <>
                        <div className="price-chart__view" ref={viewRef}>
                            <button
                                type="button"
                                className="chart-btn"
                                aria-haspopup="menu"
                                aria-expanded={open === "view"}
                                data-testid="pc-view-caret"
                                onClick={() => toggle("view")}
                            >
                                View
                                <span className="chart-btn__caret term-caret" aria-hidden="true">
                                    {"▾"}
                                </span>
                            </button>
                        </div>
                        {open === "view"
                            ? createPortal(
                                  <>
                                      <div className="price-chart__view-backdrop" data-testid="pc-view-backdrop" onClick={close} />
                                      <div
                                          ref={viewMenuRef}
                                          className="field-menu__menu field-menu__menu--portal"
                                          role="menu"
                                          aria-label="Chart views"
                                          data-testid="pc-view-menu"
                                          style={viewStyle}
                                      >
                                          {/* Navigates to the trading page and floats this chart there (App wires onViewInTrading). */}
                                          <button
                                              type="button"
                                              role="menuitem"
                                              className="field-menu__opt field-menu__opt--action"
                                              data-testid="pc-view-trading"
                                              onClick={() => {
                                                  close();
                                                  onViewInTrading();
                                              }}
                                          >
                                              View in trading
                                          </button>
                                      </div>
                                  </>,
                                  document.body,
                              )
                            : null}
                        <span className="price-chart__divider" aria-hidden="true" />
                    </>
                ) : null}

                <FieldMenu
                    testId="pc-band-type"
                    ariaLabel={`Chart type: ${TYPE_LABEL[chartType]}`}
                    compact
                    iconOnly={compact}
                    className="price-chart__type"
                    options={TYPE_OPTIONS}
                    value={chartType}
                    triggerLabel={TYPE_LABEL[chartType]}
                    onSelect={(v) => setChartType(v === "line" ? "line" : "candles")}
                />

                {chartType === "candles" ? (
                    <FieldMenu
                        testId="pc-band-size"
                        ariaLabel={`Bar size: ${barSize}`}
                        compact
                        numeric
                        className="price-chart__size"
                        options={SIZE_OPTIONS}
                        value={barSize}
                        onSelect={(v) => {
                            const s = BAR_SIZE_IDS.find((id) => id === v);
                            if (s !== undefined) setBarSize(s);
                        }}
                    />
                ) : null}

                <div className="price-chart__ind">
                    <button
                        type="button"
                        className={compact ? "chart-btn chart-btn--icon" : "chart-btn"}
                        aria-haspopup="dialog"
                        aria-expanded={open === "ind"}
                        aria-label={compact ? "Indicators" : undefined}
                        data-testid="pc-ind-button"
                        onClick={() => toggle("ind")}
                    >
                        <IndicatorIcon />
                        {compact ? null : <span>Indicators</span>}
                    </button>
                    {open === "ind" ? (
                        <>
                            <div className="settings-pop__backdrop" data-testid="pc-ind-backdrop" onClick={close} />
                            <div
                                className="settings-pop settings-pop--ind"
                                role="dialog"
                                aria-label="Indicators"
                                data-testid="pc-ind-pop"
                            >
                                <div className="settings-pop__head">
                                    <span className="settings-pop__title">Indicators</span>
                                    <button
                                        type="button"
                                        className="settings-pop__x"
                                        aria-label="Close"
                                        data-testid="pc-ind-pop-close"
                                        onClick={close}
                                    >
                                        {"×"}
                                    </button>
                                </div>
                                <div className="settings-pop__body settings-pop__panel">
                                    <IndicatorRows
                                        surface={surface}
                                        chartType={chartType}
                                        indicators={indicators}
                                        setIndicators={setIndicators}
                                        registry={registry}
                                    />
                                </div>
                            </div>
                        </>
                    ) : null}
                </div>

                <span className="price-chart__divider" aria-hidden="true" />

                <button
                    type="button"
                    className="term-gear"
                    aria-label="Chart settings"
                    aria-haspopup="dialog"
                    aria-expanded={open === "settings"}
                    data-testid="pc-settings-gear"
                    onClick={() => toggle("settings")}
                >
                    <GearIcon />
                </button>

                {open === "settings" ? renderSettings(close) : null}

                {onClose ? (
                    <button
                        type="button"
                        className="price-chart__close"
                        aria-label="Close floating chart"
                        data-testid="pc-close"
                        onClick={onClose}
                    >
                        ×
                    </button>
                ) : null}
            </div>
        </div>
    );
}
