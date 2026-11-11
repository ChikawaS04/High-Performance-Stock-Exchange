import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { OpenOrders } from "../src/components/OpenOrders";
import type { MyOrder, OrderStatus } from "../src/state/reducer";

// No vitest globals, so RTL's auto-cleanup never registers. Wire it explicitly.
afterEach(cleanup);

function order(clOrdId: number, status: OrderStatus, remainingQty = 10, originalQty = 10): MyOrder {
    return {
        clOrdId,
        side: "SELL",
        pricePx: 14_466_900,
        originalQty,
        remainingQty,
        status,
        ordType: "LIMIT",
        tif: "GTC",
        displayQty: 0,
    };
}

describe("blotter cancel confirmation (D5)", () => {
    it("opens the popup on the row Cancel and sends nothing", () => {
        const onCancel = vi.fn();
        render(<OpenOrders orders={[order(42, "OPEN", 7)]} onCancel={onCancel} />);
        fireEvent.click(screen.getByTestId("cancel-42"));

        expect(onCancel).not.toHaveBeenCalled();
        const dialog = screen.getByRole("dialog", { name: "Cancel order" });
        expect(screen.getByTestId("cancel-confirm-id").textContent).toBe("42");
        expect(dialog.textContent).toContain("SELL");
        expect(dialog.textContent).toContain("7");
        expect(dialog.textContent).toContain("1446.69");
        expect(dialog.textContent).toContain("LIMIT");
    });

    it("sends exactly one cancel for the right order on Confirm Cancel, then closes", () => {
        const onCancel = vi.fn();
        render(<OpenOrders orders={[order(41, "OPEN"), order(42, "OPEN")]} onCancel={onCancel} />);
        fireEvent.click(screen.getByTestId("cancel-42"));
        fireEvent.click(screen.getByTestId("cancel-confirm-submit"));

        expect(onCancel).toHaveBeenCalledTimes(1);
        expect(onCancel).toHaveBeenCalledWith(42);
        expect(screen.queryByTestId("cancel-confirm")).toBeNull();
    });

    it("closes without cancelling on Escape, the exit, and an outside click", () => {
        const onCancel = vi.fn();
        render(<OpenOrders orders={[order(42, "OPEN")]} onCancel={onCancel} />);

        fireEvent.click(screen.getByTestId("cancel-42"));
        fireEvent.keyDown(document, { key: "Escape" });
        expect(screen.queryByTestId("cancel-confirm")).toBeNull();

        fireEvent.click(screen.getByTestId("cancel-42"));
        fireEvent.click(screen.getByTestId("cancel-confirm-close"));
        expect(screen.queryByTestId("cancel-confirm")).toBeNull();

        fireEvent.click(screen.getByTestId("cancel-42"));
        fireEvent.mouseDown(screen.getByTestId("cancel-confirm-overlay"));
        expect(screen.queryByTestId("cancel-confirm")).toBeNull();

        expect(onCancel).not.toHaveBeenCalled();
    });

    it("does not confirm on Enter", () => {
        const onCancel = vi.fn();
        render(<OpenOrders orders={[order(42, "OPEN")]} onCancel={onCancel} />);
        fireEvent.click(screen.getByTestId("cancel-42"));

        fireEvent.keyDown(document, { key: "Enter" });
        fireEvent.keyDown(screen.getByTestId("cancel-confirm"), { key: "Enter" });
        fireEvent.keyDown(screen.getByTestId("cancel-confirm-submit"), { key: "Enter" });

        expect(onCancel).not.toHaveBeenCalled();
        expect(screen.getByTestId("cancel-confirm")).not.toBeNull();
    });

    it("disables Confirm Cancel and says so when the order fills while the popup is open", () => {
        const onCancel = vi.fn();
        const { rerender } = render(<OpenOrders orders={[order(42, "OPEN")]} onCancel={onCancel} />);
        fireEvent.click(screen.getByTestId("cancel-42"));
        expect(screen.queryByTestId("cancel-confirm-inactive")).toBeNull();

        rerender(<OpenOrders orders={[order(42, "FILLED", 0)]} onCancel={onCancel} />);

        expect(screen.getByTestId("cancel-confirm-inactive").textContent).toBe("Order is no longer active");
        const submit = screen.getByTestId("cancel-confirm-submit") as HTMLButtonElement;
        expect(submit.disabled).toBe(true);
        fireEvent.click(submit);
        expect(onCancel).not.toHaveBeenCalled();
    });

    it("leaves Cancel by Order ID as it was", () => {
        const onCancel = vi.fn();
        render(<OpenOrders orders={[]} onCancel={onCancel} />);
        fireEvent.click(screen.getByTestId("blotter-menu-btn"));
        fireEvent.click(screen.getByTestId("cancel-by-id-open"));
        fireEvent.change(screen.getByTestId("cancel-by-id-input"), { target: { value: "77" } });
        fireEvent.click(screen.getByTestId("cancel-by-id-submit"));
        expect(onCancel).toHaveBeenCalledWith(77);
    });
});

describe("blotter status filter (D6)", () => {
    const ROWS = [order(1, "OPEN"), order(2, "PARTIALLY_FILLED", 4), order(3, "CANCELLED", 4), order(4, "REJECTED")];

    function pick(value: string): void {
        fireEvent.click(screen.getByTestId("blotter-filter-trigger"));
        fireEvent.mouseDown(screen.getByTestId(`blotter-filter-opt-${value}`));
    }

    it("is a shared FieldMenu in the header band, defaulting to ALL ORDERS", () => {
        render(<OpenOrders orders={ROWS} onCancel={vi.fn()} />);
        const trigger = screen.getByTestId("blotter-filter-trigger");
        expect(trigger.textContent).toBe("ALL ORDERS");
        expect(trigger.closest(".open-orders__head")).not.toBeNull();
        expect(screen.getAllByTestId("open-orders-row")).toHaveLength(4);
    });

    it("shows only the rows each option selects", () => {
        render(<OpenOrders orders={ROWS} onCancel={vi.fn()} />);
        pick("ACTIVE");
        expect(screen.getAllByTestId("open-orders-row").map((r) => r.firstElementChild!.textContent)).toEqual([
            "1",
            "2",
        ]);
        pick("CANCELLED");
        expect(screen.getAllByTestId("open-orders-row").map((r) => r.firstElementChild!.textContent)).toEqual([
            "3",
        ]);
        expect(screen.getByTestId("blotter-filter-trigger").textContent).toBe("CANCELLED");
    });

    it("shows the dim empty message when nothing matches", () => {
        render(<OpenOrders orders={ROWS} onCancel={vi.fn()} />);
        pick("FILLED");
        expect(screen.queryAllByTestId("open-orders-row")).toHaveLength(0);
        expect(screen.getByTestId("open-orders-filter-empty").textContent).toBe("No filled orders");
    });

    it("keeps the original empty state when there are no orders at all", () => {
        render(<OpenOrders orders={[]} onCancel={vi.fn()} />);
        expect(screen.getByTestId("open-orders-empty").textContent).toBe("No orders yet");
    });
});
