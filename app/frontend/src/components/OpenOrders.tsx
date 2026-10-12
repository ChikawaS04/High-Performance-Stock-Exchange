/**
 * Order blotter (SRS §3.7). Extended from the P5-4 open-orders panel in P7-8 into
 * a full blotter: clOrdId, send time, side, limit price, filled / total, status,
 * and a cancel action on live rows. Still presentational and single-slice: it
 * renders the reducer's myOrders and emits a cancel intent via onCancel; App turns
 * that into send(cancelOrderFrame(nextClOrdId(), origClOrdId)), the same path the
 * per-row cancel and the Cancel-by-ID popup both use, so the routes cannot diverge.
 * It builds no frame and holds no clOrdId generator or socket.
 *
 * TWS charcoal rework (2026-10): the blotter is now a self-contained card (like the
 * order-entry ticket), so the panel owns its header rather than taking a loose
 * heading from the page. The header band carries the ORDERS title and a tiny
 * blend-in dropdown caret; the caret opens a white "Cancel by ID" menu, which opens
 * the Cancel-by-Order-ID popup (the global popup blueprint, DESIGN_SYSTEM.md §10).
 * The popup replaces the old always-visible CancelTicket section: it validates a
 * typed id with the shared validateOrigClOrdId and emits the SAME onCancel, so the
 * cancel-by-id route is unchanged behaviourally. The table body, every column, and
 * all test ids are untouched; only the chrome around them is new.
 *
 * What P7-8 adds, and the fidelity limits it respects:
 *
 *  - Send time. A client-assigned wall-clock stamped at dispatch
 *    (MyOrder.sentAtNanos), not a server value. Rendered through the shared
 *    formatClockNanos; a row without one shows EMPTY_PRICE.
 *
 *  - Filled / total. filled is DERIVED as originalQty - remainingQty (see
 *    filledOf), never a second stored field. total is the order's original quantity.
 *
 *  - Limit price only. The price column is the order's own limit from SENT
 *    (pricePx). Execution prices live in the tape, not per order.
 *
 * Status and remaining are authoritative from EXEC (plus the P7-8 passive
 * decrement); this component never mutates them. A Cancel action is offered only on
 * isCancellable rows (OPEN / PARTIALLY_FILLED), passing the row's own clOrdId as
 * origClOrdId. Prices render through formatPrice; a -1 would surface as EMPTY_PRICE.
 *
 * P14-6: a TIF column renders each order's time in force. P14-8: a Type column
 * renders ICE / LMT via orderTypeLabel. P14-10: the Type and Price columns show MID
 * for a midpoint peg so the peg's -1 NA price never surfaces.
 */

import { useEffect, useRef, useState } from "react";

import { isCancellable } from "../state/reducer";
import type { MyOrder } from "../state/reducer";
import { formatPrice, formatClockNanos } from "../format";
import { validateOrigClOrdId } from "./CancelTicket";

/**
 * Filled quantity for a row: original minus remaining, clamped at zero. Pure and
 * exported (mirrors validateOrderInput / tickDirections) so the one piece of view
 * logic is unit-tested without a DOM. Derived rather than stored, so it can never
 * drift from remainingQty.
 */
export function filledOf(order: MyOrder): number {
    return Math.max(0, order.originalQty - order.remainingQty);
}

/**
 * The blotter Type code for a row. Pure and exported (mirrors filledOf). MID for a
 * midpoint peg (P14-10, checked first since a peg is never an iceberg), ICE when an
 * iceberg display quantity is set (P14-6), LMT otherwise.
 */
export function orderTypeLabel(order: MyOrder): string {
    if (order.ordType === "PEG_MID") return "MID";
    return order.displayQty > 0 ? "ICE" : "LMT";
}

export interface OpenOrdersProps {
    readonly orders: readonly MyOrder[];
    readonly onCancel: (origClOrdId: number) => void;
    readonly disabled?: boolean;
}

