import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";

import { OrderToasts } from "../src/components/OrderToasts";
import { TOAST_TTL_MS } from "../src/notices";
import type { OrderNotice } from "../src/notices";

// No vitest globals, so RTL's auto-cleanup never registers. Wire it explicitly.
beforeEach(() => {
    vi.useFakeTimers();
});
afterEach(() => {
    cleanup();
    vi.useRealTimers();
});

function notice(id: number, over: Partial<OrderNotice> = {}): OrderNotice {
    return {
        id,
        kind: "filled",
        clOrdId: id,
        title: "Order filled",
        side: "BUY",
        detail: "10 @ 1446.69",
        ...over,
    };
}

function toasts(): HTMLElement[] {
    return screen.queryAllByTestId("order-toast");
}

describe("OrderToasts (D4)", () => {
    it("shows one toast per new notice, as a polite status region", () => {
        const { rerender } = render(<OrderToasts notices={[]} />);
        const region = screen.getByTestId("order-toasts");
        expect(region.getAttribute("role")).toBe("status");
        expect(region.getAttribute("aria-live")).toBe("polite");

        rerender(<OrderToasts notices={[notice(1, { side: "SELL", title: "Order cancelled" })]} />);
        expect(toasts()).toHaveLength(1);
        expect(toasts()[0].textContent).toContain("Order cancelled");
        expect(toasts()[0].textContent).toContain("SELL");
        expect(toasts()[0].querySelector(".order-toast__side--sell")).not.toBeNull();

        rerender(<OrderToasts notices={[notice(1), notice(2)]} />);
        expect(toasts()).toHaveLength(2);
    });

    it("raises nothing for notices already in state at mount", () => {
        const { rerender } = render(<OrderToasts notices={[notice(1), notice(2)]} />);
        expect(toasts()).toHaveLength(0);
        rerender(<OrderToasts notices={[notice(1), notice(2), notice(3)]} />);
        expect(toasts()).toHaveLength(1);
    });

    it("keeps at most four, newest at the bottom", () => {
        const { rerender } = render(<OrderToasts notices={[]} />);
        rerender(<OrderToasts notices={[1, 2, 3, 4, 5].map((i) => notice(i, { detail: `#${i}` }))} />);
        expect(toasts().map((t) => t.querySelector(".order-toast__detail")!.textContent)).toEqual([
            "#2",
            "#3",
            "#4",
            "#5",
        ]);
    });

    it("auto-dismisses after 5 s", () => {
        const { rerender } = render(<OrderToasts notices={[]} />);
        rerender(<OrderToasts notices={[notice(1)]} />);
        act(() => {
            vi.advanceTimersByTime(TOAST_TTL_MS - 1);
        });
        expect(toasts()).toHaveLength(1);
        act(() => {
            vi.advanceTimersByTime(1);
        });
        expect(toasts()).toHaveLength(0);
    });

    it("pauses its timer while hovered and resumes with the time left", () => {
        const { rerender } = render(<OrderToasts notices={[]} />);
        rerender(<OrderToasts notices={[notice(1)]} />);
        act(() => {
            vi.advanceTimersByTime(3000);
        });
        fireEvent.mouseEnter(toasts()[0]);
        act(() => {
            vi.advanceTimersByTime(20_000);
        });
        expect(toasts()).toHaveLength(1);

        fireEvent.mouseLeave(toasts()[0]);
        act(() => {
            vi.advanceTimersByTime(1999);
        });
        expect(toasts()).toHaveLength(1);
        act(() => {
            vi.advanceTimersByTime(1);
        });
        expect(toasts()).toHaveLength(0);
    });

    it("closes on the exit, which never takes focus", () => {
        const { rerender } = render(<OrderToasts notices={[]} />);
        rerender(<OrderToasts notices={[notice(1), notice(2)]} />);
        const close = screen.getAllByTestId("order-toast-close")[0];
        expect(close.getAttribute("tabindex")).toBe("-1");
        fireEvent.click(close);
        expect(toasts()).toHaveLength(1);
        expect(document.activeElement).toBe(document.body);
    });
});
