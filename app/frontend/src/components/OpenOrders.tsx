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
 *
 * UI polish pass:
 *  - D5: the row Cancel button opens a "Cancel order" confirmation (the same §10
 *    popup chrome as Cancel by Order ID). The cancel is sent only on Confirm
 *    Cancel; Escape, the exit and an outside click close it, and Enter does not
 *    confirm. The popup tracks the order by id and reads its live status, so an
 *    order that fills, expires or is cancelled elsewhere while it is open shows
 *    "Order is no longer active" with Confirm Cancel disabled.
 *    Cancel by Order ID follows the same rule (UI polish follow-up): Enter in its
 *    Order ID field does nothing; only a click on Confirm Cancel sends.
 *  - D6: a status filter single-select (the shared FieldMenu) in the header band,
 *    left of the caret. The predicate is the pure blotterFilter module.
 *
 * Simulation bots: an owner filter (MINE, the default / ALL PARTICIPANTS / one per
 * bot) left of the status filter, persisted like it, and an Owner column. Other
 * participants' rows come from the reducer's marketOrders slice, built from the
 * EXEC stream alone; blotterRows merges both sources into one row shape. Cancel is
 * offered only on this terminal's own rows: the venue has no ownership check, so a
 * cancel on a bot's row would really cancel the bot's order. For another
 * participant's row the Sent column shows the venue time of its first report, and
 * TIF and Price show a dash where the wire never stated them.
 */

import { useEffect, useRef, useState } from "react";

import { isCancellable } from "../state/reducer";
import type { MarketOrder, MyOrder } from "../state/reducer";
import { formatPrice, formatClockNanos, EMPTY_PRICE } from "../format";
import { validateOrigClOrdId } from "./CancelTicket";
import { FieldMenu } from "./FieldMenu";
import {
    DEFAULT_OWNER_FILTER,
    DEFAULT_STATUS_FILTER,
    OWNER_FILTER_OPTIONS,
    STATUS_FILTER_OPTIONS,
    blotterRows,
    emptyBlotterMessage,
    filterOrders,
    isOwnerFilter,
    isStatusFilter,
    type OwnerFilter,
    type StatusFilter,
} from "../blotterFilter";
import { OWNER_LABELS } from "../participants";
import { SETTING_KEYS } from "../settings/persist";
import { usePersistentSetting } from "../settings/usePersistentSetting";

const FILTER_MENU = STATUS_FILTER_OPTIONS.map((o) => ({ value: o.value, label: o.label }));
const OWNER_MENU = OWNER_FILTER_OPTIONS.map((o) => ({ value: o.value, label: o.label }));
const NO_MARKET_ORDERS: readonly MarketOrder[] = [];

/**
 * Filled quantity for a row: original minus remaining, clamped at zero. Pure and
 * exported (mirrors validateOrderInput / tickDirections) so the one piece of view
 * logic is unit-tested without a DOM. Derived rather than stored, so it can never
 * drift from remainingQty.
 */
export function filledOf(order: Pick<MyOrder, "originalQty" | "remainingQty">): number {
    return Math.max(0, order.originalQty - order.remainingQty);
}

/**
 * The blotter Type code for a row. Pure and exported (mirrors filledOf). MID for a
 * midpoint peg (P14-10, checked first since a peg is never an iceberg), ICE when an
 * iceberg display quantity is set (P14-6), LMT otherwise.
 */
export function orderTypeLabel(order: Pick<MyOrder, "ordType" | "displayQty">): string {
    if (order.ordType === "PEG_MID") return "MID";
    return order.displayQty > 0 ? "ICE" : "LMT";
}

/** The order type in words, for the cancel confirmation (all caps, §3). */
function orderTypeWords(order: MyOrder): string {
    if (order.ordType === "PEG_MID") return "MIDPOINT PEG";
    return order.displayQty > 0 ? "LIMIT (ICEBERG)" : "LIMIT";
}

export interface OpenOrdersProps {
    readonly orders: readonly MyOrder[];
    /** Other participants' orders (the bots'), shown under the owner filter. */
    readonly marketOrders?: readonly MarketOrder[];
    readonly onCancel: (origClOrdId: number) => void;
    readonly disabled?: boolean;
}

