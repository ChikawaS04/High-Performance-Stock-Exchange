import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";

import { useOrderBook } from "../src/state/useOrderBook";
import { COL_BREAK, HEATMAP_TICK_MS, HEATMAP_TICK_NS, slotFor } from "../src/heatmap/depthRing";

// P20-1: the socket edge feeds the depth recorder. A fake WebSocket stands in for the
// venue; frames go in as wire text, exactly as a live socket delivers them.

class FakeSocket {
    static readonly OPEN = 1;
    static instances: FakeSocket[] = [];
    readyState = 0;
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: string }) => void) | null = null;
    onerror: (() => void) | null = null;
    onclose: (() => void) | null = null;
    constructor(readonly url: string) {
        FakeSocket.instances.push(this);
    }
    send(): void {}
    close(): void {}
    open(): void {
        this.readyState = 1;
        this.onopen?.();
    }
    message(frame: unknown): void {
        this.onmessage?.({ data: JSON.stringify(frame) });
    }
    drop(): void {
        this.readyState = 3;
        this.onclose?.();
    }
}

const B0 = 7_000_000_000;
const PX = 1_500_000;

beforeEach(() => {
    FakeSocket.instances = [];
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval", "Date"] });
    vi.setSystemTime(new Date((B0 * HEATMAP_TICK_NS) / 1_000_000));
    vi.stubGlobal("WebSocket", FakeSocket);
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.useRealTimers();
});

function bookAt(bucket: number, qty: number): unknown {
    return {
        type: "BOOK",
        bestBid: PX - 100,
        bestAsk: PX + 100,
        bids: [[PX - 100, qty]],
        asks: [[PX + 100, 50]],
        lastSeq: 1,
        timestamp: bucket * HEATMAP_TICK_NS + 1_000_000,
    };
}

describe("useOrderBook depth recorder", () => {
    it("records BOOK frames, advances on its timer and breaks on a drop", () => {
        const { result } = renderHook(() => useOrderBook("ws://test"));
        const ws = FakeSocket.instances[FakeSocket.instances.length - 1];
        act(() => {
            ws.open();
            ws.message(bookAt(B0, 100));
        });
        const ring = result.current.depth.ring;
        expect(ring.head).toBe(B0);
        expect(ring.sizes[slotFor(ring, B0) * 40]).toBe(100);

        // Two seconds of silence: the timer fills forward up to now minus one bucket.
        act(() => {
            vi.advanceTimersByTime(2000);
        });
        expect(ring.head).toBe(B0 + 2000 / HEATMAP_TICK_MS - 1);

        act(() => ws.drop());
        expect(ring.flags[slotFor(ring, ring.head)]).toBe(COL_BREAK);
        expect(result.current.depth.getVersion()).toBeGreaterThan(0);
    });

    it("keeps one recorder across re-renders", () => {
        const { result, rerender } = renderHook(() => useOrderBook("ws://test"));
        const first = result.current.depth;
        rerender();
        expect(result.current.depth).toBe(first);
    });
});
