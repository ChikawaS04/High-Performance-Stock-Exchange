/**
 * Modify order popup (P18 D4), opened by dropping a dragged order line on a new
 * price. The §10 popup chrome, the same as the blotter's Cancel order confirmation.
 *
 * It shows the order id, side, remaining quantity, old and new price, and the line
 * that says how the change is sent: the engine accepts 35=D and 35=F only, so a
 * modify is a cancel followed by a new order, which loses queue priority. A new
 * price that crosses the opposite best adds a warning that it will trade on arrival
 * (greenlight amendment 8). Confirm Modify is the only way to send, and it is
 * disabled with "Order is no longer active" once the order fills or is cancelled
 * while the popup is open. Escape, the
 * exit and an outside click close it; Enter never confirms (§10).
 *
 * Rendered into document.body, so a chart inside the floating window (§14) cannot
 * clip or stack it. Escape is caught in the capture phase and stopped, so closing
 * this popup does not also close the floating window behind it.
 */

import { useEffect } from "react";
import { createPortal } from "react-dom";

import { formatPrice, formatQty } from "../format";
import type { Side } from "../protocol/messages";

export interface ModifyRequest {
    readonly clOrdId: number;
    readonly side: Side;
    readonly remainingQty: number;
    readonly oldPx: number;
    readonly newPx: number;
    /** True when the new price crosses the opposite best. */
    readonly marketable: boolean;
}

export interface ModifyOrderPopupProps {
    readonly request: ModifyRequest;
    /** False once the order is no longer working (filled or cancelled meanwhile): Confirm Modify is disabled. */
    readonly active: boolean;
    readonly onConfirm: (request: ModifyRequest) => void;
    readonly onClose: () => void;
}

export const QUEUE_LINE = "Sent as cancel and new order; the new order joins the back of the queue.";
export const MARKETABLE_LINE = "Marketable: will execute against the book.";

export function ModifyOrderPopup({ request, active, onConfirm, onClose }: ModifyOrderPopupProps) {
    useEffect(() => {
        const onKey = (e: KeyboardEvent): void => {
            if (e.key === "Escape") {
                e.stopPropagation();
                onClose();
            }
        };
        document.addEventListener("keydown", onKey, true);
        return () => document.removeEventListener("keydown", onKey, true);
    }, [onClose]);

    const side = request.side === "BUY" ? "buy" : "sell";

    return createPortal(
        <div
            className="cxid-overlay"
            data-testid="modify-overlay"
            onMouseDown={(e) => {
                if (e.target === e.currentTarget) onClose();
            }}
        >
            <div
                className="cxid-modal"
                role="dialog"
                aria-modal="true"
                aria-labelledby="modify-title"
                data-testid="modify-order"
                onKeyDown={(e) => {
                    // Enter never confirms (§10): stop a focused button's default activation.
                    if (e.key === "Enter") e.preventDefault();
                }}
            >
                <div className="cxid-head">
                    <span className="cxid-title" id="modify-title">
                        Modify order
                    </span>
                    <button type="button" className="cxid-exit" aria-label="Close" data-testid="modify-close" onClick={onClose}>
                        &times;
                    </button>
                </div>
                <div className="cxid-body">
                    <dl className="order-confirm__rows">
                        <div className="order-confirm__row">
                            <dt>Order ID</dt>
                            <dd className="order-confirm__num" data-testid="modify-id">
                                {request.clOrdId}
                            </dd>
                        </div>
                        <div className="order-confirm__row">
                            <dt>Side</dt>
                            <dd className={`modify-order__side modify-order__side--${side}`} data-testid="modify-side">
                                {request.side}
                            </dd>
                        </div>
                        <div className="order-confirm__row">
                            <dt>Remaining</dt>
                            <dd className="order-confirm__num" data-testid="modify-qty">
                                {formatQty(request.remainingQty)}
                            </dd>
                        </div>
                        <div className="order-confirm__row">
                            <dt>Old price</dt>
                            <dd className="order-confirm__num" data-testid="modify-old">
                                {formatPrice(request.oldPx)}
                            </dd>
                        </div>
                        <div className="order-confirm__row">
                            <dt>New price</dt>
                            <dd className="order-confirm__num" data-testid="modify-new">
                                {formatPrice(request.newPx)}
                            </dd>
                        </div>
                    </dl>
                    <p className="modify-order__note" data-testid="modify-queue">
                        {QUEUE_LINE}
                    </p>
                    {request.marketable ? (
                        <p className="modify-order__note modify-order__note--warn" role="alert" data-testid="modify-marketable">
                            {MARKETABLE_LINE}
                        </p>
                    ) : null}
                    {!active ? (
                        <div className="cxid-error" role="alert" data-testid="modify-inactive">
                            Order is no longer active
                        </div>
                    ) : null}
                    <div className="cxid-confirm-row">
                        <button
                            type="button"
                            className="cxid-confirm"
                            data-testid="modify-confirm"
                            disabled={!active}
                            onClick={() => {
                                if (active) onConfirm(request);
                            }}
                        >
                            Confirm Modify
                        </button>
                    </div>
                </div>
            </div>
        </div>,
        document.body,
    );
}
