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
 */

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
 * Fold one fill into a series and return the new series. Pure. `vwap` is the
 * session VWAP after this fill, which the open bar records.
 */
export function foldFill(
    series: BarSeries,
    sizeNs: number,
    t: number,
    price: number,
    qty: number,
    vwap: number,
): BarSeries {
    const bucket = bucketOf(t, sizeNs);
    const open = series.open;

    if (open === null) {
        return {
            open: { bucket, open: price, high: price, low: price, close: price, volume: qty, trades: 1, vwap },
            closed: series.closed,
        };
    }

    // Same bucket, or an out-of-order older one (F2): fold into the open bar, which
    // keeps its own bucket. Only the open object is replaced.
    if (bucket <= open.bucket) {
        return {
            open: {
                bucket: open.bucket,
                open: open.open,
                high: Math.max(open.high, price),
                low: Math.min(open.low, price),
                close: price,
                volume: open.volume + qty,
                trades: open.trades + 1,
                vwap,
            },
            closed: series.closed,
        };
    }

    // A later bucket: the open bar closes and a new one opens. Gap buckets get no bar.
    const closed =
        series.closed.length >= BAR_CAP
            ? [...series.closed.slice(series.closed.length - BAR_CAP + 1), open]
            : [...series.closed, open];
    return {
        open: { bucket, open: price, high: price, low: price, close: price, volume: qty, trades: 1, vwap },
        closed,
    };
}

/** Fold one fill into all four sizes. */
export function foldBars(set: BarSet, t: number, price: number, qty: number, vwap: number): BarSet {
    return {
        "5s": foldFill(set["5s"], barSizeNs("5s"), t, price, qty, vwap),
        "15s": foldFill(set["15s"], barSizeNs("15s"), t, price, qty, vwap),
        "1m": foldFill(set["1m"], barSizeNs("1m"), t, price, qty, vwap),
        "5m": foldFill(set["5m"], barSizeNs("5m"), t, price, qty, vwap),
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
