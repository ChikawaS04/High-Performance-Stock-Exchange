/**
 * Pure bars module for the candle chart (P17-2). No React, no reducer import.
 *
 * Every fill is folded into open, high, low, close, volume bars at four sizes (5s,
 * 15s, 1m, 5m). The reducer holds one series per size (P17 D2) so switching size is
 * instant and each size keeps its own full history, independent of the TAPE_CAP
 * print tape.
 *
 * Bucketing (P17 D3, amended at P17-0): EXEC timestamps are epoch nanoseconds (the
 * venue's EpochNanoClock stamps the journaled command), so buckets align to the
 * clock: bucket = floor(t / sizeNs). A 15s bar opens at :00, :15, :30 and :45 and a
 * 1m bar on the minute. There is no session anchor. At 5s the bucket index is about
 * 3.5e8, a safe integer. Timestamps near 1.75e18 arrive as doubles spaced about
 * 256 ns apart, far finer than any bar size.
 *
 * A series is { open, closed }. A fill inside the open bar's bucket replaces only the
 * small open object; the closed array is copied only when a bar closes, so an
 * in-bucket fill keeps the closed reference. closed is capped at BAR_CAP, oldest
 * dropped. A fill whose bucket is older than the open bar (possible only after a
 * venue restart while the tab stays open, F2) is folded into the open bar rather
 * than reopening a closed one, so the fold never throws. No synthetic bars are made
 * for empty buckets: a quiet stretch is an honest gap.
 *
 * Order flow (P19-1, Phase 19 D1 and D2). Every fill carries the aggressor's side, so
 * each bar splits its volume into buyVolume (buyer-initiated, offers lifted) and
 * sellVolume (seller-initiated, bids hit). A fill whose side is unknown (a frame
 * without aggressorSide, P19-0 F3) still counts in volume but in neither side, so
 * buyVolume + sellVolume <= volume, with equality whenever every fill is sided, as
 * every live fill is. Each bar also records cvd, the session delta (signed aggressor
 * quantity) after its last fill, stamped exactly as vwap is, so a CVD line is right at
 * any pan offset and after bars age out.
 *
 * The 1m and 5m series also keep per-price detail for the footprint chart: levels,
 * one { price, buy, sell } per exact traded price in units, sorted by price
 * ascending. A half-cent print (a midpoint peg, P19-0 F13) keeps its exact key;
 * grouping into rows happens only at the read. To bound memory only the newest
 * FOOTPRINT_CAP bars of a series, the open bar included, keep levels; when a bar
 * closes, the one that passes the cap is replaced by a copy without them and keeps
 * its totals.
 */

import type { Side } from "./protocol/messages";

export type BarSizeId = "5s" | "15s" | "1m" | "5m";

const SECOND_NS = 1_000_000_000;

/** The four bar sizes in display order, with their lengths in nanoseconds. */
export const BAR_SIZES: readonly { readonly id: BarSizeId; readonly ns: number }[] = [
    { id: "5s", ns: 5 * SECOND_NS },
    { id: "15s", ns: 15 * SECOND_NS },
    { id: "1m", ns: 60 * SECOND_NS },
    { id: "5m", ns: 300 * SECOND_NS },
];

export const BAR_SIZE_IDS: readonly BarSizeId[] = BAR_SIZES.map((s) => s.id);

/** Length of a bar size in nanoseconds. */
export function barSizeNs(id: BarSizeId): number {
    switch (id) {
        case "5s":
            return 5 * SECOND_NS;
        case "15s":
            return 15 * SECOND_NS;
        case "1m":
            return 60 * SECOND_NS;
        case "5m":
            return 300 * SECOND_NS;
    }
}

/** Closed bars kept per size, oldest dropped. About 41 minutes at 5s, 41 hours at 5m. */
export const BAR_CAP = 500;

/**
 * Bars per footprint-capable series that keep per-price levels, the open bar included
 * (P19 D2). Two hours at 1m, ten at 5m. P19-0 F14 measured about 0.3 MiB for both
 * series at the bots' fill rate and 8 MiB at about 25 times the price density
 * (indicative Node heap figures).
 */
export const FOOTPRINT_CAP = 120;

/** The bar sizes that keep per-price levels, and so the only footprint sizes (P19 D2, D6). */
export const FOOTPRINT_SIZES: readonly BarSizeId[] = ["1m", "5m"];

export function hasFootprint(id: BarSizeId): boolean {
    return FOOTPRINT_SIZES.includes(id);
}

