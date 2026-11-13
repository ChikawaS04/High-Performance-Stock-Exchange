/**
 * The price chart's indicator rows (P17-9, Charts follow-up decision 1): one row per
 * indicator the surface offers, the name on the left and the standard §11.3 On / Off
 * toggle on the right, with the pane-cap note. One component, rendered by both the
 * gear popup's Indicators category and the band's Indicators popup, so the two read
 * and write the same persisted set and can never disagree.
 *
 * An indicator the current chart type cannot draw (volume in line mode) shows
 * disabled with the dim note "Candles only" and keeps its stored state. Lower panes
 * are capped per surface (PANE_CAP): once the stored set holds the cap, the toggles
 * of panes that are off are disabled and one dim line reads "Pane limit reached (N)",
 * only while the cap actually holds a pane off. Overlays are never disabled by the cap.
 */

import type { ChartSurface, ChartType } from "../chartSettings";
import { INDICATORS, PANE_CAP, enabledPaneIds, onSurface } from "../indicators/registry";
import type { IndicatorDescriptor } from "../indicators/registry";

export interface IndicatorRowsProps {
    readonly surface: ChartSurface;
    readonly chartType: ChartType;
    readonly indicators: readonly string[];
    readonly setIndicators: (next: readonly string[]) => void;
    readonly registry?: readonly IndicatorDescriptor[];
}

export function settingsRowClass(disabled: boolean, stack = false): string {
    const base = stack ? "settings-pop__row settings-pop__row--stack" : "settings-pop__row";
    return disabled ? `${base} settings-pop__row--disabled` : base;
}

export function IndicatorRows({ surface, chartType, indicators, setIndicators, registry = INDICATORS }: IndicatorRowsProps) {
    const offered = onSurface(surface, registry);
    const cap = PANE_CAP[surface];
    const atCap = enabledPaneIds(indicators, registry).length >= cap;
    const capHoldsOne = atCap && offered.some((d) => d.placement === "pane" && !indicators.includes(d.id));

    const setOn = (id: string, on: boolean): void => {
        const has = indicators.includes(id);
        if (on && !has) setIndicators([...indicators, id]);
        if (!on && has) setIndicators(indicators.filter((x) => x !== id));
    };

    return (
        <>
            {offered.map((d) => {
                const on = indicators.includes(d.id);
                const typeBlocked = !d.chartTypes.includes(chartType);
                const capBlocked = d.placement === "pane" && !on && atCap;
                const disabled = typeBlocked || capBlocked;
                return (
                    <div key={d.id} className={settingsRowClass(disabled)} data-testid={`pc-ind-${d.id}-row`}>
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
        </>
    );
}
