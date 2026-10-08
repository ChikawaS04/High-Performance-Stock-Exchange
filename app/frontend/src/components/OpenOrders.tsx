/**
 * Order blotter (SRS §3.7). Extended from the P5-4 open-orders panel in P7-8 into
 * a full blotter: clOrdId, send time, side, limit price, filled / total, status,
 * and a cancel action on live rows. Still presentational and single-slice: it
 * renders the reducer's myOrders and emits a cancel intent via onCancel; App turns
 * that into send(cancelOrderFrame(nextClOrdId(), origClOrdId)), the same path the
 * P7-7 CancelTicket uses, so the two cancel routes cannot diverge. It builds no
 * frame and holds no clOrdId generator or socket.
 *
 * What P7-8 adds, and the fidelity limits it respects:
 *
 *  - Send time. A client-assigned wall-clock stamped at dispatch
 *    (MyOrder.sentAtNanos), not a server value, so the column is labelled
 *    client-assigned. Rendered through the shared formatClockNanos; a row without
 *    one shows EMPTY_PRICE.
 *
 *  - Filled / total. filled is DERIVED as originalQty - remainingQty (see
 *    filledOf), never a second stored field, so it stays consistent whether the
 *    row filled as the aggressor (EXEC-authoritative remaining) or was decremented
 *    as the passive side (the P7-8 client-side derivation). total is the order's
 *    original quantity.
 *
 *  - Limit price only. The price column is the order's own limit from SENT
 *    (pricePx); SENT is the sole source of it. Execution prices (the resting
 *    price, carried constraint 6) are a per-trade concern and live in the tape,
 *    not per order, since the L2 wire reports no per-order execution price.
 *
 * The Engine Book tab from the source design is out: per-order engine state is not
 * on the wire at L2. Status and remaining are authoritative from EXEC (plus the
 * P7-8 passive decrement); this component never mutates them. A Cancel action is
 * offered only on isCancellable rows (OPEN / PARTIALLY_FILLED), passing the row's
 * own clOrdId as origClOrdId. Prices render through formatPrice; a -1 would
 * surface as EMPTY_PRICE, never a negative.
 *
 * P8-5 (layout only): the table is wrapped in a bounded scroll body
 * (.open-orders__scroll) so the blotter holds a fixed min-height of roughly 4-5
 * rows instead of collapsing to one, the header row stays sticky, and a long list
 * scrolls internally rather than pushing the Cancel-by-ID ticket below it down. No
 * field, testid, or state behaviour changes; the row markup is untouched.
 */

import { isCancellable } from "../state/reducer";
import type { MyOrder } from "../state/reducer";
import { formatPrice, formatClockNanos } from "../format";

/**
 * Filled quantity for a row: original minus remaining, clamped at zero. Pure and
 * exported (mirrors validateOrderInput / tickDirections) so the one piece of view
 * logic is unit-tested without a DOM. Derived rather than stored, so it can never
 * drift from remainingQty, which is the single source of truth on both the
 * aggressor path (EXEC) and the passive path (the P7-8 decrement).
 */
export function filledOf(order: MyOrder): number {
    return Math.max(0, order.originalQty - order.remainingQty);
}

export interface OpenOrdersProps {
    readonly orders: readonly MyOrder[];
    readonly onCancel: (origClOrdId: number) => void;
}

export function OpenOrders({ orders, onCancel }: OpenOrdersProps) {
    if (orders.length === 0) {
        return (
            <div className="open-orders">
                <div className="open-orders__empty" data-testid="open-orders-empty">
                    No orders yet
                </div>
            </div>
        );
    }

    return (
        <div className="open-orders">
            <div className="open-orders__scroll" data-testid="open-orders-scroll">
                <table className="open-orders__table">
                    <thead>
                    <tr>
                        <th className="open-orders__col-id">ID</th>
                        <th className="open-orders__col-time" title="Client-assigned send time">
                            Sent
                        </th>
                        <th className="open-orders__col-side">Side</th>
                        <th className="open-orders__col-price">Price</th>
                        <th className="open-orders__col-qty">Filled</th>
                        <th className="open-orders__col-status">Status</th>
                        <th className="open-orders__col-action" aria-label="Actions" />
                    </tr>
                    </thead>
                    <tbody>
                    {orders.map((order) => (
                        <tr
                            key={order.clOrdId}
                            className={`open-orders__row open-orders__row--${order.side.toLowerCase()}`}
                            data-testid="open-orders-row"
                        >
                            <td className="open-orders__id">{order.clOrdId}</td>
                            <td className="open-orders__time" data-testid={`sent-${order.clOrdId}`}>
                                {formatClockNanos(order.sentAtNanos ?? 0)}
                            </td>
                            <td className="open-orders__side">{order.side}</td>
                            <td className="open-orders__price">{formatPrice(order.pricePx)}</td>
                            <td className="open-orders__qty" data-testid={`filled-${order.clOrdId}`}>
                                {filledOf(order)} / {order.originalQty}
                            </td>
                            <td className="open-orders__status">{order.status}</td>
                            <td className="open-orders__action">
                                {isCancellable(order.status) ? (
                                    <button
                                        type="button"
                                        className="open-orders__cancel"
                                        data-testid={`cancel-${order.clOrdId}`}
                                        onClick={() => onCancel(order.clOrdId)}
                                    >
                                        Cancel
                                    </button>
                                ) : null}
                            </td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