/** Aggressor volume at one exact price in a bar (P19 D2). */
export interface PriceLevel {
    /** Integer units of $0.0001, exactly as printed. */
    readonly price: number;
    /** Buyer-initiated shares (offers lifted). */
    readonly buy: number;
    /** Seller-initiated shares (bids hit). */
    readonly sell: number;
}

/**
 * "No VWAP yet": no volume has traded. Matches the reducer's SESSION_OPEN_UNSET (-1)
 * so formatPrice renders it as the dash; declared here so this module imports
 * nothing from the reducer.
 */
export const VWAP_UNSET = -1;

export interface Bar {
    /** Clock-aligned bucket index: floor(t / sizeNs) on the epoch timestamp. */
    readonly bucket: number;
    /** Prices in integer units of $0.0001. */
    readonly open: number;
    readonly high: number;
    readonly low: number;
    readonly close: number;
    /** Shares traded in the bar. */
    readonly volume: number;
    /** Fill count. */
    readonly trades: number;
    /** Session VWAP after this bar's last fill, in units, rounded half up. */
    readonly vwap: number;
    /** Buyer-initiated shares: fills whose aggressor bought (P19 D2). */
    readonly buyVolume: number;
    /** Seller-initiated shares: fills whose aggressor sold. */
    readonly sellVolume: number;
    /** Session delta (buy minus sell aggressor shares) after this bar's last fill (P19 D5). */
    readonly cvd: number;
    /**
     * Per-price aggressor volume, sorted by price ascending. Present only on the 1m and
     * 5m series and only on their newest FOOTPRINT_CAP bars; absent means no detail.
     */
    readonly levels?: readonly PriceLevel[];
}

export interface BarSeries {
    readonly open: Bar | null;
    readonly closed: readonly Bar[];
}

export type BarSet = Readonly<Record<BarSizeId, BarSeries>>;

const EMPTY_CLOSED: readonly Bar[] = [];

export function emptySeries(): BarSeries {
    return { open: null, closed: EMPTY_CLOSED };
}

export function emptyBars(): BarSet {
    return { "5s": emptySeries(), "15s": emptySeries(), "1m": emptySeries(), "5m": emptySeries() };
}

/** Clock-aligned bucket of an epoch-nanosecond timestamp. */
export function bucketOf(t: number, sizeNs: number): number {
    return Math.floor(t / sizeNs);
}

/** Epoch-nanosecond start of a bucket. */
export function bucketStart(bucket: number, sizeNs: number): number {
    return bucket * sizeNs;
}

/**
 * Add one fill to a sorted level list and return a new list. Pure: the input is never
 * mutated. Binary search for the exact price; a new price is inserted in order.
 */
export function foldLevel(levels: readonly PriceLevel[], price: number, qty: number, side: Side): readonly PriceLevel[] {
    let lo = 0;
    let hi = levels.length;
    while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (levels[mid].price < price) lo = mid + 1;
        else hi = mid;
    }
    const out = levels.slice();
    const buy = side === "BUY" ? qty : 0;
    const sell = side === "SELL" ? qty : 0;
    if (lo < levels.length && levels[lo].price === price) {
        const l = levels[lo];
        out[lo] = { price, buy: l.buy + buy, sell: l.sell + sell };
    } else {
        out.splice(lo, 0, { price, buy, sell });
    }
    return out;
}

/** A bar without its per-price detail, totals kept (the bar has aged past FOOTPRINT_CAP). */
export function withoutLevels(bar: Bar): Bar {
    if (bar.levels === undefined) return bar;
    return {
        bucket: bar.bucket,
        open: bar.open,
        high: bar.high,
        low: bar.low,
        close: bar.close,
        volume: bar.volume,
        trades: bar.trades,
        vwap: bar.vwap,
        buyVolume: bar.buyVolume,
        sellVolume: bar.sellVolume,
        cvd: bar.cvd,
    };
}

const NO_LEVELS: readonly PriceLevel[] = [];

/** The first bar of a bucket. */
function openBar(
    bucket: number,
    price: number,
    qty: number,
    vwap: number,
    side: Side | null,
    cvd: number,
    withLevels: boolean,
): Bar {
    const bar: Bar = {
        bucket,
        open: price,
        high: price,
        low: price,
        close: price,
        volume: qty,
        trades: 1,
        vwap,
        buyVolume: side === "BUY" ? qty : 0,
        sellVolume: side === "SELL" ? qty : 0,
        cvd,
    };
    if (!withLevels) return bar;
    return { ...bar, levels: side === null ? NO_LEVELS : foldLevel(NO_LEVELS, price, qty, side) };
}

