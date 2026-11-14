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
 *
 * P18-2 adds a one-time migration for indicators registered later. Each surface
 * also stores the ids it has already offered (indicatorsSeen). An offered id that
 * is not yet seen is marked seen, and switched on if its descriptor is defaultOn,
 * so a user whose set was stored before Executions existed gets it on once, and a
 * user who then turns it off keeps it off. Before this key existed every surface
 * had offered exactly volume and VWAP (SEEN_BEFORE_P18).
 */

import { useEffect, useMemo } from "react";

import { BAR_SIZE_IDS } from "./bars";
import type { BarSizeId } from "./bars";
import { INDICATORS, defaultIndicatorIds, onSurface } from "./indicators/registry";
import type { ChartSurface, ChartType, IndicatorDescriptor } from "./indicators/registry";
import { SETTING_KEYS, oneOf } from "./settings/persist";
import type { Validator } from "./settings/persist";
import { usePersistentSetting } from "./settings/usePersistentSetting";

export type { ChartSurface, ChartType } from "./indicators/registry";

export const CHART_TYPES: readonly ChartType[] = ["line", "candles"];

export const DEFAULT_CHART_TYPE: ChartType = "candles";
/** 15s: the bots' fill rate gives a 1m chart only a handful of candles in a short session (F11). */
export const DEFAULT_BAR_SIZE: BarSizeId = "15s";
/** Every registered indicator marked defaultOn: volume, VWAP, Executions and Orders. */
export const DEFAULT_INDICATORS: readonly string[] = defaultIndicatorIds();

/** The ids every surface had offered before indicatorsSeen was stored (Phase 17). */
export const SEEN_BEFORE_P18: readonly string[] = ["volume", "vwap"];

export const isChartType: Validator<ChartType> = oneOf<ChartType>(CHART_TYPES);
export const isBarSize: Validator<BarSizeId> = oneOf<BarSizeId>(BAR_SIZE_IDS);

/** Lenient: any array of strings. Cleaning is sanitizeIndicators' job. */
export const isStringArray: Validator<readonly string[]> = (value: unknown): value is readonly string[] =>
    Array.isArray(value) && value.every((v) => typeof v === "string");

export interface ChartSettingKeys {
    readonly chartType: string;
    readonly barSize: string;
    readonly indicators: string;
    readonly indicatorsSeen: string;
}

/** The persisted keys for a surface (the §13 inventory lives in SETTING_KEYS). */
export function chartSettingKeys(surface: ChartSurface): ChartSettingKeys {
    return surface === "page"
        ? {
              chartType: SETTING_KEYS.priceChartChartType,
              barSize: SETTING_KEYS.priceChartBarSize,
              indicators: SETTING_KEYS.priceChartIndicators,
              indicatorsSeen: SETTING_KEYS.priceChartIndicatorsSeen,
          }
        : {
              chartType: SETTING_KEYS.priceWindowChartType,
              barSize: SETTING_KEYS.priceWindowBarSize,
              indicators: SETTING_KEYS.priceWindowIndicators,
              indicatorsSeen: SETTING_KEYS.priceWindowIndicatorsSeen,
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

export interface IndicatorMigration {
    readonly indicators: readonly string[];
    readonly seen: readonly string[];
    /** True when either list changed and should be written back. */
    readonly changed: boolean;
}

/**
 * The one-time switch-on for indicators added after a set was stored (see the module
 * comment). Pure. Every id the surface offers that is not in `seen` is added to it;
 * a defaultOn one is also added to the enabled set if it is not there already.
 * Returns the inputs themselves when nothing is new.
 */
export function migrateIndicators(
    stored: readonly string[],
    seen: readonly string[],
    surface: ChartSurface,
    registry: readonly IndicatorDescriptor[] = INDICATORS,
): IndicatorMigration {
    let indicators = stored;
    let nextSeen = seen;
    for (const d of onSurface(surface, registry)) {
        if (nextSeen.includes(d.id)) continue;
        nextSeen = [...nextSeen, d.id];
        if (d.defaultOn === true && !indicators.includes(d.id)) indicators = [...indicators, d.id];
    }
    return { indicators, seen: nextSeen, changed: nextSeen !== seen };
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
    const [seen, setSeen] = usePersistentSetting<readonly string[]>(keys.indicatorsSeen, SEEN_BEFORE_P18, isStringArray);
    const migration = useMemo(() => migrateIndicators(stored, seen, surface, registry), [stored, seen, surface, registry]);
    // Write a migration back once, after render; the render already uses the result.
    useEffect(() => {
        if (!migration.changed) return;
        if (migration.indicators !== stored) setIndicators(migration.indicators);
        setSeen(migration.seen);
    }, [migration, stored, setIndicators, setSeen]);
    const indicators = useMemo(
        () => sanitizeIndicators(migration.indicators, surface, registry),
        [migration.indicators, surface, registry],
    );
    return { chartType, setChartType, barSize, setBarSize, indicators, setIndicators };
}
