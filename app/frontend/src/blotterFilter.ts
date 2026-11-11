/**
 * Blotter status filter (UI polish D6). Pure, so every option is unit tested
 * without a DOM; the blotter header's single-select drives it.
 *
 * ACTIVE is "everything still working": any non-terminal row, so PENDING (sent,
 * not yet acknowledged), OPEN and PARTIALLY_FILLED. PARTIALLY FILLED is the
 * working subset with at least one fill, so it overlaps ACTIVE on purpose.
 * CANCELLED includes a cancelled remainder after partial fills. REJECTED and
 * EXPIRED rows appear under ALL ORDERS only.
 */

import { isTerminal } from "./state/reducer";
import type { MyOrder } from "./state/reducer";

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
export function matchesStatusFilter(order: MyOrder, filter: StatusFilter): boolean {
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
export function filterOrders(orders: readonly MyOrder[], filter: StatusFilter): readonly MyOrder[] {
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
