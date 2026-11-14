/**
 * The Executions tab of the blotter panel (P18 D10): execution quality per own order
 * with at least one fill, newest submit first. A §9 table: it reuses the blotter's
 * own table classes, so the header row, well, zebra, hover and Side cell are the one
 * table family. The metrics are pure (executionQuality.ts); this component only
 * renders them. Cost is red and improvement green, in the direction colours (§2),
 * never the bid blue or ask red.
 *
 * P18-11: a pinned last row, ALL, sums the filled quantity and gives the
 * quantity-weighted average of each metric over the orders that have one
 * (summarizeExecutionQuality). It sits in the table foot and stays in view while
 * the body scrolls (sticky to the bottom of the scroll area).
 */

import {
    costClass,
    formatAvg,
    formatBps,
    formatFilled,
    formatPct,
    formatSpreadPaid,
    summarizeExecutionQuality,
    DASH,
} from "../executionQuality";
import { formatQty } from "../format";
import type { ExecutionQualityRow } from "../executionQuality";

export interface ExecutionsTableProps {
    readonly rows: readonly ExecutionQualityRow[];
}

function CostCell({ value, text, testId }: { readonly value: number | null; readonly text: string; readonly testId: string }) {
    return (
        <td className={`open-orders__qty exec-q__metric exec-q__metric--${costClass(value)}`} data-testid={testId}>
            {text}
        </td>
    );
}

export function ExecutionsTable({ rows }: ExecutionsTableProps) {
    if (rows.length === 0) {
        return (
            <div className="open-orders__empty" data-testid="executions-empty">
                No executions yet
            </div>
        );
    }
    const summary = summarizeExecutionQuality(rows);
    return (
        <div className="open-orders__scroll" data-testid="executions-scroll">
            <table className="open-orders__table exec-q__table" data-testid="executions-table">
                <thead>
                    <tr>
                        <th className="open-orders__col-id">Order ID</th>
                        <th className="open-orders__col-side">Side</th>
                        <th className="open-orders__col-qty" title="Filled quantity / original quantity">
                            Qty
                        </th>
                        <th className="open-orders__col-qty">Filled</th>
                        <th className="open-orders__col-price">Avg price</th>
                        <th className="open-orders__col-qty" title="Average price against the arrival mid, bps; positive is cost">
                            vs Arrival
                        </th>
                        <th className="open-orders__col-qty" title="Average price against the session VWAP at the last fill, bps; positive is cost">
                            vs VWAP
                        </th>
                        <th className="open-orders__col-qty" title="Aggressive fills against the arrival half-spread; 1.00 is the full half-spread">
                            Spread paid
                        </th>
                        <th className="open-orders__col-qty">Passive %</th>
                    </tr>
                </thead>
                <tbody>
                    {rows.map((r) => (
                        <tr
                            key={r.clOrdId}
                            className={`open-orders__row open-orders__row--${r.side.toLowerCase()}`}
                            data-testid="executions-row"
                        >
                            <td className="open-orders__id">{r.clOrdId}</td>
                            <td className="open-orders__side">{r.side}</td>
                            <td className="open-orders__qty" data-testid={`exq-qty-${r.clOrdId}`}>
                                {formatFilled(r)}
                            </td>
                            <td className="open-orders__qty" data-testid={`exq-filled-${r.clOrdId}`}>
                                {formatPct(r.filledFrac * 100)}
                            </td>
                            <td className="open-orders__price" data-testid={`exq-avg-${r.clOrdId}`}>
                                {formatAvg(r)}
                            </td>
                            <CostCell value={r.vsArrivalBps} text={formatBps(r.vsArrivalBps)} testId={`exq-arrival-${r.clOrdId}`} />
                            <CostCell value={r.vsVwapBps} text={formatBps(r.vsVwapBps)} testId={`exq-vwap-${r.clOrdId}`} />
                            <CostCell value={r.spreadPaid} text={formatSpreadPaid(r.spreadPaid)} testId={`exq-spread-${r.clOrdId}`} />
                            <td className="open-orders__qty" data-testid={`exq-passive-${r.clOrdId}`}>
                                {formatPct(r.passivePct)}
                            </td>
                        </tr>
                    ))}
                </tbody>
                <tfoot>
                    <tr className="exec-q__summary" data-testid="executions-summary">
                        <td className="open-orders__id exec-q__summary-label">ALL</td>
                        <td className="open-orders__side exec-q__summary-dash">{DASH}</td>
                        <td className="open-orders__qty" data-testid="exq-sum-qty">
                            {formatQty(summary.filledQty)}
                        </td>
                        <td className="open-orders__qty" data-testid="exq-sum-filled">
                            {formatPct(summary.filledFrac * 100)}
                        </td>
                        <td className="open-orders__price exec-q__summary-dash">{DASH}</td>
                        <CostCell value={summary.vsArrivalBps} text={formatBps(summary.vsArrivalBps)} testId="exq-sum-arrival" />
                        <CostCell value={summary.vsVwapBps} text={formatBps(summary.vsVwapBps)} testId="exq-sum-vwap" />
                        <CostCell value={summary.spreadPaid} text={formatSpreadPaid(summary.spreadPaid)} testId="exq-sum-spread" />
                        <td className="open-orders__qty" data-testid="exq-sum-passive">
                            {formatPct(summary.passivePct)}
                        </td>
                    </tr>
                </tfoot>
            </table>
        </div>
    );
}