/**
 * Fold one fill into a series and return the new series. Pure. `vwap` is the
 * session VWAP after this fill and `cvd` the session delta after it, both of which
 * the open bar records. `side` is the aggressor's side, null when unknown (counted
 * in volume only). `withLevels` keeps per-price levels (the 1m and 5m series).
 */
export function foldFill(
    series: BarSeries,
    sizeNs: number,
    t: number,
    price: number,
    qty: number,
    vwap: number,
    side: Side | null = null,
    cvd = 0,
    withLevels = false,
): BarSeries {
    const bucket = bucketOf(t, sizeNs);
    const open = series.open;

    if (open === null) {
        return { open: openBar(bucket, price, qty, vwap, side, cvd, withLevels), closed: series.closed };
    }

    // Same bucket, or an out-of-order older one (F2): fold into the open bar, which
    // keeps its own bucket. Only the open object is replaced.
    if (bucket <= open.bucket) {
        const next: Bar = {
            bucket: open.bucket,
            open: open.open,
            high: Math.max(open.high, price),
            low: Math.min(open.low, price),
            close: price,
            volume: open.volume + qty,
            trades: open.trades + 1,
            vwap,
            buyVolume: open.buyVolume + (side === "BUY" ? qty : 0),
            sellVolume: open.sellVolume + (side === "SELL" ? qty : 0),
            cvd,
        };
        if (!withLevels) return { open: next, closed: series.closed };
        const levels = open.levels ?? NO_LEVELS;
        return {
            open: { ...next, levels: side === null ? levels : foldLevel(levels, price, qty, side) },
            closed: series.closed,
        };
    }

    // A later bucket: the open bar closes and a new one opens. Gap buckets get no bar.
    const closed =
        series.closed.length >= BAR_CAP
            ? [...series.closed.slice(series.closed.length - BAR_CAP + 1), open]
            : [...series.closed, open];
    // The new open bar takes one of the FOOTPRINT_CAP places with detail, so the closed
    // bar FOOTPRINT_CAP from the end passes the cap and drops its levels. One bar
    // closes per fold, so every older bar is already without levels.
    const passing = closed.length - FOOTPRINT_CAP;
    if (passing >= 0 && closed[passing].levels !== undefined) closed[passing] = withoutLevels(closed[passing]);
    return { open: openBar(bucket, price, qty, vwap, side, cvd, withLevels), closed };
}

/**
 * Fold one fill into all four sizes. The 1m and 5m series keep per-price levels
 * (FOOTPRINT_SIZES). `side` and `cvd` as foldFill.
 */
export function foldBars(
    set: BarSet,
    t: number,
    price: number,
    qty: number,
    vwap: number,
    side: Side | null = null,
    cvd = 0,
): BarSet {
    return {
        "5s": foldFill(set["5s"], barSizeNs("5s"), t, price, qty, vwap, side, cvd, false),
        "15s": foldFill(set["15s"], barSizeNs("15s"), t, price, qty, vwap, side, cvd, false),
        "1m": foldFill(set["1m"], barSizeNs("1m"), t, price, qty, vwap, side, cvd, true),
        "5m": foldFill(set["5m"], barSizeNs("5m"), t, price, qty, vwap, side, cvd, true),
    };
}

/** Closed bars then the open bar, oldest first. Allocates; for the render edge only. */
export function seriesBars(series: BarSeries): readonly Bar[] {
    return series.open === null ? series.closed : [...series.closed, series.open];
}

/**
 * Session VWAP in integer units: notional / volume, rounded half up to the nearest
 * $0.0001 unit, computed exactly in BigInt so a notional past
 * Number.MAX_SAFE_INTEGER loses nothing. VWAP_UNSET at zero volume.
 *
 * Rounding is deliberate and allowed here: VWAP is a statistic, never an order
 * price. The rule that nothing rounds a price (CLAUDE.md) is about limit prices,
 * which must arrive on the tick; no order is ever priced from this value.
 */
export function vwapUnits(notional: bigint, volume: number): number {
    if (volume <= 0) return VWAP_UNSET;
    const v = BigInt(volume);
    // Half up for non-negative values: floor((2N + V) / 2V).
    return Number((2n * notional + v) / (2n * v));
}
