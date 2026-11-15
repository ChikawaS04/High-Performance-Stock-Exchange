/**
 * The price chart's indicator rows (P17-9, Charts follow-up decision 1): one row per
 * indicator the surface offers, the name on the left and the standard §11.3 On / Off
 * toggle on the right, with the pane-cap note. One component, rendered by both the
 * gear popup's Indicators category and the band's Indicators popup, so the two read
 * and write the same persisted set and can never disagree.
 *
 * An indicator the current chart type cannot draw (volume in line mode) shows
 * disabled with a dim note naming where it does draw (typeNote: "Bar charts only"
 * for candles and footprint, P19-6) and keeps its stored state. Lower panes
 * are capped per surface (PANE_CAP): once the stored set holds the cap, the toggles
 * of panes that are off are disabled and one dim line reads "Pane limit reached (N)",
 * only while the cap actually holds a pane off. Overlays are never disabled by the cap.
 *
 * Parameters (P19-7, P19 D7). With showParams (the gear popup only; the band's
 * Indicators popup stays a toggle list), an indicator with params gets a ▸ disclosure
 * after its name. Expanded (▾), each param is a row with its label on the left and the
 * §11.5 editable combobox (FieldMenu, the sunken well with presets) on the right. A
 * typed value is stored only once it is valid (an integer in range, or one decimal
 * place for a tenths param); an invalid draft is left unstored and the well shows the
 * stored value again on blur. Which rows are expanded is local and never persisted
 * (§13 excludes expanded sections).
 *
 * With `ids` (the settings view's indicator folders) only those indicators render, in
 * registry order; the pane cap still counts every enabled pane, and the limit line
 * shows in a folder only while the cap holds one of that folder's panes off.
 */

import { useState } from "react";

import type { ChartSurface, ChartType } from "../chartSettings";
import { INDICATORS, PANE_CAP, enabledPaneIds, onSurface } from "../indicators/registry";
import type { IndicatorDescriptor, IndicatorParam } from "../indicators/registry";
import { NO_PARAMS, formatParam, paramValue, parseParam } from "../indicatorParams";
import type { IndicatorParams } from "../indicatorParams";
import { FieldMenu } from "./FieldMenu";

export interface IndicatorRowsProps {
    readonly surface: ChartSurface;
    readonly chartType: ChartType;
    readonly indicators: readonly string[];
    readonly setIndicators: (next: readonly string[]) => void;
    readonly registry?: readonly IndicatorDescriptor[];
    /** Render only these indicators (a settings view folder); all offered when absent. */
    readonly ids?: readonly string[];
    /** Show the parameter disclosures (the gear popup's category only). */
    readonly showParams?: boolean;
    readonly params?: IndicatorParams;
    readonly setParam?: (id: string, paramId: string, value: number) => void;
}

interface ParamFieldProps {
    readonly d: IndicatorDescriptor;
    readonly p: IndicatorParam;
    readonly value: number;
    readonly setParam: (id: string, paramId: string, value: number) => void;
}

/** One parameter's combobox: a draft while typing, stored only when valid. */
function ParamField({ d, p, value, setParam }: ParamFieldProps) {
    const [draft, setDraft] = useState<string | null>(null);
    const commit = (text: string): void => {
        const v = parseParam(p, text);
        if (v !== null && v !== value) setParam(d.id, p.id, v);
    };
    return (
        <span className="settings-pop__param" onBlur={() => setDraft(null)}>
            <FieldMenu
                testId={`pc-param-${d.id}-${p.id}`}
                ariaLabel={`${d.label} ${p.label}`}
                options={p.presets.map((v) => ({ value: formatParam(p, v), label: formatParam(p, v) }))}
                value={draft ?? formatParam(p, value)}
                editable
                numeric
                inputMode={p.display === "tenths" ? "decimal" : "numeric"}
                onInput={(text) => {
                    setDraft(text);
                    commit(text);
                }}
                onSelect={(text) => {
                    setDraft(null);
                    commit(text);
                }}
            />
        </span>
    );
}

/** The dim note on an indicator the current chart type cannot draw. */
export function typeNote(d: IndicatorDescriptor): string {
    const t = d.chartTypes;
    if (t.includes("candles") && t.includes("footprint") && !t.includes("line")) return "Bar charts only";
    if (t.length === 1 && t[0] === "candles") return "Candles only";
    if (t.length === 1 && t[0] === "footprint") return "Footprint only";
    return "Not in this chart type";
}

export function settingsRowClass(disabled: boolean): string {
    return disabled ? "settings-pop__row settings-pop__row--disabled" : "settings-pop__row";
}

export function IndicatorRows({
    surface,
    chartType,
    indicators,
    setIndicators,
    registry = INDICATORS,
    ids,
    showParams = false,
    params = NO_PARAMS,
    setParam,
}: IndicatorRowsProps) {
    const [expanded, setExpanded] = useState<readonly string[]>([]);
    const toggleExpanded = (id: string): void =>
        setExpanded((e) => (e.includes(id) ? e.filter((x) => x !== id) : [...e, id]));
    const onThis = onSurface(surface, registry);
    const offered = ids === undefined ? onThis : onThis.filter((d) => ids.includes(d.id));
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
                const hasParams = showParams && setParam !== undefined && d.params !== undefined && d.params.length > 0;
                const open = hasParams && expanded.includes(d.id);
                const row = (
                    <div key={d.id} className={settingsRowClass(disabled)} data-testid={`pc-ind-${d.id}-row`}>
                        <span className="settings-pop__name">
                            {d.label}
                            {hasParams ? (
                                <button
                                    type="button"
                                    className="settings-pop__disc"
                                    aria-expanded={open}
                                    aria-label={`${d.label} parameters`}
                                    data-testid={`pc-ind-${d.id}-params`}
                                    onClick={() => toggleExpanded(d.id)}
                                >
                                    {open ? "▾" : "▸"}
                                </button>
                            ) : null}
                            {typeBlocked ? (
                                <span className="settings-pop__note" data-testid={`pc-ind-${d.id}-note`}>
                                    {typeNote(d)}
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
                if (!open || d.params === undefined || setParam === undefined) return row;
                return [
                    row,
                    ...d.params.map((p) => (
                        <div
                            key={`${d.id}.${p.id}`}
                            className="settings-pop__row settings-pop__row--param"
                            data-testid={`pc-param-${d.id}-${p.id}-row`}
                        >
                            <span className="settings-pop__name settings-pop__name--param">{p.label}</span>
                            <ParamField d={d} p={p} value={paramValue(params, d, p.id)} setParam={setParam} />
                        </div>
                    )),
                ];
            })}
            {capHoldsOne ? (
                <div className="settings-pop__note settings-pop__note--line" data-testid="pc-pane-limit">
                    {`Pane limit reached (${cap})`}
                </div>
            ) : null}
        </>
    );
}
