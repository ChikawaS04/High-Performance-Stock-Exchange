/**
 * Reprice sequencing (P18 D5, amended at P18-0 and at the greenlight). Pure: no
 * React, no socket, no timers. The reducer holds the entries (AppState.reprices)
 * and steps them on every EXEC frame, because React batches several frames into one
 * render and every transition must see each frame in order.
 *
 * The engine accepts 35=D and 35=F only, so a reprice is a cancel, then a new
 * order once the cancel is confirmed. One entry per original order:
 *
 *   cancelSent  the CANCEL went on the wire (SENT with reprice meta creates it)
 *     -> ready      ORDER_CANCELLED for the original: the quantity still remaining
 *                   at cancel is fixed, and App sends the replacement NEW
 *     -> aborted(filled)  a fill left the original FILLED: nothing to replace
 *     -> aborted(failed)  ORDER_REJECTED for the original (the cancel was refused),
 *                   or no answer within REPRICE_TIMEOUT_MS
 *   ready
 *     -> done       SENT of the replacement NEW (with repriceOf meta)
 *     -> aborted(failed)  the replacement's socket write failed
 *
 * "Idle" is no entry. A terminal entry (done, aborted) is removed in the same step
 * that raises its toast, so it can never raise a second one: a full fill followed by
 * the cancel's ORDER_REJECTED (the engine refuses a cancel on a filled order) gives
 * exactly one "Modify not sent: order filled" toast, and any later frame for that
 * order finds no entry and is ignored (greenlight amendments 1 and 5).
 *
 * A ready entry absorbs every frame: the original is already cancelled, so nothing
 * else can happen to it. A timeout carries its order id and only aborts an entry
 * that is still cancelSent, so a stale timer cannot abort a later reprice.
 */

import { formatPrice, formatQty } from "./format";
import { NOTICE_TITLES } from "./notices";
import type { NoticeDraft } from "./notices";
import { isFill } from "./protocol/messages";
import type { ExecFrame, OrderIntent, Side } from "./protocol/messages";
import type { MyOrder } from "./state/reducer";

/** No answer to the cancel within this long aborts the reprice (P18-0 F4: observed worst about 33 ms). */
export const REPRICE_TIMEOUT_MS = 2000;

export type RepriceStatus = "cancelSent" | "ready";

/**
 * A live reprice. Only cancelSent and ready are ever stored; done and aborted are
 * the transitions that remove an entry.
 */
export interface Reprice {
    readonly origClOrdId: number;
    readonly side: Side;
    readonly oldPx: number;
    readonly newPx: number;
    /** Remaining quantity: at the drag while cancelSent, fixed at the cancel once ready. */
    readonly qty: number;
    readonly status: RepriceStatus;
}

function detail(r: Reprice, qty: number): string {
    return `${formatQty(qty)} @ ${formatPrice(r.oldPx)}, new ${formatPrice(r.newPx)} not sent`;
}

/** The toast for an aborted reprice. */
export function abortNotice(r: Reprice, reason: "filled" | "failed", qty: number = r.qty): NoticeDraft {
    return reason === "filled"
        ? { kind: "modifyNotSent", clOrdId: r.origClOrdId, title: NOTICE_TITLES.modifyNotSent, side: r.side, detail: detail(r, qty) }
        : { kind: "modifyFailed", clOrdId: r.origClOrdId, title: NOTICE_TITLES.modifyFailed, side: r.side, detail: detail(r, qty) };
}

/** A new entry for a cancel just written to the wire, from the order's row. */
export function beginReprice(row: MyOrder, newPx: number): Reprice {
    return { origClOrdId: row.clOrdId, side: row.side, oldPx: row.pricePx, newPx, qty: row.remainingQty, status: "cancelSent" };
}

export interface RepriceStep {
    readonly reprices: readonly Reprice[];
    readonly drafts: readonly NoticeDraft[];
}

/**
 * Step every entry on one EXEC frame. `rows` is myOrders AFTER the frame was
 * applied, so a fill's effect on the original (FILLED or not) is already there.
 * Returns the same array when nothing changes.
 */
export function stepReprices(reprices: readonly Reprice[], frame: ExecFrame, rows: readonly MyOrder[]): RepriceStep {
    if (reprices.length === 0) return { reprices, drafts: [] };
    let changed = false;
    const drafts: NoticeDraft[] = [];
    const out: Reprice[] = [];
    for (const r of reprices) {
        if (r.status !== "cancelSent") {
            out.push(r); // ready absorbs every frame
            continue;
        }
        const id = r.origClOrdId;
        const row = rows.find((o) => o.clOrdId === id);
        const names = frame.orderId === id || (isFill(frame) && frame.passiveOrderId === id);
        if (!names) {
            out.push(r);
            continue;
        }
        if (isFill(frame)) {
            if (row !== undefined && row.status === "FILLED") {
                changed = true;
                drafts.push(abortNotice(r, "filled", r.qty));
                continue; // removed
            }
            out.push(r); // a partial fill: the cancel still decides
            continue;
        }
        if (frame.execType === "ORDER_CANCELLED") {
            changed = true;
            const qty = row !== undefined ? row.remainingQty : r.qty;
            if (qty <= 0) {
                drafts.push(abortNotice(r, "filled", r.qty));
                continue;
            }
            out.push({ ...r, qty, status: "ready" });
            continue;
        }
        if (frame.execType === "ORDER_REJECTED") {
            changed = true;
            drafts.push(abortNotice(r, "failed"));
            continue;
        }
        out.push(r);
    }
    return { reprices: changed ? out : reprices, drafts };
}

/** No answer in time: abort the entry if, and only if, it is still cancelSent. */
export function timeoutReprice(reprices: readonly Reprice[], origClOrdId: number): RepriceStep {
    const r = reprices.find((x) => x.origClOrdId === origClOrdId);
    if (r === undefined || r.status !== "cancelSent") return { reprices, drafts: [] };
    return { reprices: reprices.filter((x) => x !== r), drafts: [abortNotice(r, "failed")] };
}

/** The replacement's socket write failed: abort a ready entry. */
export function sendFailedReprice(reprices: readonly Reprice[], origClOrdId: number): RepriceStep {
    const r = reprices.find((x) => x.origClOrdId === origClOrdId);
    if (r === undefined || r.status !== "ready") return { reprices, drafts: [] };
    return { reprices: reprices.filter((x) => x !== r), drafts: [abortNotice(r, "failed")] };
}

/** The replacement went on the wire: the entry is done and removed. */
export function completeReprice(reprices: readonly Reprice[], origClOrdId: number): readonly Reprice[] {
    const next = reprices.filter((x) => !(x.origClOrdId === origClOrdId && x.status === "ready"));
    return next.length === reprices.length ? reprices : next;
}

/**
 * The replacement order (P18 D4): only the quantity still remaining at cancel is
 * re-sent; TIF, type and iceberg display carry over. An iceberg whose remainder is
 * no larger than its display size goes as a plain limit (the ticket's rule that a
 * display >= qty is not an iceberg), so the frame is always valid.
 */
export function replacementIntent(row: MyOrder, r: Reprice): OrderIntent {
    const displayQty = row.displayQty > 0 && row.displayQty < r.qty ? row.displayQty : 0;
    return { side: row.side, ordType: "LIMIT", tif: row.tif, pricePx: r.newPx, qty: r.qty, displayQty };
}
