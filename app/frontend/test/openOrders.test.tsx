import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { OpenOrders } from "../src/components/OpenOrders";
import type { MyOrder, OrderStatus } from "../src/state/reducer";
import type { Side } from "../src/protocol/messages";
import { EMPTY_PRICE } from "../src/format";

afterEach(cleanup);

function makeOrder(
    clOrdId: number,
    status: OrderStatus,
    side: Side = "BUY",
    pricePx = 1500000,
    remainingQty = 10,
    originalQty = 10,
    sentAtNanos?: number,
): MyOrder {
    return {
        clOrdId,
        side,
        pricePx,
        originalQty,
        remainingQty,
        status,
        ...(sentAtNanos !== undefined ? { sentAtNanos } : {}),
    };
}

describe("<OpenOrders />", () => {
    it("renders one row per order with id, side, dollar price, filled/total, and status", () => {
        render(<OpenOrders orders={[makeOrder(7, "OPEN", "BUY", 1502500, 8)]} onCancel={vi.fn()} />);

        const rows = screen.getAllByTestId("open-orders-row");
        expect(rows).toHaveLength(1);
        const text = rows[0].textContent ?? "";
        expect(text).toContain("7");
        expect(text).toContain("BUY");
        expect(text).toContain("150.25");
        expect(text).toContain("2 / 10"); // filled 2 of original 10 (remaining 8)
        expect(text).toContain("OPEN");
    });

    it("shows filled / total derived from original minus remaining", () => {
        render(
            <OpenOrders
                orders={[makeOrder(3, "PARTIALLY_FILLED", "BUY", 1500000, 6, 10)]}
                onCancel={vi.fn()}
            />,
        );
        expect(screen.getByTestId("filled-3").textContent).toBe("4 / 10");
    });

    it("renders a client-assigned send time when the row carries one", () => {
        render(
            <OpenOrders
                orders={[makeOrder(5, "OPEN", "BUY", 1500000, 10, 10, 1_700_000_000_123_000_000)]}
                onCancel={vi.fn()}
            />,
        );
        // Local HH:MM:SS.mmm; the wall-clock hour is timezone-dependent, so assert shape.
        expect(screen.getByTestId("sent-5").textContent).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{3}$/);
    });

    it("renders an empty send-time cell when the row has none", () => {
        render(<OpenOrders orders={[makeOrder(6, "OPEN")]} onCancel={vi.fn()} />);
        expect(screen.getByTestId("sent-6").textContent).toBe(EMPTY_PRICE);
    });

    it("offers Cancel only on cancellable rows (OPEN, PARTIALLY_FILLED)", () => {
        render(
            <OpenOrders
                orders={[
                    makeOrder(1, "PENDING"),
                    makeOrder(2, "OPEN"),
                    makeOrder(3, "PARTIALLY_FILLED"),
                    makeOrder(4, "FILLED"),
                    makeOrder(5, "CANCELLED"),
                    makeOrder(6, "REJECTED"),
                ]}
                onCancel={vi.fn()}
            />,
        );

        expect(screen.queryByTestId("cancel-1")).toBeNull();
        expect(screen.getByTestId("cancel-2")).not.toBeNull();
        expect(screen.getByTestId("cancel-3")).not.toBeNull();
        expect(screen.queryByTestId("cancel-4")).toBeNull();
        expect(screen.queryByTestId("cancel-5")).toBeNull();
        expect(screen.queryByTestId("cancel-6")).toBeNull();
    });

    it("calls onCancel with the row's own clOrdId (the origClOrdId to cancel)", () => {
        const onCancel = vi.fn();
        render(<OpenOrders orders={[makeOrder(42, "OPEN")]} onCancel={onCancel} />);

        fireEvent.click(screen.getByTestId("cancel-42"));

        expect(onCancel).toHaveBeenCalledTimes(1);
        expect(onCancel).toHaveBeenCalledWith(42);
    });

    it("renders a clean empty state with no rows", () => {
        render(<OpenOrders orders={[]} onCancel={vi.fn()} />);

        expect(screen.getByTestId("open-orders-empty")).not.toBeNull();
        expect(screen.queryAllByTestId("open-orders-row")).toHaveLength(0);
    });

    it("formats the price via formatPrice and never leaks a sentinel", () => {
        render(<OpenOrders orders={[makeOrder(9, "OPEN", "SELL", 500, 1)]} onCancel={vi.fn()} />);

        const priceCell = screen.getByTestId("open-orders-row").querySelector(".open-orders__price");
        expect(priceCell?.textContent).toBe("0.05");
    });

    it("puts the rows in a dedicated scroll body with the header row above them (P8-5)", () => {
        render(
            <OpenOrders orders={[makeOrder(1, "OPEN"), makeOrder(2, "OPEN")]} onCancel={vi.fn()} />,
        );

        // The body is the scroll container: every order row lives inside it.
        const scroll = screen.getByTestId("open-orders-scroll");
        const rows = screen.getAllByTestId("open-orders-row");
        expect(rows).toHaveLength(2);
        rows.forEach((row) => expect(scroll.contains(row)).toBe(true));

        // Sticky-header contract: the single table's column header is a <thead>
        // inside that same scroll body, ahead of the rows in document order, so the
        // CSS pins it to the top while the rows scroll under it. jsdom does not apply
        // terminal.css, so this locks the DOM contract the sticky / scroll rules key
        // off, not the computed position.
        const table = scroll.querySelector("table.open-orders__table");
        expect(table).not.toBeNull();
        const thead = table!.querySelector("thead");
        expect(thead).not.toBeNull();
        expect(scroll.contains(thead!)).toBe(true);
        expect(
            thead!.compareDocumentPosition(rows[0]) & Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy();
    });
});
