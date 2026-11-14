/**
 * Pure volume profile (P19-3, Phase 19 D3 and D4). No React, no DOM.
 *
 * Input is the reducer's session volume at price (AppState.volumeAtPrice): shares at
 * each exact price in integer units since the tab loaded. Nothing here is a fold over
 * bars, so the profile does not age out with them.
 *
 * Two levels of rows:
 *  - The tick ladder: one row per one-cent tick (TICK units) from the lowest to the
 *    highest traded tick, untraded ticks included at zero. A half-cent key (a
 *    midpoint peg print) falls into the tick row below it (floor to the tick). The
 *    point of control and the value area are computed here, on the full session
 *    distribution, so they never move with zoom or pan.
 *  - Display rows: the ladder grouped into rows of N ticks, N the smallest of
 *    ROW_STEPS whose row is at least MIN_ROW_PX tall at the current scale. Rows align
 *    to multiples of N ticks. Grouping happens here, at the read, never at the write.
 *    Widths are scaled to the largest display row of the whole session, not of the
 *    visible slice, so the visible rows are never re-weighted.
 *
 * Point of control: the tick with the most volume. On a tie, the tick nearest the
 * middle of the traded range wins, then the lower price.
 *
 * Value area (the standard two-row method): start at the POC; repeatedly compare the
 * combined volume of the next two ticks above with the next two below and add the
 * larger pair (a tie goes up); near an edge the pair is whatever ticks remain on that
 * side. Stop once the area holds at least VALUE_AREA_FRACTION of the volume. Integer
 * arithmetic throughout: the test is area * 10 >= total * 7.
 */

import { TICK_PX as TICK } from "../prices";

/** Share of the session volume the value area holds, in tenths (70%). */
export const VALUE_AREA_TENTHS = 7;

/** Shortest display row in drawing pixels (P19 D4). */
export const MIN_ROW_PX = 2;

/** Ticks per display row, tried in order. */
export const ROW_STEPS: readonly number[] = [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000];

/** Widest profile as a fraction of the plot width (P19 D4). */
export const PROFILE_WIDTH_FRACTION = 0.25;

/** The session distribution on the tick ladder. */
export interface TickLadder {
    /** Price in units of the lowest tick row. */
    readonly base: number;
    /** Volume per tick row, ascending in price: row i is base + i * TICK. */
    readonly volumes: readonly number[];
    readonly total: number;
}

export interface ProfileStats {
    readonly ladder: TickLadder;
    /** Price in units of the POC tick row. */
    readonly poc: number;
    /** Lowest and highest tick rows of the value area, in units, inclusive. */
    readonly vaLow: number;
    readonly vaHigh: number;
}

/** The tick row a price belongs to: floor to the tick. */
export function tickRowOf(price: number): number {
    return Math.floor(price / TICK) * TICK;
}

/** The tick ladder of a volume-at-price map; null when nothing has traded. */
export function tickLadder(vap: ReadonlyMap<number, number>): TickLadder | null {
    let lo = Infinity;
    let hi = -Infinity;
    let total = 0;
    for (const [price, qty] of vap) {
        if (qty <= 0) continue;
        const row = tickRowOf(price);
        if (row < lo) lo = row;
        if (row > hi) hi = row;
        total += qty;
    }
    if (total === 0) return null;
    const volumes = new Array<number>((hi - lo) / TICK + 1).fill(0);
    for (const [price, qty] of vap) {
        if (qty <= 0) continue;
        volumes[(tickRowOf(price) - lo) / TICK] += qty;
    }
    return { base: lo, volumes, total };
}

/** Index of the point of control on the ladder (see the module comment for ties). */
export function pocIndex(volumes: readonly number[]): number {
    let best = 0;
    const mid2 = volumes.length - 1; // twice the middle index, kept integer
    for (let i = 1; i < volumes.length; i++) {
        if (volumes[i] > volumes[best]) {
            best = i;
        } else if (volumes[i] === volumes[best]) {
            // Nearer the middle wins; at equal distance the lower (earlier) index stays.
            if (Math.abs(2 * i - mid2) < Math.abs(2 * best - mid2)) best = i;
        }
    }
    return best;
}

