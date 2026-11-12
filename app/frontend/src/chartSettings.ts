/**
 * Chart settings model (P17-4, P17 D5).
 *
 * Three settings per chart surface, held separately for the Charts page and the
 * floating window on the Trading page: chart type (line or candles), bar size, and
 * the enabled indicators. Display colours (background, line colour, area fill, fill
 * colour) stay shared by both surfaces, as built (DESIGN_SYSTEM.md §13).
 *
 * Keys follow the existing scheme: hpse.v1.priceChart.<setting> for the page and
 * hpse.v1.priceWindow.<setting> for the window. Distinct keys are all it takes to
 * keep the surfaces independent, since usePersistentSetting syncs only instances of
 * the same key.
 *
 * Indicators are stored as a JSON array of ids. readSetting removes the whole key
 * when validation fails (P17-0 F6), so the stored value is read through a lenient
 * validator (any array of strings) and then cleaned by sanitizeIndicators: unknown
 * ids, ids the surface does not offer and duplicates are dropped. An id that is only
 * unavailable for the current chart type (volume in line mode) is KEPT, so switching
 * to Line and back does not lose it; the popup shows it disabled. The cleaned set is
 * not written back on read; the next toggle writes a clean set.
 */

import { useMemo } from "react";

import { BAR_SIZE_IDS } from "./bars";
import type { BarSizeId } from "./bars";
import { INDICATORS, onSurface } from "./indicators/registry";
import type { ChartSurface, ChartType, IndicatorDescriptor } from "./indicators/registry";
import { SETTING_KEYS, oneOf } from "./settings/persist";
import type { Validator } from "./settings/persist";
import { usePersistentSetting } from "./settings/usePersistentSetting";

export type { ChartSurface, ChartType } from "./indicators/registry";

export const CHART_TYPES: readonly ChartType[] = ["line", "candles"];

export const DEFAULT_CHART_TYPE: ChartType = "candles";
/** 15s: the bots' fill rate gives a 1m chart only a handful of candles in a short session (F11). */
export const DEFAULT_BAR_SIZE: BarSizeId = "15s";
export const DEFAULT_INDICATORS: readonly string[] = ["volume", "vwap"];

export const isChartType: Validator<ChartType> = oneOf<ChartType>(CHART_TYPES);
export const isBarSize: Validator<BarSizeId> = oneOf<BarSizeId>(BAR_SIZE_IDS);

/** Lenient: any array of strings. Cleaning is sanitizeIndicators' job. */
export const isStringArray: Validator<readonly string[]> = (value: unknown): value is readonly string[] =>
    Array.isArray(value) && value.every((v) => typeof v === "string");

export interface ChartSettingKeys {
    readonly chartType: string;
    readonly barSize: string;
    readonly indicators: string;
}

/** The persisted keys for a surface (the §13 inventory lives in SETTING_KEYS). */
export function chartSettingKeys(surface: ChartSurface): ChartSettingKeys {
    return surface === "page"
        ? {
              chartType: SETTING_KEYS.priceChartChartType,
              barSize: SETTING_KEYS.priceChartBarSize,
              indicators: SETTING_KEYS.priceChartIndicators,
          }
        : {
              chartType: SETTING_KEYS.priceWindowChartType,
              barSize: SETTING_KEYS.priceWindowBarSize,
              indicators: SETTING_KEYS.priceWindowIndicators,
          };
}

/**
 * Keep only ids the surface offers, once each, in stored order. Ids unavailable only
 * for the current chart type stay (see the module comment).
 */
export function sanitizeIndicators(
    ids: readonly string[],
    surface: ChartSurface,
    registry: readonly IndicatorDescriptor[] = INDICATORS,
): readonly string[] {
    const offered = new Set(onSurface(surface, registry).map((d) => d.id));
    const out: string[] = [];
    for (const id of ids) {
        if (offered.has(id) && !out.includes(id)) out.push(id);
    }
    return out;
}

export interface ChartSettings {
    readonly chartType: ChartType;
    readonly setChartType: (next: ChartType) => void;
    readonly barSize: BarSizeId;
    readonly setBarSize: (next: BarSizeId) => void;
    readonly indicators: readonly string[];
    readonly setIndicators: (next: readonly string[]) => void;
}

/** The three persisted chart settings for one surface. */
export function useChartSettings(
    surface: ChartSurface,
    registry: readonly IndicatorDescriptor[] = INDICATORS,
): ChartSettings {
    const keys = chartSettingKeys(surface);
    const [chartType, setChartType] = usePersistentSetting<ChartType>(keys.chartType, DEFAULT_CHART_TYPE, isChartType);
    const [barSize, setBarSize] = usePersistentSetting<BarSizeId>(keys.barSize, DEFAULT_BAR_SIZE, isBarSize);
    const [stored, setIndicators] = usePersistentSetting<readonly string[]>(
        keys.indicators,
        DEFAULT_INDICATORS,
        isStringArray,
    );
    const indicators = useMemo(() => sanitizeIndicators(stored, surface, registry), [stored, surface, registry]);
    return { chartType, setChartType, barSize, setBarSize, indicators, setIndicators };
}
