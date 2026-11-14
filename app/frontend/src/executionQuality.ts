/**
 * Execution quality (transaction cost analysis, P18 D9). Pure.
 *
 * One row per own order with at least one fill, from the exact per-order aggregates
 * the reducer folds (MyOrder.exec, P18-1) and the arrival stamp (MyOrder.arrival,
 * P18-8). Never from the capped fill list, so a long session cannot drift a value.
 *
 * | Metric       | Definition                                                         |
 * | Avg price    | volume-weighted average of the order's fills                       |
 * | Filled       | filled quantity / original quantity                                |
 * | vs Arrival   | (avg - arrival mid) / arrival mid x 10,000 bps, sign flipped for    |
 * |              | sells, so positive always means cost                               |
 * | vs VWAP      | the same against the session VWAP after the order's last fill      |
 * | Spread paid  | aggressive fills only: (aggressive avg - arrival mid) / arrival     |
 * |              | half-spread, sign flipped for sells; 1.0 is the full half-spread,  |
 * |              | 0 a midpoint fill, negative an improvement                         |
 * | Passive %    | share of the filled quantity that filled passively                 |
 *
 * All arithmetic is in integer price units and BigInt, since price times quantity
 * passes the safe range in a long session (P17 D4). A ratio is converted to a float
 * only at the end, from a BigInt quotient scaled by RATIO_SCALE, which keeps six
 * decimals. A metric whose input is missing is null and shows as a dash: no arrival
 * mid (a one-sided book at submit), no VWAP, no aggressive fill, or a zero
 * half-spread (guarded, never divided by).
 */

import { EMPTY_PRICE, formatPrice } from "./format";
import type { MyOrder } from "./state/reducer";
import type { Side } from "./protocol/messages";

/** Six decimals of a BigInt ratio survive the conversion to a float. */
const RATIO_SCALE = 1_000_000n;

export interface ExecutionQualityRow {
    readonly clOrdId: number;
    readonly side: Side;
    readonly originalQty: number;
    readonly filledQty: number;
    /** Quantity-weighted average fill price in units, rounded half up (a statistic). */
    readonly avgPx: number;
    /** Filled quantity / original quantity, 0 to 1. */
    readonly filledFrac: number;
    readonly vsArrivalBps: number | null;
    readonly vsVwapBps: number | null;
    readonly spreadPaid: number | null;
    /** 0 to 100. */
    readonly passivePct: number;
    /** Client send time, for the newest-first order. */
    readonly sentAtNanos?: number;
}

/** num / den as a float, exact to six decimals; den must be positive. */
function ratio(num: bigint, den: bigint): number {
    return Number((num * RATIO_SCALE) / den) / Number(RATIO_SCALE);
}

/** Half-up integer average of a non-negative notional over a positive quantity. */
function avgUnits(notional: bigint, qty: number): number {
    const q = BigInt(qty);
    return Number((2n * notional + q) / (2n * q));
}

/**
 * Cost in bps of filling `qty` for `notional` against a benchmark price, positive
 * for cost: a buy above the benchmark, a sell below it. Null without a benchmark.
 */
export function costBps(side: Side, notional: bigint, qty: number, benchmarkPx: number): number | null {
    if (!(benchmarkPx > 0) || !(qty > 0)) return null;
    const bench = BigInt(benchmarkPx) * BigInt(qty);
    const diff = side === "BUY" ? notional - bench : bench - notional;
    return ratio(diff * 10_000n, bench);
}

/**
 * Spread paid on aggressive fills: (avg - mid) / half-spread, sign flipped for sells.
 * Null with no aggressive fill, no arrival mid, or a zero half-spread.
 */
export function spreadPaid(side: Side, aggNotional: bigint, aggQty: number, bid: number, ask: number, mid: number): number | null {
    if (!(aggQty > 0) || !(mid > 0) || !(bid > 0) || !(ask > 0)) return null;
    const spread = ask - bid;
    if (!(spread > 0)) return null;
    const q = BigInt(aggQty);
    const midNotional = BigInt(mid) * q;
    const diff = side === "BUY" ? aggNotional - midNotional : midNotional - aggNotional;
    // (diff / q) / (spread / 2) = 2 diff / (q spread)
    return ratio(2n * diff, q * BigInt(spread));
}

/** The quality row for one order, or null when it has no fill yet. */
export function qualityRow(o: MyOrder): ExecutionQualityRow | null {
    const e = o.exec;
    if (e === undefined || !(e.qty > 0)) return null;
    const a = o.arrival;
    const mid = a !== undefined ? a.mid : -1;
    return {
        clOrdId: o.clOrdId,
        side: o.side,
        originalQty: o.originalQty,
        filledQty: e.qty,
        avgPx: avgUnits(e.notional, e.qty),
        filledFrac: o.originalQty > 0 ? e.qty / o.originalQty : 0,
        vsArrivalBps: costBps(o.side, e.notional, e.qty, mid),
        vsVwapBps: costBps(o.side, e.notional, e.qty, e.lastVwap),
        spreadPaid: a !== undefined ? spreadPaid(o.side, e.aggNotional, e.aggQty, a.bid, a.ask, a.mid) : null,
        passivePct: (e.passiveQty / e.qty) * 100,
        ...(o.sentAtNanos !== undefined ? { sentAtNanos: o.sentAtNanos } : {}),
    };
}