export function OpenOrders({ orders, onCancel, disabled = false }: OpenOrdersProps) {
    const [menuOpen, setMenuOpen] = useState(false);
    const [modalOpen, setModalOpen] = useState(false);
    const [idInput, setIdInput] = useState("");
    const [error, setError] = useState<string | null>(null);
    const menuWrapRef = useRef<HTMLDivElement>(null);

    const openModal = (): void => {
        setMenuOpen(false);
        setError(null);
        setIdInput("");
        setModalOpen(true);
    };

    const closeModal = (): void => {
        setModalOpen(false);
        setError(null);
        setIdInput("");
    };

    const confirmCancel = (): void => {
        if (disabled) return;
        const result = validateOrigClOrdId(idInput);
        if (!result.ok) {
            setError(result.reason);
            return;
        }
        setError(null);
        onCancel(result.origClOrdId);
        setIdInput("");
        setModalOpen(false);
    };

    // Close the menu on an outside click, and close menu + popup on Escape.
    useEffect(() => {
        if (!menuOpen && !modalOpen) return;
        const onDown = (e: MouseEvent): void => {
            if (
                menuOpen &&
                menuWrapRef.current !== null &&
                !menuWrapRef.current.contains(e.target as Node)
            ) {
                setMenuOpen(false);
            }
        };
        const onKey = (e: KeyboardEvent): void => {
            if (e.key === "Escape") {
                setMenuOpen(false);
                setModalOpen(false);
            }
        };
        document.addEventListener("mousedown", onDown);
        document.addEventListener("keydown", onKey);
        return () => {
            document.removeEventListener("mousedown", onDown);
            document.removeEventListener("keydown", onKey);
        };
    }, [menuOpen, modalOpen]);

    return (
        <div className="open-orders">
            <div className="open-orders__head">
                <span className="open-orders__panel-title">Orders</span>
                <div className="open-orders__menu-wrap" ref={menuWrapRef}>
                    <button
                        type="button"
                        className="open-orders__caret"
                        data-testid="blotter-menu-btn"
                        aria-haspopup="menu"
                        aria-expanded={menuOpen}
                        aria-label="Cancel options"
                        onClick={() => setMenuOpen((o) => !o)}
                    >
                        &#9662;
                    </button>
                    {menuOpen ? (
                        <div className="open-orders__menu" role="menu">
                            <button
                                type="button"
                                className="open-orders__menu-item"
                                role="menuitem"
                                data-testid="cancel-by-id-open"
                                onClick={openModal}
                            >
                                Cancel by ID
                            </button>
                        </div>
                    ) : null}
                </div>
            </div>

            <div className="open-orders__body">
                {orders.length === 0 ? (
                    <div className="open-orders__empty" data-testid="open-orders-empty">
                        No orders yet
                    </div>
                ) : (
                    <div className="open-orders__scroll" data-testid="open-orders-scroll">
                        <table className="open-orders__table">
                            <thead>
                            <tr>
                                <th className="open-orders__col-id">ID</th>
                                <th className="open-orders__col-time" title="Client-assigned send time">
                                    Sent
                                </th>
                                <th className="open-orders__col-side">Side</th>
                                <th className="open-orders__col-type">Type</th>
                                <th className="open-orders__col-tif">TIF</th>
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
                                    <td className="open-orders__type" data-testid={`type-${order.clOrdId}`}>
                                        {orderTypeLabel(order)}
                                    </td>
                                    <td className="open-orders__tif" data-testid={`tif-${order.clOrdId}`}>
                                        {order.tif}
                                    </td>
                                    <td className="open-orders__price">
                                        {order.ordType === "PEG_MID" ? "MID" : formatPrice(order.pricePx)}
                                    </td>
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
                )}
            </div>

            {modalOpen ? (
                <div
                    className="cxid-overlay"
                    data-testid="cancel-by-id-overlay"
                    onMouseDown={(e) => {
                        if (e.target === e.currentTarget) closeModal();
                    }}
                >
                    <div
                        className="cxid-modal"
                        role="dialog"
                        aria-modal="true"
                        aria-labelledby="cxid-title"
                    >
                        <div className="cxid-head">
                            <span className="cxid-title" id="cxid-title">
                                Cancel by Order ID
                            </span>
                            <button
                                type="button"
                                className="cxid-exit"
                                aria-label="Close"
                                data-testid="cancel-by-id-close"
                                onClick={closeModal}
                            >
                                &times;
                            </button>
                        </div>
                        <div className="cxid-body">
                            <label className="cxid-label" htmlFor="cxid-input">
                                Order ID
                            </label>
                            <input
                                id="cxid-input"
                                className="cxid-input"
                                type="text"
                                inputMode="numeric"
                                placeholder="Enter order ID"
                                autoComplete="off"
                                value={idInput}
                                data-testid="cancel-by-id-input"
                                aria-label="Order ID to cancel"
                                disabled={disabled}
                                onChange={(e) => setIdInput(e.target.value)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter") confirmCancel();
                                }}
                            />
                            {error !== null ? (
                                <div
                                    className="cxid-error"
                                    role="alert"
                                    data-testid="cancel-by-id-error"
                                >
                                    {error}
                                </div>
                            ) : null}
                            <div className="cxid-confirm-row">
                                <button
                                    type="button"
                                    className="cxid-confirm"
                                    data-testid="cancel-by-id-submit"
                                    disabled={disabled}
                                    onClick={confirmCancel}
                                >
                                    Confirm Cancel
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            ) : null}
        </div>
    );
}
