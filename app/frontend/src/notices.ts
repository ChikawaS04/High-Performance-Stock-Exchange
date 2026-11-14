/**
 * Order notifications (UI polish D4). Pure: which EXEC raises which notice, and the
 * toast queue (add, cap, dismiss, expire). The reducer calls noticesForExec with
 * the order rows before and after it applied a frame; OrderToasts renders the
 * queue and owns only the timers.
 *
 * Own orders only. EXEC frames are broadcast (SRS §3.6), so a notice is raised
 * only for a row in myOrders, the same ownership test the reducer and blotter use.
 * The aggressor is named by orderId; a passive resting order of ours is named by
 * passiveOrderId and gets its own notice from its locally decremented row.
 *
 * A notice is raised only when the frame actually changed the row. The reducer
 * returns the same row object for a terminal order (nextOrder / passiveFill never
 * resurrect one), so "row changed" is also the terminal guard. "Submitted" fires
 * only on PENDING to OPEN: a reject arrives instead of an accept, so a rejected
 * order never shows "submitted" first, and the trailing ACCEPTED the engine sends
 * after a partial fill (reporting the rested remainder) raises nothing.
 */

import { formatPrice } from "./format";
import type { ExecFrame, Side } from "./protocol/messages";
import type { MyOrder } from "./state/reducer";

/**
 * modifyNotSent and modifyFailed (P18-5) are raised by the reprice sequence
 * (reprice.ts), not by noticesForExec: a drag to reprice that ended without sending
 * its replacement order.
 */
export type NoticeKind =
    | "submitted"
    | "partial"
    | "filled"
    | "cancelled"
    | "rejected"
    | "expired"
    | "modifyNotSent"
    | "modifyFailed";

export interface OrderNotice {
    /** Client-assigned, increasing per notice (the reducer's noticeSeq). */
    readonly id: number;
    readonly kind: NoticeKind;
    readonly clOrdId: number;
    readonly title: string;
    readonly side: Side;
    /** Everything after the side word: quantities, prices, type. */
    readonly detail: string;
}

export type NoticeDraft = Omit<OrderNotice, "id">;

export const NOTICE_TITLES: Readonly<Record<NoticeKind, string>> = {
    submitted: "Order submitted",
    partial: "Partially filled",
    filled: "Order filled",
    cancelled: "Order cancelled",
    rejected: "Order rejected",
    expired: "Order expired",
    modifyNotSent: "Modify not sent: order filled",
    modifyFailed: "Modify failed",
};

/** Most toasts on screen at once; the oldest is dropped beyond it. */
export const TOAST_CAP = 4;
/** Auto-dismiss delay; hovering a toast pauses it. */
export const TOAST_TTL_MS = 5000;

function priceText(order: MyOrder): string {
    return order.ordType === "PEG_MID" ? "MID" : formatPrice(order.pricePx);
}

function typeText(order: MyOrder): string {
    if (order.ordType === "PEG_MID") return "MIDPOINT PEG";
    return order.displayQty > 0 ? "ICEBERG" : "LIMIT";
}

function draft(kind: NoticeKind, order: MyOrder, detail: string): NoticeDraft {
    return { kind, clOrdId: order.clOrdId, title: NOTICE_TITLES[kind], side: order.side, detail };
}

function isFillType(frame: ExecFrame): boolean {
    return frame.execType === "ORDER_FILLED" || frame.execType === "ORDER_PARTIALLY_FILLED";
}

/**
 * The notices one EXEC raises for this terminal's own orders: zero, one, or two
 * (when both sides of a trade are ours). `before` and `after` are the reducer's
 * myOrders on either side of the frame; the reducer keeps row indices stable.
 */
export function noticesForExec(
    before: readonly MyOrder[],
    after: readonly MyOrder[],
    frame: ExecFrame,
): NoticeDraft[] {
    const out: NoticeDraft[] = [];

    const ai = frame.orderId > 0 ? before.findIndex((o) => o.clOrdId === frame.orderId) : -1;
    if (ai !== -1 && after[ai] !== before[ai]) {
        const b = before[ai];
        switch (frame.execType) {
            case "ORDER_ACCEPTED":
                if (b.status === "PENDING") {
                    const detail =
                        b.ordType === "PEG_MID"
                            ? `${b.originalQty} ${typeText(b)}`
                            : `${b.originalQty} ${typeText(b)} @ ${priceText(b)}`;
                    out.push(draft("submitted", b, detail));
                }
                break;
            case "ORDER_PARTIALLY_FILLED":
                out.push(
                    draft(
                        "partial",
                        b,
                        `${frame.filledQuantity} @ ${formatPrice(frame.price)}, ${frame.remainingQuantity} remaining`,
                    ),
                );
                break;
            case "ORDER_FILLED":
                out.push(draft("filled", b, `${frame.filledQuantity} @ ${formatPrice(frame.price)}`));
                break;
            case "ORDER_CANCELLED":
                out.push(draft("cancelled", b, `${b.remainingQty} @ ${priceText(b)}`));
                break;
            case "ORDER_REJECTED":
                out.push(draft("rejected", b, `${b.originalQty} @ ${priceText(b)}`));
                break;
            case "ORDER_EXPIRED":
                out.push(draft("expired", b, `${frame.remainingQuantity} unfilled`));
                break;
        }
    }

    // Passive side: our resting order was hit. It receives no EXEC of its own, so the
    // notice comes from its locally decremented row.
    if (isFillType(frame) && frame.passiveOrderId > 0 && frame.passiveOrderId !== frame.orderId) {
        const pi = before.findIndex((o) => o.clOrdId === frame.passiveOrderId);
        if (pi !== -1 && after[pi] !== before[pi]) {
            const a = after[pi];
            const fill = `${frame.filledQuantity} @ ${formatPrice(frame.price)}`;
            out.push(
                a.status === "FILLED"
                    ? draft("filled", a, fill)
                    : draft("partial", a, `${fill}, ${a.remainingQty} remaining`),
            );
        }
    }

    return out;
}

export type ToastAction =
    | { readonly type: "add"; readonly notice: OrderNotice }
    | { readonly type: "dismiss"; readonly id: number }
    | { readonly type: "expire"; readonly id: number };

/**
 * The visible toast stack, oldest first (rendered top to bottom, so the newest is
 * at the bottom). Adding beyond TOAST_CAP drops the oldest; a repeated id is a
 * no-op. Dismiss (the exit) and expire (the timer) both remove one toast.
 */
export function toastQueue(state: readonly OrderNotice[], action: ToastAction): readonly OrderNotice[] {
    switch (action.type) {
        case "add": {
            if (state.some((t) => t.id === action.notice.id)) return state;
            const next = [...state, action.notice];
            return next.length > TOAST_CAP ? next.slice(next.length - TOAST_CAP) : next;
        }
        case "dismiss":
        case "expire":
            return state.some((t) => t.id === action.id) ? state.filter((t) => t.id !== action.id) : state;
    }
}