/**
 * Every own order with at least one fill, newest first by submit time (rows with no
 * send time keep myOrders' own newest-first order, after those that have one).
 */
export function buildExecutionQuality(myOrders: readonly MyOrder[]): readonly ExecutionQualityRow[] {
    const rows: { readonly row: ExecutionQualityRow; readonly i: number }[] = [];
    myOrders.forEach((o, i) => {
        const row = qualityRow(o);
        if (row !== null) rows.push({ row, i });
    });
    return rows
        .sort((x, y) => (y.row.sentAtNanos ?? -1) - (x.row.sentAtNanos ?? -1) || x.i - y.i)
        .map((x) => x.row);
}

// ---- Display (P18-10) ---------------------------------------------------------------

/** A missing metric shows the terminal's one empty-value glyph, as a missing price does. */
export const DASH = EMPTY_PRICE;

/** Signed bps with one decimal ("+3.2", "-0.8", "0.0"), or the dash. */
export function formatBps(bps: number | null): string {
    if (bps === null || !Number.isFinite(bps)) return DASH;
    const r = Math.round(bps * 10) / 10;
    if (r === 0) return "0.0";
    return `${r > 0 ? "+" : ""}${r.toFixed(1)}`;
}

/** Spread paid as a ratio with two decimals ("1.00", "-0.50"), or the dash. */
export function formatSpreadPaid(x: number | null): string {
    if (x === null || !Number.isFinite(x)) return DASH;
    const r = Math.round(x * 100) / 100;
    return r === 0 ? "0.00" : r.toFixed(2);
}

/** A percentage with no decimals ("40%"). */
export function formatPct(pct: number): string {
    return `${Math.round(pct)}%`;
}

/** Filled as "300/500". */
export function formatFilled(row: Pick<ExecutionQualityRow, "filledQty" | "originalQty">): string {
    return `${row.filledQty}/${row.originalQty}`;
}

export function formatAvg(row: Pick<ExecutionQualityRow, "avgPx">): string {
    return formatPrice(row.avgPx);
}

/** Cost or improvement, for the cell colour: positive is cost (red), negative improvement (green). */
export function costClass(x: number | null): "cost" | "improve" | "flat" | "none" {
    if (x === null || !Number.isFinite(x)) return "none";
    const r = Math.round(x * 100) / 100;
    return r > 0 ? "cost" : r < 0 ? "improve" : "flat";
}

// ---- Summary row (P18 D11) ----------------------------------------------------------

export interface ExecutionQualitySummary {
    /** Shares filled across every row. */
    readonly filledQty: number;
    /** Total filled / total original, 0 to 1; 0 with no rows. */
    readonly filledFrac: number;
    /** Quantity-weighted over the rows that have a value; null when none does. */
    readonly vsArrivalBps: number | null;
    readonly vsVwapBps: number | null;
    readonly spreadPaid: number | null;
    /** Passive share of all filled quantity, 0 to 100. */
    readonly passivePct: number;
}

/** sum(value * filledQty) / sum(filledQty) over rows with a value, else null. */
function weighted(rows: readonly ExecutionQualityRow[], pick: (r: ExecutionQualityRow) => number | null): number | null {
    let num = 0;
    let den = 0;
    for (const r of rows) {
        const v = pick(r);
        if (v === null || !Number.isFinite(v)) continue;
        num += v * r.filledQty;
        den += r.filledQty;
    }
    return den > 0 ? num / den : null;
}

/**
 * The pinned ALL row (P18 D11): total filled quantity, and the quantity-weighted
 * average of each metric across the orders that have a value. An order without a
 * value for a metric (no arrival mid, no aggressive fill) is left out of that
 * metric's average only. Weights are filled quantity for every metric.
 */
export function summarizeExecutionQuality(rows: readonly ExecutionQualityRow[]): ExecutionQualitySummary {
    let filledQty = 0;
    let originalQty = 0;
    let passiveQty = 0;
    for (const r of rows) {
        filledQty += r.filledQty;
        originalQty += r.originalQty;
        passiveQty += (r.passivePct / 100) * r.filledQty;
    }
    return {
        filledQty,
        filledFrac: originalQty > 0 ? filledQty / originalQty : 0,
        vsArrivalBps: weighted(rows, (r) => r.vsArrivalBps),
        vsVwapBps: weighted(rows, (r) => r.vsVwapBps),
        spreadPaid: weighted(rows, (r) => r.spreadPaid),
        passivePct: filledQty > 0 ? (passiveQty / filledQty) * 100 : 0,
    };
}
