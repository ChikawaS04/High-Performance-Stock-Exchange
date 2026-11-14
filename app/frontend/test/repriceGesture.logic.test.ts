import { describe, expect, it } from "vitest";

import { isOnTick, snapToTick, TICK_PX } from "../src/prices";
import { dropDrag, isMarketable, moveDrag, startDrag } from "../src/repriceGesture";
import type { DragTarget } from "../src/repriceGesture";
import { TICK_PX as TICKET_TICK_PX } from "../src/components/OrderEntry";

const LIMIT: DragTarget = { clOrdId: 1, side: "BUY", pricePx: 1_000_000, draggable: true };

describe("prices (P18-4)", () => {
    it("owns the one-cent tick, which the ticket re-exports", () => {
        expect(TICK_PX).toBe(100);
        expect(TICKET_TICK_PX).toBe(TICK_PX);
    });

    it("snaps a pointer price to the nearest tick, half a tick rounding up", () => {
        expect(snapToTick(1_000_049)).toBe(1_000_000);
        expect(snapToTick(1_000_050)).toBe(1_000_100);
        expect(snapToTick(1_000_151.7)).toBe(1_000_200);
        expect(snapToTick(1_000_000)).toBe(1_000_000);
    });

    it("never snaps below one tick, and maps a non-finite pointer to one tick", () => {
        expect(snapToTick(10)).toBe(TICK_PX);
        expect(snapToTick(-500)).toBe(TICK_PX);
        expect(snapToTick(Number.NaN)).toBe(TICK_PX);
    });

    it("isOnTick accepts whole positive ticks only", () => {
        expect(isOnTick(1_000_100)).toBe(true);
        expect(isOnTick(1_000_050)).toBe(false);
        expect(isOnTick(0)).toBe(false);
        expect(isOnTick(100.5)).toBe(false);
    });
});

describe("repriceGesture (P18 D4)", () => {
    it("starts at the order's price with the ghost on it", () => {
        expect(startDrag(LIMIT, [])).toEqual({ clOrdId: 1, side: "BUY", oldPx: 1_000_000, ghostPx: 1_000_000 });
    });

    it("refuses a peg and an order with a reprice in flight", () => {
        expect(startDrag({ ...LIMIT, draggable: false }, [])).toBeNull();
        expect(startDrag(LIMIT, [7, 1])).toBeNull();
        expect(startDrag(LIMIT, [7])).not.toBeNull();
    });

    it("moves the ghost to the snapped pointer price, keeping the object when nothing changes", () => {
        const d = startDrag(LIMIT, [])!;
        const moved = moveDrag(d, 1_000_260);
        expect(moved.ghostPx).toBe(1_000_300);
        expect(moved.oldPx).toBe(1_000_000);
        expect(moveDrag(moved, 1_000_280)).toBe(moved);
        expect(isOnTick(moveDrag(moved, 1_234_567.89).ghostPx)).toBe(true);
    });

    it("dropping back on the old price cancels the gesture", () => {
        const d = moveDrag(moveDrag(startDrag(LIMIT, [])!, 1_000_400), 1_000_010);
        expect(dropDrag(d)).toEqual({ kind: "cancel" });
    });

    it("dropping on a new price asks to confirm with the old and new price", () => {
        const d = moveDrag(startDrag(LIMIT, [])!, 999_700);
        expect(dropDrag(d)).toEqual({ kind: "confirm", clOrdId: 1, side: "BUY", oldPx: 1_000_000, newPx: 999_700 });
    });

    it("flags a price that crosses the opposite best as marketable", () => {
        expect(isMarketable("BUY", 1_000_200, 1_000_000, 1_000_200)).toBe(true);
        expect(isMarketable("BUY", 1_000_100, 1_000_000, 1_000_200)).toBe(false);
        expect(isMarketable("SELL", 1_000_000, 1_000_000, 1_000_200)).toBe(true);
        expect(isMarketable("SELL", 1_000_100, 1_000_000, 1_000_200)).toBe(false);
        // An empty opposite side is never crossed.
        expect(isMarketable("BUY", 9_999_900, 1_000_000, -1)).toBe(false);
        expect(isMarketable("SELL", 100, -1, 1_000_200)).toBe(false);
    });
});
