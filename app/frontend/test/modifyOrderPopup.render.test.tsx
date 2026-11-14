import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { MARKETABLE_LINE, ModifyOrderPopup, QUEUE_LINE } from "../src/components/ModifyOrderPopup";
import type { ModifyRequest } from "../src/components/ModifyOrderPopup";
import { formatPrice } from "../src/format";

afterEach(() => {
    cleanup();
});

const REQ: ModifyRequest = { clOrdId: 1_763_000_000_001, side: "BUY", remainingQty: 1_200, oldPx: 1_000_000, newPx: 1_000_300, marketable: false };

function open(over: Partial<ModifyRequest> = {}, active = true) {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    render(<ModifyOrderPopup request={{ ...REQ, ...over }} active={active} onConfirm={onConfirm} onClose={onClose} />);
    return { onConfirm, onClose };
}

describe("Modify order popup (P18-4)", () => {
    it("shows the order id, side, remaining, old and new price, and the queue line", () => {
        open();
        expect(screen.getByRole("dialog").getAttribute("aria-modal")).toBe("true");
        expect(screen.getByText("Modify order")).not.toBeNull();
        expect(screen.getByTestId("modify-id").textContent).toBe(String(REQ.clOrdId));
        expect(screen.getByTestId("modify-side").textContent).toBe("BUY");
        expect(screen.getByTestId("modify-qty").textContent).toBe("1,200");
        expect(screen.getByTestId("modify-old").textContent).toBe(formatPrice(1_000_000));
        expect(screen.getByTestId("modify-new").textContent).toBe(formatPrice(1_000_300));
        expect(screen.getByTestId("modify-queue").textContent).toBe(QUEUE_LINE);
        expect(screen.getByTestId("modify-confirm").textContent).toBe("Confirm Modify");
        expect(screen.queryByTestId("modify-marketable")).toBeNull();
    });

    it("warns when the new price is marketable, and still allows Confirm Modify", () => {
        const { onConfirm } = open({ marketable: true });
        expect(screen.getByTestId("modify-marketable").textContent).toBe(MARKETABLE_LINE);
        fireEvent.click(screen.getByTestId("modify-confirm"));
        expect(onConfirm).toHaveBeenCalledTimes(1);
    });

    it("Confirm Modify hands back the request", () => {
        const { onConfirm, onClose } = open();
        fireEvent.click(screen.getByTestId("modify-confirm"));
        expect(onConfirm).toHaveBeenCalledWith(REQ);
        expect(onClose).not.toHaveBeenCalled();
    });

    it("Escape, the exit and an outside click close it without confirming", () => {
        const { onConfirm, onClose } = open();
        fireEvent.keyDown(document, { key: "Escape" });
        fireEvent.click(screen.getByTestId("modify-close"));
        fireEvent.mouseDown(screen.getByTestId("modify-overlay"));
        expect(onClose).toHaveBeenCalledTimes(3);
        expect(onConfirm).not.toHaveBeenCalled();
    });

    it("a click inside the popup is not an outside click", () => {
        const { onClose } = open();
        fireEvent.mouseDown(screen.getByTestId("modify-order"));
        expect(onClose).not.toHaveBeenCalled();
    });

    it("Enter never confirms", () => {
        const { onConfirm } = open();
        fireEvent.keyDown(document, { key: "Enter" });
        fireEvent.keyDown(screen.getByTestId("modify-order"), { key: "Enter" });
        const ev = new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true });
        screen.getByTestId("modify-confirm").dispatchEvent(ev);
        expect(ev.defaultPrevented).toBe(true);
        expect(onConfirm).not.toHaveBeenCalled();
    });

    it("an order that is no longer active disables Confirm Modify", () => {
        const { onConfirm } = open({}, false);
        expect(screen.getByTestId("modify-inactive").textContent).toBe("Order is no longer active");
        const btn = screen.getByTestId("modify-confirm") as HTMLButtonElement;
        expect(btn.disabled).toBe(true);
        fireEvent.click(btn);
        expect(onConfirm).not.toHaveBeenCalled();
    });

    it("renders into document.body, outside its parent", () => {
        const host = document.createElement("div");
        document.body.appendChild(host);
        render(<ModifyOrderPopup request={REQ} active onConfirm={vi.fn()} onClose={vi.fn()} />, { container: host });
        expect(host.querySelector('[data-testid="modify-order"]')).toBeNull();
        expect(document.body.querySelector('[data-testid="modify-order"]')).not.toBeNull();
        host.remove();
    });
});
