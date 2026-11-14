/**
 * Indicator parameters (P19-7, Phase 19 D7 as amended at P19-0 and at greenlight).
 * Pure: validation, cleaning, resolution and the edit-time parse and format.
 *
 * A descriptor may carry params, each an integer with a range and a default. Values
 * persist per surface with the indicator set, as one JSON object keyed by indicator
 * id and then param id: hpse.v1.priceChart.indicatorParams and
 * hpse.v1.priceWindow.indicatorParams.
 *
 * readSetting removes a whole key when validation fails (P17-0 F6), so the stored
 * object is read through a loose validator (any plain object) and then cleaned per
 * value: an unknown indicator or param, a non-integer and an out-of-range value are
 * each dropped on their own, and a dropped value reads as its default. One bad value
 * never costs the others.
 *
 * Display. Every value is stored as an integer. A param with display "tenths" (the
 * Bollinger width) is stored in tenths and shown as a decimal with one place, "2.0"
 * for 20; the edit parse turns "2.5" back into 25 exactly, with no float rounding.
 */

import type { ChartSurface, IndicatorDescriptor, IndicatorParam } from "./indicators/registry";
import { INDICATORS, onSurface } from "./indicators/registry";
import type { Validator } from "./settings/persist";

/** Stored values: indicator id, then param id, then the integer value. */
export type IndicatorParams = Readonly<Record<string, Readonly<Record<string, number>>>>;

export const NO_PARAMS: IndicatorParams = {};

function isPlainObject(v: unknown): v is Record<string, unknown> {
    return typeof v === "object" && v !== null && !Array.isArray(v);
}

/** Loose: any plain object. Per-value cleaning is cleanParams' job. */
export const isParamRecord: Validator<IndicatorParams> = (v: unknown): v is IndicatorParams => isPlainObject(v);

/** Is `v` a valid stored value for `p`: an integer within [min, max]? */
export function validParam(p: IndicatorParam, v: unknown): v is number {
    return typeof v === "number" && Number.isInteger(v) && v >= p.min && v <= p.max;
}

/**
 * Keep only valid values for params the surface's indicators declare. Pure. A key with
 * no valid value left is dropped, so a cleaned object holds only real overrides.
 */
export function cleanParams(
    raw: unknown,
    surface: ChartSurface,
    registry: readonly IndicatorDescriptor[] = INDICATORS,
): IndicatorParams {
    if (!isPlainObject(raw)) return NO_PARAMS;
    const out: Record<string, Record<string, number>> = {};
    for (const d of onSurface(surface, registry)) {
        if (d.params === undefined) continue;
        const stored = raw[d.id];
        if (!isPlainObject(stored)) continue;
        const kept: Record<string, number> = {};
        for (const p of d.params) {
            const v = stored[p.id];
            if (validParam(p, v)) kept[p.id] = v;
        }
        if (Object.keys(kept).length > 0) out[d.id] = kept;
    }
    return out;
}

/** The value of one param: the stored one if valid, else its default. */
export function paramValue(params: IndicatorParams, d: IndicatorDescriptor, paramId: string): number {
    const p = d.params?.find((x) => x.id === paramId);
    if (p === undefined) throw new Error(`unknown param ${d.id}.${paramId}`);
    const v = params[d.id]?.[paramId];
    return validParam(p, v) ? v : p.default;
}

/** Every param of a descriptor resolved to its value. */
export function resolveParams(params: IndicatorParams, d: IndicatorDescriptor): Readonly<Record<string, number>> {
    const out: Record<string, number> = {};
    for (const p of d.params ?? []) out[p.id] = paramValue(params, d, p.id);
    return out;
}

/** A new params object with one value set. The caller passes a validated value. */
export function withParam(params: IndicatorParams, id: string, paramId: string, value: number): IndicatorParams {
    return { ...params, [id]: { ...(params[id] ?? {}), [paramId]: value } };
}

/** How a value shows in the editor: "20", or "2.0" for a tenths param. */
export function formatParam(p: IndicatorParam, v: number): string {
    if (p.display !== "tenths") return String(v);
    return `${Math.trunc(v / 10)}.${Math.abs(v % 10)}`;
}

const INT_TEXT = /^\d+$/;
const TENTHS_TEXT = /^(\d+)(?:\.(\d?))?$/;

/**
 * The stored value a typed text means, or null when it is not a valid value: not a
 * whole number (or, for tenths, more than one decimal place), or outside [min, max].
 * Exact: "2.5" is 25 by string arithmetic, never by multiplying a float.
 */
export function parseParam(p: IndicatorParam, text: string): number | null {
    const t = text.trim();
    let v: number;
    if (p.display === "tenths") {
        const m = TENTHS_TEXT.exec(t);
        if (m === null) return null;
        v = Number(m[1]) * 10 + (m[2] !== undefined && m[2] !== "" ? Number(m[2]) : 0);
    } else {
        if (!INT_TEXT.test(t)) return null;
        v = Number(t);
    }
    return validParam(p, v) ? v : null;
}
