/**
 * Presentational depth ladder (SRS §3.7 depth chart), refined in P7-4, made a
 * scrolling viewport in P9-2.
 *
 * Pure edge: it renders only from the authoritative BOOK slice plus one derived
 * scalar (the last trade price, for the divider). It owns no socket or hook and
 * never reads EXEC or anything derived from EXEC ordering (the load-bearing
 * Phase-4 constraint). The one piece of local state is the depth selector, which
 * now sizes each pane's scrolling viewport rather than slicing the book (P9-2);
 * the full book already in hand is always rendered, and a deeper side scrolls.
 *
 * Layout, top to bottom: a depth selector, a Price / Size / Total caption, the
 * ask side (highest price on top, best ask nearest the mid divider), a spread /
 * mid / last strip, then the bid side (best bid on top, nearest the divider).
 * Each row has three columns plus a CSS depth bar whose width is proportional to
 * CUMULATIVE quantity outward from the touch.
 *
 * Bar scaling: SHARED max across both sides of the FULL book (P9-2; was the
 * visible window in P7-4). Now that every level renders, a level's bar is stable
 * as you scroll and the deepest level saturates at 100%; this is also the scale
 * the DepthCurve already uses, so the two panels agree with no separate curve
 * change. buildLadder exposes the raw 0..1 shadeFraction and a 0..100 widthPct
 * derived from it; the component sizes the bar from widthPct.
 *
 * Units in, dollars only at this render edge via format.ts. No float price math.
 * midpointLabel is imported from format.ts so this divider and the P7-3 header
 * share one exact definition. cumulate moved to depth.ts in P7-5 so the
 * ladder and the depth curve share one cumulation. Sentinels never surface: rows
 * are always real levels (BOOK is a server-trimmed top-N prefix, 20 per side since
 * P9-1, replaced wholesale), and spread, mid, and last are each guarded before
 * formatting.
 */

import { useState, type CSSProperties } from "react";

import { cumulate, type CumLevel } from "../depth";
import { formatPrice, EMPTY_PRICE, midpointLabel } from "../format";
import type { BookState } from "../state/reducer";
import type { Level } from "../protocol/messages";

/** Selectable depth = the number of rows each pane shows at once, i.e. its
 *  scrolling viewport height (P9-2). The server now sends a top-N prefix of up to
 *  MAX_DEPTH_LEVELS = 20 levels per side (P9-1), so a book deeper than the selected
 *  value overflows the pane and scrolls; the selector is no longer a cap on how
 *  many levels render. */
const DEPTH_OPTIONS = [8, 10, 14] as const;
const DEFAULT_DEPTH = 10;

/** One rendered ladder row: real price, its quantity, cumulative depth, shading. */
export interface LadderRow {
    readonly pricePx: number;
    readonly qty: number;
    readonly cumQty: number;
    /** Cumulative depth as a fraction of the full book's largest cumulative value, 0..1. */
    readonly shadeFraction: number;
    /** shadeFraction rendered as a 0..100 width for the inline depth bar. */
    readonly widthPct: number;
}

/**
 * Both sides in touch-first order: bids highest-first, asks lowest-first (each
 * best-first from the touch). The bids pane renders this order top-down (best bid
 * on top); the asks pane renders it into a CSS column-reverse container (P9-2), so
 * the best ask paints at the bottom nearest the divider and deeper asks above it.
 */
export interface LadderModel {
    readonly asks: readonly LadderRow[];
    readonly bids: readonly LadderRow[];
}

/**
 * Pure depth-bar model. Unit-tested directly, separately from the component.
 *
 * `bids` arrive highest-first, `asks` lowest-first (both best-first). Each side is
 * cumulated from the touch outward over the WHOLE book it is given (P9-2 removed
 * the old depth slice; the book is already a server-trimmed top-N prefix). The
 * depth selector no longer enters here at all, so the shared max is the full
 * book's: cumulative depth is monotonic, so each side's total is its last element,
 * and the shared max is the larger of the two, guarded so an empty book yields
 * zero widths (never NaN). Every row carries the raw shadeFraction in [0,1] and
 * widthPct = shadeFraction * 100.
 *
 * Both sides are returned touch-first (no reverse): cumQty rises down each array.
 * The asks pane relies on CSS column-reverse to paint that array bottom-up, so the
 * best ask still lands nearest the divider.
 */
