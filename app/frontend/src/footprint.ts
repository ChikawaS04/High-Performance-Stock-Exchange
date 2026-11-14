/**
 * Pure footprint layout (P19-5, Phase 19 D6 as amended at P19-0 and at greenlight).
 * No React, no DOM.
 *
 * A footprint bar is a column of price rows, each showing sell x buy: shares sold
 * by aggressors hitting bids, and shares bought by aggressors lifting offers. The
 * input is a bar's per-price levels (bars.ts, P19-1), kept only on the 1m and 5m
 * series and only on their newest FOOTPRINT_CAP bars. A bar without levels has no
 * detail and is flagged noDetail; the chart draws it as a plain outline.
 *
 * Rows. A 1m bar spans about 45 ticks but trades at only about 9 prices (P19-0 F15),
 * so one row per tick is mostly empty and too short for text. Rows are grouped at
 * the read into N ticks, N the smallest of ROW_STEPS whose row is at least
 * FOOTPRINT_ROW_MIN_PX tall at the current scale, aligned to multiples of N ticks. A
 * half-cent key falls into the row whose range contains it. Within a bar the rows run
 * contiguously from its lowest to its highest traded row, empty rows included, so the
 * diagonal neighbours are well defined. Row index r ascends with price: row 0 is the
 * lowest.
 *
 * Diagonal imbalance (the standard footprint rule). Buyers lifting offers at row r are
 * compared with sellers hitting bids one row below (r - 1); sellers at row r with
 * buyers one row above (r + 1):
 *  - buy imbalance at r: buy[r] >= IMBALANCE_RATIO x sell[r - 1]
 *  - sell imbalance at r: sell[r] >= IMBALANCE_RATIO x buy[r + 1]
 * When the diagonal opposite is empty (zero, or no row there at the bar's edge) the
 * ratio means nothing, so the row is flagged only when its dominant side is at least
 * IMBALANCE_MIN_QTY shares. With sparse rows the opposite is often empty, and a sweep
 * through thin prices would otherwise never show. A side with no shares is never an
 * imbalance.
 */

import type { Bar, PriceLevel } from "./bars";
import { TICK_PX as TICK } from "./prices";
import type { SlotBounds } from "./chartViewport";

/** Footprint slot widths in drawing pixels: the column needs room for "sell x buy" (P19 D6). */
export const FOOTPRINT_SLOT: SlotBounds = { min: 64, max: 160 };

/** Shortest footprint row: room for one line of 9px mono text. */
export const FOOTPRINT_ROW_MIN_PX = 13;

/** Ticks per footprint row, tried in order. */
export const ROW_STEPS: readonly number[] = [1, 2, 5, 10, 20, 50, 100, 200, 500];

/** The diagonal ratio a dominant side must reach (P19 D6). */
export const IMBALANCE_RATIO = 3;

/**
 * Shares the dominant side must reach to be flagged when its diagonal opposite is empty
 * (greenlight amendment 1). The bots' prints are mostly 10 to 200 shares (P19-0 F14).
 * Replaying the three recorded bot sessions through footprintBar (P19-5) at 1, 5 and
 * 10 ticks per row flagged, as a share of traded rows: never-flag 2 to 13%; a 100-share
 * minimum 42 to 77% (nearly every row of a fast bar); 200 shares 6 to 30%, with the
 * fast bars still showing a few flagged rows. 200 keeps a sweep visible without
 * flagging ordinary rows.
 */
export const IMBALANCE_MIN_QTY = 200;

export type Imbalance = "buy" | "sell" | null;

export interface FootprintRow {
    /** Row index, ascending with price (row 0 is the bar's lowest). */
    readonly r: number;
    /** Lowest price in units the row covers, inclusive. */
    readonly lo: number;
    /** Price in units just above the row, exclusive. */
    readonly hi: number;
    readonly buy: number;
    readonly sell: number;
    readonly imbalance: Imbalance;
}

export interface FootprintBar {
    readonly bar: Bar;
    /** True when the bar has aged past FOOTPRINT_CAP or is on a size without detail. */
    readonly noDetail: boolean;
    /** Rows ascending in price; empty when noDetail. */
    readonly rows: readonly FootprintRow[];
}

/** Ticks per row at `pxPerTick` drawing pixels per tick. */
export function footprintRowStep(pxPerTick: number, minRowPx = FOOTPRINT_ROW_MIN_PX): number {
    if (!(pxPerTick > 0)) return ROW_STEPS[ROW_STEPS.length - 1];
    for (const n of ROW_STEPS) if (n * pxPerTick >= minRowPx) return n;
    return ROW_STEPS[ROW_STEPS.length - 1];
}

