/**
 * Pure Alpaca-snapshot parsing for the ignition price (P11-1).
 *
 * No network, no React. Turns an Alpaca snapshot object into the official market
 * open in long integer units of $0.0001, or null when the value is absent or
 * unusable.
 *
 * The open arrives as a USD JSON number (e.g. 149.8). It is converted to units at
 * the edge by rendering a two-decimal dollar string and reusing the audited
 * parsePrice parser, so the whole price path stays integer-only (never o * 10000
 * in floating point). Rounding to the nearest cent happens at the toFixed(2) edge;
 * equity opens are quoted to the cent, and any extra precision Alpaca returns is
 * rounded there. A missing dailyBar, a missing or non-number o, a non-finite o, an
 * o <= 0, or anything parsePrice rejects all yield null, which the header then
 * renders as the EMPTY_PRICE sentinel.
 */

import { parsePrice } from "../format";

/** The subset of the Alpaca snapshot we read: dailyBar.o (USD open). */
export interface AlpacaSnapshot {
    readonly dailyBar?: { readonly o?: unknown };
}

/** Official market open in long units of $0.0001 from an Alpaca snapshot, or null. */
export function parseIgnitionOpen(snapshot: unknown): number | null {
    const o = (snapshot as AlpacaSnapshot | null)?.dailyBar?.o;
    if (typeof o !== "number" || !Number.isFinite(o) || o <= 0) {
        return null;
    }
    return parsePrice(o.toFixed(2));
}