export function buildLadder(
    bids: readonly Level[],
    asks: readonly Level[],
): LadderModel {
    const bidCum = cumulate(bids);
    const askCum = cumulate(asks);

    const maxBid = bidCum.length > 0 ? bidCum[bidCum.length - 1].cumQty : 0;
    const maxAsk = askCum.length > 0 ? askCum[askCum.length - 1].cumQty : 0;
    const sharedMax = Math.max(maxBid, maxAsk);

    const withWidth = (r: CumLevel): LadderRow => {
        const shadeFraction = sharedMax > 0 ? r.cumQty / sharedMax : 0;
        return {
            pricePx: r.pricePx,
            qty: r.qty,
            cumQty: r.cumQty,
            shadeFraction,
            widthPct: shadeFraction * 100,
        };
    };

    // Both sides emitted touch-first (best-first). bids highest-first = display
    // order (best bid on top). asks lowest-first: the asks pane's CSS column-reverse
    // (P9-2) paints them bottom-up so the best ask lands at the divider, which is why
    // the model is no longer reversed here.
    const askRows = askCum.map(withWidth);
    const bidRows = bidCum.map(withWidth);

    return { asks: askRows, bids: bidRows };
}

/**
 * Spread for the mid divider. Guarded: computed only when BOTH tops are real.
 * A `-1` sentinel on either side must not reach the subtraction — e.g.
 * bestAsk 1500000 with bestBid -1 would yield 1500001 → "150.0001", a bogus spread
 * that formatPrice cannot catch because it is positive.
 */
export function spreadLabel(bestBid: number, bestAsk: number): string {
    if (bestBid > 0 && bestAsk > 0) {
        return formatPrice(bestAsk - bestBid);
    }
    return EMPTY_PRICE;
}

function renderRow(row: LadderRow, side: "ask" | "bid") {
    return (
        <div
            key={row.pricePx}
            className={`depth-ladder__row depth-ladder__row--${side}`}
            data-testid={`${side}-row`}
        >
            <div
                className="depth-ladder__bar"
                style={{ width: `${row.widthPct}%` }}
                aria-hidden="true"
            />
            <span className="depth-ladder__price">{formatPrice(row.pricePx)}</span>
            <span className="depth-ladder__qty">{row.qty}</span>
            <span className="depth-ladder__cum">{row.cumQty}</span>
        </div>
    );
}

interface DepthLadderProps {
    readonly book: BookState;
    /**
     * Last trade price in units for the divider's Last cell. It is the newest tape
     * print (App derives it as `tape[0].pricePx`), not part of the BOOK slice,
     * so `buildLadder` stays book-only and pure. Defaults to the -1 sentinel, which
     * renders blank, so the ladder is still valid before the first trade.
     */
    readonly lastPx?: number;
}

export function DepthLadder({ book, lastPx = -1 }: DepthLadderProps) {
    const [depth, setDepth] = useState<number>(DEFAULT_DEPTH);

    const { asks, bids } = buildLadder(book.bids, book.asks);
    // P9-2: --depth-rows is the viewport height in rows; the stylesheet multiplies
    // it by --depth-row-h for each pane's max-height. buildLadder renders the FULL
    // book now, so a side deeper than this overflows the pane and engages the P8-3
    // themed scrollbar. The value is a count, not pixels, so the row height stays
    // single-sourced in terminal.css (--depth-row-h).
    const paneStyle = { "--depth-rows": depth } as CSSProperties;
    const spread = spreadLabel(book.bestBid, book.bestAsk);
    const mid = midpointLabel(book.bestBid, book.bestAsk);
    const last = lastPx > 0 ? formatPrice(lastPx) : EMPTY_PRICE;

    return (
        <div className="depth-ladder">
            <div className="depth-ladder__controls">
                <label className="depth-ladder__depth-label" htmlFor="depth-select">
                    Depth
                </label>
                <select
                    id="depth-select"
                    className="depth-ladder__depth"
                    data-testid="depth-select"
                    value={depth}
                    onChange={(e) => setDepth(Number(e.target.value))}
                >
                    {DEPTH_OPTIONS.map((n) => (
                        <option key={n} value={n}>
                            {n}
                        </option>
                    ))}
                </select>
            </div>

            <div className="depth-ladder__head" aria-hidden="true">
                <span className="depth-ladder__head-price">Price</span>
                <span className="depth-ladder__head-qty">Size</span>
                <span className="depth-ladder__head-cum">Total</span>
            </div>

            <div className="depth-ladder__asks" style={paneStyle}>
                {asks.map((row) => renderRow(row, "ask"))}
            </div>

            <div className="depth-ladder__divider">
        <span className="depth-ladder__divider-cell">
          <span className="depth-ladder__divider-label">Spread</span>
          <span className="depth-ladder__divider-value" data-testid="spread-value">
            {spread}
          </span>
        </span>
                <span className="depth-ladder__divider-cell">
          <span className="depth-ladder__divider-label">Mid</span>
          <span className="depth-ladder__divider-value" data-testid="mid-value">
            {mid}
          </span>
        </span>
                <span className="depth-ladder__divider-cell">
          <span className="depth-ladder__divider-label">Last</span>
          <span className="depth-ladder__divider-value" data-testid="last-value">
            {last}
          </span>
        </span>
            </div>

            <div className="depth-ladder__bids" style={paneStyle}>
                {bids.map((row) => renderRow(row, "bid"))}
            </div>
        </div>
    );
}