/** The lowest price of the `stepTicks` row holding `price` (floor to the row). */
export function rowLoOf(price: number, stepTicks: number): number {
    const width = stepTicks * TICK;
    return Math.floor(price / width) * width;
}

/** Levels grouped into contiguous rows of `stepTicks`, ascending; empty for no levels. */
export function groupRows(levels: readonly PriceLevel[], stepTicks: number): readonly { lo: number; hi: number; buy: number; sell: number }[] {
    if (levels.length === 0) return [];
    const width = stepTicks * TICK;
    const first = rowLoOf(levels[0].price, stepTicks);
    const last = rowLoOf(levels[levels.length - 1].price, stepTicks);
    const n = (last - first) / width + 1;
    const rows = Array.from({ length: n }, (_, i) => ({ lo: first + i * width, hi: first + (i + 1) * width, buy: 0, sell: 0 }));
    for (const l of levels) {
        const row = rows[(rowLoOf(l.price, stepTicks) - first) / width];
        row.buy += l.buy;
        row.sell += l.sell;
    }
    return rows;
}

/** Is `dominant` an imbalance against its diagonal `opposite` (0 when empty)? */
export function isImbalance(dominant: number, opposite: number, ratio = IMBALANCE_RATIO, minQty = IMBALANCE_MIN_QTY): boolean {
    if (dominant <= 0) return false;
    if (opposite <= 0) return dominant >= minQty;
    return dominant >= ratio * opposite;
}

/** The imbalance of each row of a contiguous ascending row list. */
export function imbalances(
    rows: readonly { readonly buy: number; readonly sell: number }[],
    ratio = IMBALANCE_RATIO,
    minQty = IMBALANCE_MIN_QTY,
): readonly Imbalance[] {
    return rows.map((row, r) => {
        const below = r > 0 ? rows[r - 1].sell : 0;
        const above = r + 1 < rows.length ? rows[r + 1].buy : 0;
        const buyImb = isImbalance(row.buy, below, ratio, minQty);
        const sellImb = isImbalance(row.sell, above, ratio, minQty);
        if (buyImb && sellImb) return row.buy >= row.sell ? "buy" : "sell";
        if (buyImb) return "buy";
        if (sellImb) return "sell";
        return null;
    });
}

/** One bar's footprint at `stepTicks` per row. */
export function footprintBar(bar: Bar, stepTicks: number, ratio = IMBALANCE_RATIO, minQty = IMBALANCE_MIN_QTY): FootprintBar {
    if (bar.levels === undefined) return { bar, noDetail: true, rows: [] };
    const grouped = groupRows(bar.levels, stepTicks);
    const imb = imbalances(grouped, ratio, minQty);
    return {
        bar,
        noDetail: false,
        rows: grouped.map((g, r) => ({ r, lo: g.lo, hi: g.hi, buy: g.buy, sell: g.sell, imbalance: imb[r] })),
    };
}

/** Footprint columns that fit across `plotW` at `slotW` (at least one). */
export function footprintColumns(plotW: number, slotW: number = FOOTPRINT_SLOT.min): number {
    return Math.max(1, Math.floor(plotW / Math.max(FOOTPRINT_SLOT.min, slotW)));
}

/**
 * A cell quantity that fits a 64px column: digits without separators below 10,000
 * ("1234"), then thousands ("12K", "999K"), then millions with one decimal ("1.2M").
 * Two of them with " x " stay within 11 characters, about 59px at 9px mono.
 */
export function compactQty(n: number): string {
    const a = Math.abs(Math.trunc(n));
    const sign = n < 0 ? "-" : "";
    if (a < 10_000) return `${sign}${a}`;
    if (a < 1_000_000) return `${sign}${Math.floor(a / 1000)}K`;
    return `${sign}${(Math.floor(a / 100_000) / 10).toFixed(1)}M`;
}

/** A cell's text: sell x buy (bid hit by ask lifted). */
export function cellText(row: { readonly sell: number; readonly buy: number }): string {
    return `${compactQty(row.sell)} x ${compactQty(row.buy)}`;
}

/** The row holding `price` in a footprint bar, or null outside its rows. */
export function rowAt(fb: FootprintBar, price: number): FootprintRow | null {
    for (const row of fb.rows) if (price >= row.lo && price < row.hi) return row;
    return null;
}
