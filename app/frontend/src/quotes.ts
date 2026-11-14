/**
 * Best-quote history (P18 D6, amended at P18-0 F5). Pure: no React, no reducer
 * import. The reducer keeps only the current book; the bid/ask lines need the best
 * quotes over time, so this module folds every change of the best bid or best ask.
 *
 * Two shapes, because one raw cap cannot serve both chart types:
 *
 *  - The ring: every change, { t, bid, ask }, oldest first, capped at QUOTE_CAP.
 *    Line mode draws it on the tape's time domain. At the measured 0.22 changes a
 *    second (4 at most, P18-0) 5,000 points span hours, far more than the 200-print
 *    tape the line chart shows. A change copies the array; at that rate the copy
 *    costs nothing measurable, and an unchanged quote copies nothing.
 *  - The bucketed series: per bar size, the last quote in each clock-aligned bucket,
 *    folded exactly like the bars (an open point plus a closed list capped at
 *    BAR_CAP), so the quote history always spans every bar the candle view can show.
 *    A change inside the open bucket replaces only the open point.
 *
 * Prices are units; -1 marks an empty side. A bucket with no change has no entry:
 * the drawing carries the previous quote forward (the step holds), and only an
 * explicit -1 breaks a line. A socket drop appends one point with both sides empty,
 * so the line breaks honestly instead of bridging the outage.
 */

import { BAR_CAP, barSizeNs, bucketOf } from "./bars";
import type { BarSizeId } from "./bars";

/** Raw quote changes kept, oldest dropped. */
export const QUOTE_CAP = 5000;

/** The -1 empty-side sentinel, as on the wire. */
export const NO_QUOTE = -1;

export interface QuotePoint {
    /** Epoch nanoseconds of the BOOK frame that changed the quote. */
    readonly t: number;
    readonly bid: number;
    readonly ask: number;
}

export interface QuoteBucket {
    readonly bucket: number;
    /** The last best bid and ask in the bucket; -1 for an empty side. */
    readonly bid: number;
    readonly ask: number;
}

export interface QuoteSeries {
    readonly open: QuoteBucket | null;
    readonly closed: readonly QuoteBucket[];
}

export type QuoteSet = Readonly<Record<BarSizeId, QuoteSeries>>;

const EMPTY_CLOSED: readonly QuoteBucket[] = [];

export function emptyQuoteSeries(): QuoteSeries {
    return { open: null, closed: EMPTY_CLOSED };
}

export function emptyQuoteSet(): QuoteSet {
    return { "5s": emptyQuoteSeries(), "15s": emptyQuoteSeries(), "1m": emptyQuoteSeries(), "5m": emptyQuoteSeries() };
}

/** The newest point in the ring, or null. */
export function lastQuote(ring: readonly QuotePoint[]): QuotePoint | null {
    return ring.length === 0 ? null : ring[ring.length - 1];
}

/** True when (bid, ask) differs from the ring's newest point (or the ring is empty). */
export function quoteChanged(ring: readonly QuotePoint[], bid: number, ask: number): boolean {
    const last = lastQuote(ring);
    return last === null || last.bid !== bid || last.ask !== ask;
}

/** Append a point, oldest dropped past QUOTE_CAP. */
export function appendQuote(ring: readonly QuotePoint[], p: QuotePoint): readonly QuotePoint[] {
    return ring.length >= QUOTE_CAP ? [...ring.slice(ring.length - QUOTE_CAP + 1), p] : [...ring, p];
}

/**
 * Fold one quote into a bucketed series at `bucket`. Same bucket (or an older one,
 * the out-of-order guard the bars use): the open point is replaced. A later
 * bucket: the open point closes and a new one opens. Gap buckets get no entry.
 */
export function foldQuoteAt(series: QuoteSeries, bucket: number, bid: number, ask: number): QuoteSeries {
    const open = series.open;
    if (open === null) return { open: { bucket, bid, ask }, closed: series.closed };
    if (bucket <= open.bucket) return { open: { bucket: open.bucket, bid, ask }, closed: series.closed };
    const closed =
        series.closed.length >= BAR_CAP ? [...series.closed.slice(series.closed.length - BAR_CAP + 1), open] : [...series.closed, open];
    return { open: { bucket, bid, ask }, closed };
}

const SIZES: readonly BarSizeId[] = ["5s", "15s", "1m", "5m"];

function foldAll(set: QuoteSet, bucketFor: (sizeNs: number) => number, bid: number, ask: number): QuoteSet {
    const out = {} as Record<BarSizeId, QuoteSeries>;
    for (const id of SIZES) out[id] = foldQuoteAt(set[id], bucketFor(barSizeNs(id)), bid, ask);
    return out;
}

/** Fold a quote change at time `t` into all four sizes. */
export function foldQuotes(set: QuoteSet, t: number, bid: number, ask: number): QuoteSet {
    return foldAll(set, (ns) => bucketOf(t, ns), bid, ask);
}

/**
 * The outage marker in the bucketed series: an empty quote in the bucket AFTER the
 * one holding `t` (the last frame before the drop), so that bucket keeps its last
 * real quote and the line breaks from the next bucket on, until a new quote.
 */
export function foldQuoteBreak(set: QuoteSet, t: number): QuoteSet {
    return foldAll(set, (ns) => bucketOf(t, ns) + 1, NO_QUOTE, NO_QUOTE);
}

/** Closed buckets then the open one, oldest first. Allocates; for the render edge only. */
export function quoteBuckets(series: QuoteSeries): readonly QuoteBucket[] {
    return series.open === null ? series.closed : [...series.closed, series.open];
}
