import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";

import { useRepriceTimer } from "../src/state/useRepriceTimer";
import { REPRICE_TIMEOUT_MS } from "../src/reprice";
import type { Reprice } from "../src/reprice";

// P18-5: the reprice timeout at the component edge, on fake timers.

beforeEach(() => {
    vi.useFakeTimers();
});
afterEach(() => {
    cleanup();
    vi.useRealTimers();
});

const WAITING: Reprice = { origClOrdId: 7, side: "BUY", oldPx: 1_000_000, newPx: 999_700, qty: 10, status: "cancelSent" };

function mount(initial: readonly Reprice[]) {
    const onTimeout = vi.fn();
    const hook = renderHook(({ reprices }: { reprices: readonly Reprice[] }) => useRepriceTimer(reprices, onTimeout), {
        initialProps: { reprices: initial },
    });
    return { onTimeout, ...hook };
}

describe("useRepriceTimer (P18-5)", () => {
    it("fires once for an entry still waiting after the timeout", () => {
        const { onTimeout } = mount([WAITING]);
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS - 1));
        expect(onTimeout).not.toHaveBeenCalled();
        act(() => vi.advanceTimersByTime(1));
        expect(onTimeout).toHaveBeenCalledTimes(1);
        expect(onTimeout).toHaveBeenCalledWith(7);
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS * 3));
        expect(onTimeout).toHaveBeenCalledTimes(1);
    });

    it("re-renders with the same waiting entry do not restart its timer", () => {
        const { onTimeout, rerender } = mount([WAITING]);
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS / 2));
        rerender({ reprices: [{ ...WAITING }] });
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS / 2));
        expect(onTimeout).toHaveBeenCalledTimes(1);
    });

    it("clears the timer as soon as the entry stops waiting", () => {
        const { onTimeout, rerender } = mount([WAITING]);
        act(() => vi.advanceTimersByTime(500));
        rerender({ reprices: [{ ...WAITING, status: "ready" }] });
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS * 2));
        rerender({ reprices: [] });
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS * 2));
        expect(onTimeout).not.toHaveBeenCalled();
    });

    it("times each entry on its own, and a later reprice of the same order gets a fresh timer", () => {
        const other: Reprice = { ...WAITING, origClOrdId: 8 };
        const { onTimeout, rerender } = mount([WAITING]);
        act(() => vi.advanceTimersByTime(1500));
        rerender({ reprices: [WAITING, other] });
        act(() => vi.advanceTimersByTime(500));
        expect(onTimeout.mock.calls).toEqual([[7]]);
        rerender({ reprices: [other] });
        act(() => vi.advanceTimersByTime(1500));
        expect(onTimeout.mock.calls).toEqual([[7], [8]]);
        rerender({ reprices: [] });
        rerender({ reprices: [WAITING] });
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS));
        expect(onTimeout.mock.calls).toEqual([[7], [8], [7]]);
    });

    it("clears every timer on unmount", () => {
        const { onTimeout, unmount } = mount([WAITING]);
        unmount();
        act(() => vi.advanceTimersByTime(REPRICE_TIMEOUT_MS * 2));
        expect(onTimeout).not.toHaveBeenCalled();
    });
});
