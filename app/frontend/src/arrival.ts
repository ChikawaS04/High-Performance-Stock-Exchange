/**
 * Arrival stamping (P18 D8). Pure. At submit, App records the market for the new
 * order: the best bid and ask, the mid, and the session VWAP. The execution quality
 * metrics (P18-9) measure each order's fills against it.
 *
 * The mid exists only when both sides do; otherwise it is -1 and every metric that
 * needs it shows a dash. Values are integer units, -1 for unknown. `t` is the
 * epoch-nanosecond time of the book the stamp was read from (the venue's quote
 * time); the order's own send time is MyOrder.sentAtNanos.
 */

import { venueMid } from "./prices";

export interface Arrival {
    readonly bid: number;
    readonly ask: number;
    /** -1 unless both sides exist. */
    readonly mid: number;
    /** Session VWAP at submit; -1 before the first trade. */
    readonly vwap: number;
    readonly t: number;
}

export function arrivalFrom(bestBid: number, bestAsk: number, vwap: number, t: number): Arrival {
    const mid = venueMid(bestBid, bestAsk);
    return {
        bid: bestBid > 0 ? bestBid : -1,
        ask: bestAsk > 0 ? bestAsk : -1,
        mid: mid ?? -1,
        vwap: vwap >= 0 ? vwap : -1,
        t,
    };
}
