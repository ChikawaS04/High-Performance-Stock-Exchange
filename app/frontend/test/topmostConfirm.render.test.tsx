import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

// The topmost confirmation popups (DESIGN_SYSTEM.md §10): Order Confirmation and Cancel
// by Order ID render into the page body above everything else, and a click outside
// either plays the alert sound instead of closing it.

vi.mock("../src/alertSound", () => ({ playAlertSound: vi.fn() }));

import { playAlertSound } from "../src/alertSound";
import { OrderEntry } from "../src/components/OrderEntry";
import { OpenOrders } from "../src/components/OpenOrders";
import type { MyOrder } from "../src/state/reducer";

const alert = vi.mocked(playAlertSound);

beforeEach(() => alert.mockClear());
afterEach(cleanup);

const CSS = readFileSync(resolve(__dirname, "../src/styles/terminal.css"), "utf-8");

/** The z-index a rule declares (the last declaration of the selector wins). */
function zIndex(selector: string): number {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const re = new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`, "g");
    let z = Number.NaN;
    let m: RegExpExecArray | null;
    while ((m = re.exec(CSS)) !== null) {
        const d = /z-index:\s*(\d+)/.exec(m[1]);
        if (d !== null) z = Number(d[1]);
    }
    return z;
}

function openOrderConfirm(): void {
    render(<OrderEntry onSubmit={vi.fn()} />);
    fireEvent.change(screen.getByTestId("price-input"), { target: { value: "150.00" } });
    fireEvent.change(screen.getByTestId("qty-input"), { target: { value: "5" } });
    fireEvent.click(screen.getByTestId("order-submit"));
}

function openCancelById(): void {
    render(<OpenOrders orders={[]} onCancel={vi.fn()} />);
    fireEvent.click(screen.getByTestId("blotter-menu-btn"));
    fireEvent.click(screen.getByTestId("cancel-by-id-open"));
}

describe("topmost confirmation popups", () => {
    it("stack above everything else in the terminal", () => {
        const top = zIndex(".confirm-topmost");
        expect(top).toBe(1000);
        // The settings view, its backdrop, toasts, floating windows and the pick-list portal.
        for (const sel of [
            ".settings-pop.settings-pop--view",
            ".settings-pop__backdrop--view",
            ".order-toasts",
            ".price-overlay",
            ".depth-overlay",
            ".field-menu__menu--portal",
        ]) {
            expect(zIndex(sel), sel).toBeLessThan(top);
        }
        // The rule comes after both overlays' own z-index, so it wins at equal specificity.
        expect(CSS.lastIndexOf(".confirm-topmost {")).toBeGreaterThan(CSS.indexOf(".order-confirm__overlay {"));
        expect(CSS.lastIndexOf(".confirm-topmost {")).toBeGreaterThan(CSS.indexOf(".cxid-overlay {"));
    });

    it("keeps toasts above the chart settings view and its backdrop, below the pick-lists", () => {
        const toasts = zIndex(".order-toasts");
        expect(toasts).toBeGreaterThan(zIndex(".settings-pop.settings-pop--view"));
        expect(toasts).toBeGreaterThan(zIndex(".settings-pop__backdrop--view"));
        expect(toasts).toBeLessThan(zIndex(".field-menu__menu--portal"));
    });

    it("Order Confirmation renders into the body as topmost and refuses an outside click with the alert", () => {
        openOrderConfirm();
        const overlay = screen.getByTestId("order-confirm-overlay");
        expect(overlay.parentElement).toBe(document.body);
        expect(overlay.className).toContain("confirm-topmost");
        fireEvent.mouseDown(overlay);
        expect(alert).toHaveBeenCalledTimes(1);
        expect(screen.getByTestId("order-confirm-overlay")).not.toBeNull();
        // A press inside the popup is not "elsewhere".
        fireEvent.mouseDown(screen.getByTestId("order-confirm-headline"));
        expect(alert).toHaveBeenCalledTimes(1);
        // Cancel and the exit still close it.
        fireEvent.click(screen.getByTestId("order-confirm-cancel"));
        expect(screen.queryByTestId("order-confirm-overlay")).toBeNull();
    });

    it("Cancel by Order ID renders into the body as topmost and refuses an outside click with the alert", () => {
        openCancelById();
        const overlay = screen.getByTestId("cancel-by-id-overlay");
        expect(overlay.parentElement).toBe(document.body);
        expect(overlay.className).toContain("confirm-topmost");
        fireEvent.mouseDown(overlay);
        fireEvent.mouseDown(overlay);
        expect(alert).toHaveBeenCalledTimes(2);
        expect(screen.getByTestId("cancel-by-id-overlay")).not.toBeNull();
        fireEvent.mouseDown(screen.getByTestId("cancel-by-id-input"));
        expect(alert).toHaveBeenCalledTimes(2);
        fireEvent.keyDown(document, { key: "Escape" });
        expect(screen.queryByTestId("cancel-by-id-overlay")).toBeNull();
    });

    it("leaves the row Cancel confirmation as it was: not topmost, an outside click closes it", () => {
        const order: MyOrder = {
            clOrdId: 5,
            side: "SELL",
            pricePx: 14_466_900,
            originalQty: 10,
            remainingQty: 10,
            status: "OPEN",
            ordType: "LIMIT",
            tif: "GTC",
            displayQty: 0,
        };
        render(<OpenOrders orders={[order]} onCancel={vi.fn()} />);
        fireEvent.click(screen.getByTestId("cancel-5"));
        const overlay = screen.getByTestId("cancel-confirm-overlay");
        expect(overlay.className).not.toContain("confirm-topmost");
        fireEvent.mouseDown(overlay);
        expect(alert).not.toHaveBeenCalled();
        expect(screen.queryByTestId("cancel-confirm-overlay")).toBeNull();
    });
});

describe("Escape in the blotter popups", () => {
    it("closes only Cancel by Order ID, never the floating window behind it", () => {
        const windowClose = vi.fn();
        const onKey = (e: KeyboardEvent): void => {
            if (e.key === "Escape") windowClose();
        };
        // The floating window listens on the document in the bubble phase.
        document.addEventListener("keydown", onKey);
        try {
            openCancelById();
            fireEvent.keyDown(screen.getByTestId("cancel-by-id-input"), { key: "Escape" });
            expect(screen.queryByTestId("cancel-by-id-overlay")).toBeNull();
            expect(windowClose).not.toHaveBeenCalled();
            // With no popup open, Escape reaches the window as before.
            fireEvent.keyDown(document.body, { key: "Escape" });
            expect(windowClose).toHaveBeenCalledTimes(1);
        } finally {
            document.removeEventListener("keydown", onKey);
        }
    });
});