export function OpenOrders({ orders, marketOrders = NO_MARKET_ORDERS, onCancel, disabled = false }: OpenOrdersProps) {
    const [menuOpen, setMenuOpen] = useState(false);
    const [modalOpen, setModalOpen] = useState(false);
    const [idInput, setIdInput] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [confirmId, setConfirmId] = useState<number | null>(null);
    // The status filter persists across page switches and restarts (UI polish D10).
    const [statusFilter, setStatusFilter] = usePersistentSetting<StatusFilter>(
        SETTING_KEYS.blotterStatusFilter,
        DEFAULT_STATUS_FILTER,
        isStatusFilter,
    );
    const [ownerFilter, setOwnerFilter] = usePersistentSetting<OwnerFilter>(
        SETTING_KEYS.blotterOwnerFilter,
        DEFAULT_OWNER_FILTER,
        isOwnerFilter,
    );
    const menuWrapRef = useRef<HTMLDivElement>(null);

    const rows = blotterRows(orders, marketOrders, ownerFilter);
    const shown = filterOrders(rows, statusFilter);
    const ownerLabel =
        OWNER_FILTER_OPTIONS.find((o) => o.value === ownerFilter)?.label ?? ownerFilter;
    const filterLabel =
        STATUS_FILTER_OPTIONS.find((o) => o.value === statusFilter)?.label ?? statusFilter;

    // The order the cancel confirmation is about, looked up live so a status change
    // while the popup is open is reflected at once.
    const confirmOrder = confirmId === null ? undefined : orders.find((o) => o.clOrdId === confirmId);
    const confirmActive = confirmOrder !== undefined && isCancellable(confirmOrder.status);
    const confirmOpen = confirmId !== null;

    const closeConfirm = (): void => setConfirmId(null);

    const sendConfirmedCancel = (): void => {
        if (disabled || confirmId === null || !confirmActive) return;
        onCancel(confirmId);
        setConfirmId(null);
    };

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
        if (!menuOpen && !modalOpen && !confirmOpen) return;
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
                setConfirmId(null);
            }
        };
        document.addEventListener("mousedown", onDown);
        document.addEventListener("keydown", onKey);
        return () => {
            document.removeEventListener("mousedown", onDown);
            document.removeEventListener("keydown", onKey);
        };
    }, [menuOpen, modalOpen, confirmOpen]);

    return (
        <div className="open-orders">
            <div className="open-orders__head">
                <span className="open-orders__panel-title">Orders</span>
                <FieldMenu
                    testId="blotter-owner-filter"
                    ariaLabel="Owner filter"
                    compact
                    className="open-orders__owner-filter"
                    options={OWNER_MENU}
                    value={ownerFilter}
                    triggerLabel={ownerLabel}
                    onSelect={(v) => setOwnerFilter(v as OwnerFilter)}
                />
                <FieldMenu
                    testId="blotter-filter"
                    ariaLabel="Status filter"
                    compact
                    className="open-orders__filter"
                    options={FILTER_MENU}
                    value={statusFilter}
                    triggerLabel={filterLabel}
                    onSelect={(v) => setStatusFilter(v as StatusFilter)}
                />
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
                        <div className="field-menu__menu field-menu__menu--anchored" role="menu">
                            <button
                                type="button"
                                className="field-menu__opt field-menu__opt--action field-menu__opt--caps"
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
                {rows.length === 0 ? (
                    <div className="open-orders__empty" data-testid="open-orders-empty">
                        {emptyBlotterMessage(ownerFilter, "ALL")}
                    </div>
                ) : (
                    <div className="open-orders__scroll" data-testid="open-orders-scroll">
                        <table className="open-orders__table">
                            <thead>
                            <tr>
                                <th className="open-orders__col-id">ID</th>
                                <th className="open-orders__col-owner">Owner</th>
                                <th
                                    className="open-orders__col-time"
                                    title="Client-assigned send time; venue time of the first report for another participant's order"
                                >
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
                            {shown.length === 0 ? (
                                <tr className="open-orders__filter-empty-row">
                                    <td
                                        colSpan={10}
                                        className="open-orders__filter-empty"
                                        data-testid="open-orders-filter-empty"
                                    >
                                        {emptyBlotterMessage(ownerFilter, statusFilter)}
                                    </td>
                                </tr>
                            ) : null}
                            {shown.map((order) => (
                                <tr
                                    key={order.clOrdId}
                                    className={`open-orders__row open-orders__row--${order.side.toLowerCase()}`}
                                    data-testid="open-orders-row"
                                >
                                    <td className="open-orders__id">{order.clOrdId}</td>
                                    <td className="open-orders__owner" data-testid={`owner-${order.clOrdId}`}>
                                        {OWNER_LABELS[order.owner]}
                                    </td>
                                    <td className="open-orders__time" data-testid={`sent-${order.clOrdId}`}>
                                        {formatClockNanos(order.timeNanos ?? 0)}
                                    </td>
                                    <td className="open-orders__side">{order.side}</td>
                                    <td className="open-orders__type" data-testid={`type-${order.clOrdId}`}>
                                        {orderTypeLabel(order)}
                                    </td>
                                    <td className="open-orders__tif" data-testid={`tif-${order.clOrdId}`}>
                                        {order.tif ?? EMPTY_PRICE}
                                    </td>
                                    <td className="open-orders__price">
                                        {order.ordType === "PEG_MID" ? "MID" : formatPrice(order.pricePx)}
                                    </td>
                                    <td className="open-orders__qty" data-testid={`filled-${order.clOrdId}`}>
                                        {filledOf(order)} / {order.originalQty}
                                    </td>
                                    <td className="open-orders__status">{order.status}</td>
                                    <td className="open-orders__action">
                                        {order.owner === "YOU" && isCancellable(order.status) ? (
                                            <button
                                                type="button"
                                                className="open-orders__cancel"
                                                data-testid={`cancel-${order.clOrdId}`}
                                                onClick={() => setConfirmId(order.clOrdId)}
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

            {confirmOpen ? (
                <div
                    className="cxid-overlay"
                    data-testid="cancel-confirm-overlay"
                    onMouseDown={(e) => {
                        if (e.target === e.currentTarget) closeConfirm();
                    }}
                >
                    <div
                        className="cxid-modal"
                        role="dialog"
                        aria-modal="true"
                        aria-labelledby="cancel-confirm-title"
                        data-testid="cancel-confirm"
                    >
                        <div className="cxid-head">
                            <span className="cxid-title" id="cancel-confirm-title">
                                Cancel order
                            </span>
                            <button
                                type="button"
                                className="cxid-exit"
                                aria-label="Close"
                                data-testid="cancel-confirm-close"
                                onClick={closeConfirm}
                            >
                                &times;
                            </button>
                        </div>
                        <div className="cxid-body">
                            <dl className="order-confirm__rows">
                                <div className="order-confirm__row">
                                    <dt>Order ID</dt>
                                    <dd className="order-confirm__num" data-testid="cancel-confirm-id">
                                        {confirmId}
                                    </dd>
                                </div>
                                {confirmOrder !== undefined ? (
                                    <>
                                        <div className="order-confirm__row">
                                            <dt>Side</dt>
                                            <dd>{confirmOrder.side}</dd>
                                        </div>
                                        <div className="order-confirm__row">
                                            <dt>Remaining</dt>
                                            <dd className="order-confirm__num">{confirmOrder.remainingQty}</dd>
                                        </div>
                                        <div className="order-confirm__row">
                                            <dt>Price</dt>
                                            <dd className="order-confirm__num">
                                                {confirmOrder.ordType === "PEG_MID"
                                                    ? "MID"
                                                    : formatPrice(confirmOrder.pricePx)}
                                            </dd>
                                        </div>
                                        <div className="order-confirm__row">
                                            <dt>Order type</dt>
                                            <dd>{orderTypeWords(confirmOrder)}</dd>
                                        </div>
                                    </>
                                ) : null}
                            </dl>
                            {!confirmActive ? (
                                <div
                                    className="cxid-error"
                                    role="alert"
                                    data-testid="cancel-confirm-inactive"
                                >
                                    Order is no longer active
                                </div>
                            ) : null}
                            <div className="cxid-confirm-row">
                                <button
                                    type="button"
                                    className="cxid-confirm"
                                    data-testid="cancel-confirm-submit"
                                    disabled={disabled || !confirmActive}
                                    onClick={sendConfirmedCancel}
                                >
                                    Confirm Cancel
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            ) : null}

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
