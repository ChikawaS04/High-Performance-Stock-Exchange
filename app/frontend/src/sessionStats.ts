/**
 * Pure session-statistics helper for the Price Chart side panel (P13-2).
 *
 * Mirrors priceSeries.ts and depth.ts: one pure function, no React, no pixels,
 * directly unit tested. Where those two emit integer domain units for a component
 * to scale, this one emits finished DISPLAY STRINGS, because its consumer renders
 * text rather than geometry. Keeping the formatting here is what lets SessionStats
 * stay a dumb row table with no conditionals in it: every degenerate case
 * (unset sentinel, empty tape, zero count) is decided and pinned in one place.
 *
 * Input shape follows deriveHeader's composite rather than taking the whole
 * AppState: the function declares exactly the slices it reads, and a test
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

import { formatPrice, EMPTY_PRICE, formatQty, formatSignedDollar, formatSignedPercent, changeSign } from "./format";
import type { ChangeSign } from "./format";
import { vwapUnits } from "./bars";
import type { TapeEntry } from "./state/reducer";

/**
 * The reducer slices the panel reads. Prices are integer units of $0.0001,
 * with SESSION_OPEN_UNSET for "no trade has printed this session".
 */
export interface SessionStatsInput {
    readonly tape: readonly TapeEntry[];
    readonly sessionHighPx: number;
    readonly sessionLowPx: number;
    readonly sessionTradeCount: number;
    /** Session notional and volume (P17-8): the session VWAP is their ratio. */
    readonly sessionNotional: bigint;
    readonly sessionVolume: number;
    /**
     * The market open (the Alpaca open, integer units on the cent), the same open the
     * chart's market-open reference line uses. Undefined until it is fetched.
     */
    readonly openPx?: number;
}

/** Direction of Chg from open; "none" while it shows the dash. */
export type ChgFromOpenDir = ChangeSign | "none";

/**
 * Render-ready strings, one per panel row (last trade contributes two). Every
 * field is always a string: there is no undefined and no null to branch on, so
 * the component mounts a stable set of rows from first paint (P13 D10).
 */
export interface SessionStatsModel {
    readonly high: string;
    readonly low: string;
    /** Session VWAP (P17-8), rounded half up; the dash before the first fill. */
    readonly vwap: string;
    readonly trades: string;
    readonly lastPrice: string;
    readonly lastSize: string;
    /** Last trade minus the market open, signed dollars; the dash until both exist. */
    readonly chgFromOpen: string;
    /** The same move as a signed percent of the open; the dash until both exist. */
    readonly chgFromOpenPct: string;
    readonly chgFromOpenDir: ChgFromOpenDir;
}

/**
 * Build the display rows from session state (four from P13, VWAP added at
 * P17-8). Pure and total: no input value
 * can throw, and no input value yields NaN or "undefined" in the output.
 *
 *   { tape: [print at 1502500 x 4], high: 1507500, low: 1492500, trades: 12,
 *     notional: 30050000n, volume: 20 }
 *     -> { high: "150.75", low: "149.25", vwap: "150.25", trades: "12", lastPrice: "150.25", lastSize: "4" }
 *
 *   { tape: [], high: -1, low: -1, trades: 0, notional: 0n, volume: 0 }
 *     -> every price field the dash, trades "0"
 */
export function buildSessionStats(input: SessionStatsInput): SessionStatsModel {
    const { tape, sessionHighPx, sessionLowPx, sessionTradeCount, sessionNotional, sessionVolume, openPx } = input;

    // Newest-first, so the latest print is index 0. undefined only when no trade has
    // printed, which is the sole empty case either last-trade field has to handle.
    const last = tape.length > 0 ? tape[0] : undefined;

    // Chg from open: integer units through the shared formatters, never float maths.
    // The dash for both parts until the open is known and a trade has printed.
    const known = last !== undefined && openPx !== undefined && openPx > 0;
    const delta = known ? last.pricePx - openPx : 0;

    return {
        // No sentinel branch: formatPrice maps every negative value to the dash.
        high: formatPrice(sessionHighPx),
        low: formatPrice(sessionLowPx),
        // vwapUnits returns -1 at zero volume, which formatPrice renders as the dash.
        vwap: formatPrice(vwapUnits(sessionNotional, sessionVolume)),
        // A count, not a price: zero is real and renders "0".
        trades: formatQty(sessionTradeCount),
        lastPrice: last !== undefined ? formatPrice(last.pricePx) : EMPTY_PRICE,
        lastSize: last !== undefined ? formatQty(last.quantity) : EMPTY_PRICE,
        chgFromOpen: known ? formatSignedDollar(delta) : EMPTY_PRICE,
        chgFromOpenPct: known ? formatSignedPercent(delta, openPx) : EMPTY_PRICE,
        chgFromOpenDir: known ? changeSign(delta) : "none",
    };
}
