/**
 * Pure session-statistics helper for the Price Chart side panel (P13-2).
 *
 * Mirrors priceSeries.ts and depth.ts: one pure function, no React, no pixels,
 * directly unit tested. Where those two emit integer domain units for a component
 * to scale, this one emits finished DISPLAY STRINGS, because its consumer renders
 * text rather than geometry. Keeping the formatting here is what lets SessionStats
 * stay a dumb four-row table with no conditionals in it: every degenerate case
 * (unset sentinel, empty tape, zero count) is decided and pinned in one place.
 *
 * Input shape follows deriveHeader's composite rather than taking the whole
 * AppState: the function reads four slices, so it declares four slices, and a test
 * can construct an input without building a reducer state.
 *
 * Two different kinds of number live here, and they take different formatters:
 *
 *  - PRICES go through formatPrice, which already renders any negative value as
 *    the EMPTY_PRICE dash. SESSION_OPEN_UNSET is negative by design, so an unset
 *    high or low formats correctly with no branch here at all.
 *  - COUNTS AND SIZES go through formatQty, the same grouped integer formatter the
 *    header Volume uses. Zero is a real value and renders "0", never the dash:
 *    "no trades yet" is a known fact, not missing data (P13 D10). Using formatQty
 *    rather than String() is what keeps a four-digit trade count from disagreeing
 *    with the grouped Volume sitting in the strip directly above it.
 *
 * Last trade reads tape[0] and needs no accumulator: the tape is newest-first and
 * the TAPE_CAP cap only ever evicts from the tail, so the newest row is always
 * present when the tape is non-empty. An empty tape is the one case it must handle,
 * and it yields the dash for both price and size.
 */

import { formatPrice, EMPTY_PRICE, formatQty } from "./format";
import type { TapeEntry } from "./state/reducer";

/**
 * The four reducer slices the panel reads. Prices are integer units of $0.0001,
 * with SESSION_OPEN_UNSET for "no trade has printed this session".
 */
export interface SessionStatsInput {
    readonly tape: readonly TapeEntry[];
    readonly sessionHighPx: number;
    readonly sessionLowPx: number;
    readonly sessionTradeCount: number;
}

/**
 * Render-ready strings, one per panel row (last trade contributes two). Every
 * field is always a string: there is no undefined and no null to branch on, so
 * the component mounts a stable set of rows from first paint (P13 D10).
 */
export interface SessionStatsModel {
    readonly high: string;
    readonly low: string;
    readonly trades: string;
    readonly lastPrice: string;
    readonly lastSize: string;
}

/**
 * Build the four display rows from session state. Pure and total: no input value
 * can throw, and no input value yields NaN or "undefined" in the output.
 *
 *   { tape: [print at 1502500 x 4], high: 1507500, low: 1492500, trades: 12 }
 *     -> { high: "150.75", low: "149.25", trades: "12", lastPrice: "150.25", lastSize: "4" }
 *
 *   { tape: [], high: -1, low: -1, trades: 0 }
 *     -> { high: "—", low: "—", trades: "0", lastPrice: "—", lastSize: "—" }
 */
export function buildSessionStats(input: SessionStatsInput): SessionStatsModel {
    const { tape, sessionHighPx, sessionLowPx, sessionTradeCount } = input;

    // Newest-first, so the latest print is index 0. undefined only when no trade has
    // printed, which is the sole empty case either last-trade field has to handle.
    const last = tape.length > 0 ? tape[0] : undefined;

    return {
        // No sentinel branch: formatPrice maps every negative value to the dash.
        high: formatPrice(sessionHighPx),
        low: formatPrice(sessionLowPx),
        // A count, not a price: zero is real and renders "0".
        trades: formatQty(sessionTradeCount),
        lastPrice: last !== undefined ? formatPrice(last.pricePx) : EMPTY_PRICE,
        lastSize: last !== undefined ? formatQty(last.quantity) : EMPTY_PRICE,
    };
}
