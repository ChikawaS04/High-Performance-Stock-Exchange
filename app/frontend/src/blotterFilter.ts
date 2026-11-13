/**
 * Blotter status filter (UI polish D6). Pure, so every option is unit tested
 * without a DOM; the blotter header's single-select drives it.
 *
 * ACTIVE is "everything still working": any non-terminal row, so PENDING (sent,
 * not yet acknowledged), OPEN and PARTIALLY_FILLED. PARTIALLY FILLED is the
 * working subset with at least one fill, so it overlaps ACTIVE on purpose.
 * CANCELLED includes a cancelled remainder after partial fills. REJECTED and
 * EXPIRED rows appear under ALL ORDERS only.
 *
 * Owner filter (simulation bots): MINE shows this terminal's orders only (the
 * default, so the blotter reads exactly as before), ALL adds every other
 * participant's order seen on the EXEC stream, and a bot name shows that bot's
 * orders alone. The status filter then applies to whichever rows the owner filter
 * chose. blotterRows builds one row shape for both sources.
 */

import { isTerminal } from "./state/reducer";
import type { MarketOrder, MyOrder, OrderStatus } from "./state/reducer";
import type { OrdType, Side, TimeInForce } from "./protocol/messages";
import { BOT_OWNERS, OWNER_LABELS, botOwnerOf, ownerOf } from "./participants";
import type { BotOwner, Owner } from "./participants";

/** The fields the status filter reads. Both order shapes have them. */
export type StatusRow = Pick<MyOrder, "status" | "originalQty" | "remainingQty">;

export type StatusFilter = "ALL" | "ACTIVE" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED";

export const DEFAULT_STATUS_FILTER: StatusFilter = "ALL";

/** Options in menu order, labels all caps (DESIGN_SYSTEM.md §3). */
export const STATUS_FILTER_OPTIONS: readonly { readonly value: StatusFilter; readonly label: string }[] = [
    { value: "ALL", label: "ALL ORDERS" },
    { value: "ACTIVE", label: "ACTIVE" },
    { value: "PARTIALLY_FILLED", label: "PARTIALLY FILLED" },
    { value: "FILLED", label: "FILLED" },
    { value: "CANCELLED", label: "CANCELLED" },
];

/** True for a value that names one of the filter options (used to validate storage). */
export function isStatusFilter(value: unknown): value is StatusFilter {
    return STATUS_FILTER_OPTIONS.some((o) => o.value === value);
}

/** Whether one order is shown under the given filter. */
export function matchesStatusFilter(order: StatusRow, filter: StatusFilter): boolean {
    switch (filter) {
        case "ALL":
            return true;
        case "ACTIVE":
            return !isTerminal(order.status);
        case "PARTIALLY_FILLED":
            return !isTerminal(order.status) && order.originalQty - order.remainingQty > 0;
        case "FILLED":
            return order.status === "FILLED";
        case "CANCELLED":
            return order.status === "CANCELLED";
    }
}

/** The order-preserving subset of rows the filter shows. */
export function filterOrders<T extends StatusRow>(orders: readonly T[], filter: StatusFilter): readonly T[] {
    return filter === "ALL" ? orders : orders.filter((o) => matchesStatusFilter(o, filter));
}

/** The dim one-line message for a filter that matches no rows. */
export function emptyFilterMessage(filter: StatusFilter): string {
    switch (filter) {
        case "ALL":
            return "No orders yet";
        case "ACTIVE":
            return "No active orders";
        case "PARTIALLY_FILLED":
            return "No partially filled orders";
        case "FILLED":
            return "No filled orders";
        case "CANCELLED":
            return "No cancelled orders";
    }
}

// --- owner filter (simulation bots) -------------------------------------------------

export type OwnerFilter = "MINE" | "ALL" | BotOwner;

export const DEFAULT_OWNER_FILTER: OwnerFilter = "MINE";

/** Options in menu order, labels all caps (DESIGN_SYSTEM.md §3). */
export const OWNER_FILTER_OPTIONS: readonly { readonly value: OwnerFilter; readonly label: string }[] = [
    { value: "MINE", label: "MINE" },
    { value: "ALL", label: "ALL PARTICIPANTS" },
    ...BOT_OWNERS.map((b) => ({ value: b, label: OWNER_LABELS[b] })),
];

/** True for a value that names one of the owner options (used to validate storage). */
export function isOwnerFilter(value: unknown): value is OwnerFilter {
    return OWNER_FILTER_OPTIONS.some((o) => o.value === value);
}

/**
 * One blotter row, from this terminal's order or another participant's. tif is
 * null and pricePx -1 where the wire never stated them for another participant's
 * order; displayQty is 0 for those (an iceberg's display size is not on the wire).
 * timeNanos is our send time for our own orders and the venue time of the first
 * report for anyone else's; absent when unknown.
 */
export interface BlotterRow {
    readonly clOrdId: number;
    readonly owner: Owner;
    readonly side: Side;
    readonly pricePx: number;
    readonly originalQty: number;
    readonly remainingQty: number;
    readonly status: OrderStatus;
    readonly ordType: OrdType;
    readonly tif: TimeInForce | null;
    readonly displayQty: number;
    readonly timeNanos?: number;
}

function mineRow(o: MyOrder): BlotterRow {
    return {
        clOrdId: o.clOrdId,
        owner: "YOU",
        side: o.side,
        pricePx: o.pricePx,
        originalQty: o.originalQty,
        remainingQty: o.remainingQty,
        status: o.status,
        ordType: o.ordType,
        tif: o.tif,
        displayQty: o.displayQty,
        ...(o.sentAtNanos !== undefined ? { timeNanos: o.sentAtNanos } : {}),
    };
}

function marketRow(o: MarketOrder): BlotterRow {
    return {
        clOrdId: o.clOrdId,
        owner: ownerOf(o.clOrdId, false),
        side: o.side,
        pricePx: o.pricePx,
        originalQty: o.originalQty,
        remainingQty: o.remainingQty,
        status: o.status,
        ordType: o.ordType,
        tif: o.tif,
        displayQty: 0,
        timeNanos: o.firstSeenNanos,
    };
}

/**
 * The rows the owner filter shows, newest first. MINE keeps myOrders' own order.
 * ALL merges both sources by time, newest first; a row with no time sorts as the
 * oldest, and ties keep our own rows first. A bot filter shows that bot's rows.
 */
export function blotterRows(
    myOrders: readonly MyOrder[],
    marketOrders: readonly MarketOrder[],
    filter: OwnerFilter,
): readonly BlotterRow[] {
    if (filter === "MINE") return myOrders.map(mineRow);
    if (filter === "ALL") {
        const rows = [...myOrders.map(mineRow), ...marketOrders.map(marketRow)];
        return rows
            .map((row, i) => ({ row, i }))
            .sort((a, b) => (b.row.timeNanos ?? 0) - (a.row.timeNanos ?? 0) || a.i - b.i)
            .map((x) => x.row);
    }
    return marketOrders.filter((o) => botOwnerOf(o.clOrdId) === filter).map(marketRow);
}

/** The dim line for an owner and status combination that matches no rows. */
export function emptyBlotterMessage(owner: OwnerFilter, status: StatusFilter): string {
    if (status !== "ALL") return emptyFilterMessage(status);
    if (owner === "MINE" || owner === "ALL") return "No orders yet";
    return `No ${OWNER_LABELS[owner]} orders yet`;
}