/** The value area as inclusive ladder indices, grown from `poc` by the two-row method. */
export function valueAreaIndices(volumes: readonly number[], poc: number, total: number): { readonly lo: number; readonly hi: number } {
    let lo = poc;
    let hi = poc;
    let area = volumes[poc];
    const n = volumes.length;
    while (area * 10 < total * VALUE_AREA_TENTHS && (lo > 0 || hi < n - 1)) {
        const upCount = Math.min(2, n - 1 - hi);
        const downCount = Math.min(2, lo);
        let up = 0;
        for (let k = 1; k <= upCount; k++) up += volumes[hi + k];
        let down = 0;
        for (let k = 1; k <= downCount; k++) down += volumes[lo - k];
        if (upCount > 0 && (downCount === 0 || up >= down)) {
            hi += upCount;
            area += up;
        } else {
            lo -= downCount;
            area += down;
        }
    }
    return { lo, hi };
}

/** POC and value area of the session; null when nothing has traded. */
export function profileStats(vap: ReadonlyMap<number, number>): ProfileStats | null {
    const ladder = tickLadder(vap);
    if (ladder === null) return null;
    const p = pocIndex(ladder.volumes);
    const va = valueAreaIndices(ladder.volumes, p, ladder.total);
    return {
        ladder,
        poc: ladder.base + p * TICK,
        vaLow: ladder.base + va.lo * TICK,
        vaHigh: ladder.base + va.hi * TICK,
    };
}

/** Ticks per display row for a scale of `pxPerTick` drawing pixels per tick. */
export function rowStepTicks(pxPerTick: number, minRowPx = MIN_ROW_PX): number {
    if (!(pxPerTick > 0)) return ROW_STEPS[ROW_STEPS.length - 1];
    for (const n of ROW_STEPS) if (n * pxPerTick >= minRowPx) return n;
    return ROW_STEPS[ROW_STEPS.length - 1];
}

export interface ProfileRow {
    /** Lowest price in units the row covers, inclusive. */
    readonly lo: number;
    /** Price in units just above the row, exclusive: lo + step * TICK. */
    readonly hi: number;
    readonly volume: number;
    /** The row holds the POC tick. */
    readonly poc: boolean;
    /** The row overlaps the value area. */
    readonly inValue: boolean;
}

export interface ProfileLayout {
    readonly stepTicks: number;
    /** Display rows that overlap [dMin, dMax] and hold volume, ascending in price. */
    readonly rows: readonly ProfileRow[];
    /** Largest display row of the whole session at this grouping: the width scale. */
    readonly maxVolume: number;
}

/**
 * Display rows for a price domain [dMin, dMax] drawn `heightPx` tall. Rows outside the
 * domain are not returned, but the width scale is the whole session's.
 */
export function profileRows(stats: ProfileStats, dMin: number, dMax: number, heightPx: number): ProfileLayout {
    const span = dMax - dMin;
    const pxPerTick = span > 0 ? (heightPx * TICK) / span : 0;
    const stepTicks = rowStepTicks(pxPerTick);
    const width = stepTicks * TICK;
    const { base, volumes } = stats.ladder;

    const grouped = new Map<number, number>();
    volumes.forEach((v, i) => {
        if (v === 0) return;
        const lo = Math.floor((base + i * TICK) / width) * width;
        grouped.set(lo, (grouped.get(lo) ?? 0) + v);
    });

    let maxVolume = 0;
    const rows: ProfileRow[] = [];
    for (const [lo, volume] of [...grouped.entries()].sort((a, b) => a[0] - b[0])) {
        if (volume > maxVolume) maxVolume = volume;
        const hi = lo + width;
        if (hi <= dMin || lo > dMax) continue;
        rows.push({
            lo,
            hi,
            volume,
            poc: stats.poc >= lo && stats.poc < hi,
            inValue: lo <= stats.vaHigh && hi > stats.vaLow,
        });
    }
    return { stepTicks, rows, maxVolume };
}
