/**
 * Pure time-axis rules for the price chart (Charts follow-up, decision 4). No React,
 * no SVG.
 *
 * The tick step comes from round clock steps so labels sit at least
 * MIN_LABEL_GAP_PX apart, as TWS and Bloomberg do. Labels read HH:MM when the step
 * is a minute or more and HH:MM:SS below that, in 24-hour local time. The first
 * label on the axis, and any tick whose local date differs from the tick before it
 * (a midnight crossing), shows the date instead ("Fri 13 Nov"). The crosshair time
 * tag is not an axis label and keeps full HH:MM:SS.
 *
 * Timestamps are epoch nanoseconds (P17-0 F1). Ticks are whole multiples of the
 * step, counted in step units so the index stays a safe integer.
 */

import { formatClockNanos } from "./format";

/**
 * Round clock steps in seconds. The decision lists 5s to 1h; the hour multiples past
 * it keep the 80px rule at the widest zoom-out of 5m bars (500 bars of 3px is about
 * 42 hours across 1500px, where 1h ticks would be 36px apart).
 */
export const TICK_STEPS_SEC: readonly number[] = [
    5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400,
];

/** Labels sit at least this far apart. */
export const MIN_LABEL_GAP_PX = 80;

const NS = 1_000_000_000;

/**
 * The smallest round step whose labels sit at least `minGapPx` apart at
 * `pxPerSec`, restricted to whole multiples of `multipleOfSec` (the bar size, so a
 * candle tick always lands on a bar boundary). Falls back to the largest allowed
 * step when even that is too dense.
 */
export function pickTickStep(pxPerSec: number, multipleOfSec = 1, minGapPx = MIN_LABEL_GAP_PX): number {
    let fallback = TICK_STEPS_SEC[TICK_STEPS_SEC.length - 1];
    for (const s of TICK_STEPS_SEC) {
        if (s % multipleOfSec !== 0) continue;
        fallback = s;
        if (pxPerSec > 0 && s * pxPerSec >= minGapPx) return s;
    }
    return fallback;
}

/** Epoch-nanosecond ticks on whole multiples of `stepSec` inside [t0, t1]. */
export function clockTicks(t0: number, t1: number, stepSec: number): readonly number[] {
    if (!(t1 >= t0) || !(stepSec > 0)) return [];
    const stepNs = stepSec * NS;
    const out: number[] = [];
    for (let k = Math.ceil(t0 / stepNs); k <= Math.floor(t1 / stepNs); k++) out.push(k * stepNs);
    return out;
}

const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function localDate(nanos: number): Date {
    return new Date(Math.floor(nanos / 1_000_000));
}

/** The date tile's text, local time: "Fri 13 Nov". */
export function formatDateLabel(nanos: number): string {
    const d = localDate(nanos);
    return `${WEEKDAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}`;
}

/** A local calendar day key, for spotting a midnight crossing. */
export function localDayKey(nanos: number): number {
    const d = localDate(nanos);
    return d.getFullYear() * 10_000 + (d.getMonth() + 1) * 100 + d.getDate();
}

/** HH:MM at a step of a minute or more, HH:MM:SS below. 24-hour local time. */
export function formatTickTime(nanos: number, stepSec: number): string {
    const hms = formatClockNanos(nanos, "s");
    return stepSec >= 60 ? hms.slice(0, 5) : hms;
}

export interface TickLabel {
    readonly t: number;
    readonly text: string;
    /** True when the label is the date tile (the first tick, or a midnight crossing). */
    readonly date: boolean;
}

/** Label each tick: the first and any midnight crossing as the date, the rest as clock time. */
export function labelTicks(ticks: readonly number[], stepSec: number): readonly TickLabel[] {
    return ticks.map((t, i) => {
        const date = i === 0 || localDayKey(t) !== localDayKey(ticks[i - 1]);
        return { t, text: date ? formatDateLabel(t) : formatTickTime(t, stepSec), date };
    });
}
