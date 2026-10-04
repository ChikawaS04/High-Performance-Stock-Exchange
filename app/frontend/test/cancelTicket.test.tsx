import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { CancelTicket, validateOrigClOrdId } from "../src/components/CancelTicket";

afterEach(cleanup);

const input = () => screen.getByTestId("cancel-ticket-input") as HTMLInputElement;

describe("validateOrigClOrdId", () => {
    it("accepts a positive whole number, trimming whitespace", () => {
        expect(validateOrigClOrdId("42")).toEqual({ ok: true, origClOrdId: 42 });
        expect(validateOrigClOrdId("  1000  ")).toEqual({ ok: true, origClOrdId: 1000 });
    });

    it("rejects zero, negatives, decimals, and non-numeric ids", () => {
        for (const v of ["0", "-1", "1.5", "abc", "", " ", "1e3", "1,000"]) {
            expect(validateOrigClOrdId(v).ok).toBe(false);
        }
    });
});

describe("<CancelTicket />", () => {
    it("cancels a valid id exactly once, passing it as origClOrdId", () => {
        const onCancel = vi.fn();
        render(<CancelTicket onCancel={onCancel} />);

        fireEvent.change(input(), { target: { value: "42" } });
        fireEvent.click(screen.getByTestId("cancel-ticket-submit"));

        expect(onCancel).toHaveBeenCalledTimes(1);
        expect(onCancel).toHaveBeenCalledWith(42);
    });

    it("does not require the id to match a known order (manual by-id cancel)", () => {
        const onCancel = vi.fn();
        render(<CancelTicket onCancel={onCancel} />);

        fireEvent.change(input(), { target: { value: "999999" } });
        fireEvent.click(screen.getByTestId("cancel-ticket-submit"));

        expect(onCancel).toHaveBeenCalledWith(999999);
    });

    it("blocks an invalid id: shows a reason and does not call onCancel", () => {
        const onCancel = vi.fn();
        render(<CancelTicket onCancel={onCancel} />);

        fireEvent.change(input(), { target: { value: "1.5" } });
        fireEvent.click(screen.getByTestId("cancel-ticket-submit"));

        expect(onCancel).not.toHaveBeenCalled();
        expect(screen.getByTestId("cancel-ticket-error").textContent).toMatch(/Order ID/i);
    });

    it("clears the field after a successful cancel", () => {
        render(<CancelTicket onCancel={vi.fn()} />);

        fireEvent.change(input(), { target: { value: "7" } });
        fireEvent.click(screen.getByTestId("cancel-ticket-submit"));

        expect(input().value).toBe("");
    });

    it("is inert when disabled", () => {
        const onCancel = vi.fn();
        render(<CancelTicket onCancel={onCancel} disabled />);

        expect(input().disabled).toBe(true);
        fireEvent.click(screen.getByTestId("cancel-ticket-submit"));
        expect(onCancel).not.toHaveBeenCalled();
    });

    it("uses plain click handlers, not an HTML form submit", () => {
        const { container } = render(<CancelTicket onCancel={vi.fn()} />);
        expect(container.querySelector("form")).toBeNull();
    });
});

describe("<CancelTicket /> FIX annotation cleanup (P8-6, retheme)", () => {
    /**
     * P8-6 replaced this ticket's FIX tag chrome with plain wording. The slate
     * retheme then removed the VISIBLE "Order ID to cancel" label as redundant: the
     * panel heading directly above the ticket already reads "Cancel by ID", and that
     * heading is rendered by TradingPage, not by this component, so it is out of
     * reach of a CancelTicket-only render.
     *
     * The plain wording therefore survives as the input's accessible name rather
     * than as on-screen text, which is what this asserts. The negative assertions
     * are the actual P8-6 guarantee and are unchanged: no OrigClOrdID, no tag
     * number, anywhere in the ticket. The tag-level view lives in the FIX inspector.
     */
    it("names the field in plain wording and drops the FIX tag chrome", () => {
        render(<CancelTicket onCancel={vi.fn()} />);

        expect(screen.getByLabelText("Order ID to cancel")).toBe(input());
        expect(screen.queryByText("OrigClOrdID")).toBeNull();
        expect(screen.queryByText(/Tag 41/)).toBeNull();
        expect(screen.queryByText(/\bTag\b/)).toBeNull();
    });
});