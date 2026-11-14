import { describe, expect, it } from "vitest";

import {
    fillMarkersForCandles,
    fillMarkersForLine,
    MARKER_HIT_PX,
    MARKER_STACK_MAX,
    MARKER_STACK_PX,
    markerOffset,
    markerReadout,
    nearestPoint,
    orderLabel,
    orderTagYs,
    placeOrderLines,
    venueMid,
} from "../src/chartOverlays";
import type { WorkingOrder } from "../src/ownExecutions";
import { bucketOf } from "../src/bars";
import type { OwnFill } from "../src/state/reducer";
import type { Side } from "../src/protocol/messages";
import { formatPrice, formatQty } from "../src/format";

const S = 1_000_000_000;
const NS = 15 * S;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);
const B0 = bucketOf(T0, NS);

let tradeId = 0;
function own(t: number, side: Side, pricePx: number, qty = 100): OwnFill {
    tradeId += 1;
    return { clOrdId: 1_763_000_000_001, tradeId, side, pricePx, qty, timestamp: t, role: "aggressive", vwap: pricePx };
}
/** myFills order: newest first. */
const newestFirst = (...oldestFirst: OwnFill[]): OwnFill[] => [...oldestFirst].reverse();

describe("fillMarkersForCandles (P18 D2)", () => {
    it("anchors each fill to its bar's slot, bucket minus the first visible bucket", () => {
        const fills = newestFirst(own(T0 + 1 * S, "BUY", 1_000_000), own(T0 + 31 * S, "SELL", 1_000_500));
        const ms = fillMarkersForCandles(fills, B0, B0 + 3, NS);
        expect(ms.map((m) => [m.anchor, m.side, m.pricePx, m.stack, m.count])).toEqual([
            [0, "BUY", 1_000_000, 0, 1],
            [2, "SELL", 1_000_500, 0, 1],
        ]);
    });

    it("leaves out fills whose bar is outside the visible slots", () => {
        const fills = newestFirst(own(T0 - 1 * S, "BUY", 1_000_000), own(T0 + 1 * S, "BUY", 1_000_100), own(T0 + 61 * S, "SELL", 1_000_200));
        const ms = fillMarkersForCandles(fills, B0, B0 + 3, NS);
        expect(ms.map((m) => m.pricePx)).toEqual([1_000_100]);
    });

    it("stacks up to three fills of one side in one bar outward, oldest innermost", () => {
        const fills = newestFirst(
            own(T0 + 1 * S, "BUY", 1_000_000),
            own(T0 + 2 * S, "BUY", 1_000_100),
            own(T0 + 3 * S, "BUY", 1_000_000),
            own(T0 + 4 * S, "SELL", 1_000_300),
        );
        const ms = fillMarkersForCandles(fills, B0, B0, NS);
        const buys = ms.filter((m) => m.side === "BUY");
        expect(buys.map((m) => [m.stack, m.pricePx, m.count])).toEqual([
            [0, 1_000_000, 1],
            [1, 1_000_100, 1],
            [2, 1_000_000, 1],
        ]);
        // The sell is its own group in the same bar, not stacked behind the buys.
        expect(ms.filter((m) => m.side === "SELL").map((m) => m.stack)).toEqual([0]);
    });

    it("past three fills draws one marker with a count at the quantity-weighted average", () => {
        expect(MARKER_STACK_MAX).toBe(3);
        const fills = newestFirst(
            own(T0 + 1 * S, "SELL", 1_000_000, 100),
            own(T0 + 2 * S, "SELL", 1_000_100, 100),
            own(T0 + 3 * S, "SELL", 1_000_200, 100),
            own(T0 + 4 * S, "SELL", 1_000_300, 300),
        );
        const ms = fillMarkersForCandles(fills, B0, B0, NS);
        expect(ms).toHaveLength(1);
        // (1_000_000 + 1_000_100 + 1_000_200) * 100 + 1_000_300 * 300 = 600 shares at 1_000_200 avg.
        expect(ms[0]).toMatchObject({ side: "SELL", count: 4, qty: 600, stack: 0, pricePx: 1_000_200 });
    });

    it("gives every marker a distinct key", () => {
        const fills = newestFirst(own(T0 + 1 * S, "BUY", 1_000_000), own(T0 + 2 * S, "BUY", 1_000_000), own(T0 + 16 * S, "BUY", 1_000_000));
        const keys = fillMarkersForCandles(fills, B0, B0 + 1, NS).map((m) => m.key);
        expect(new Set(keys).size).toBe(keys.length);
    });
});

describe("fillMarkersForLine (P18 D2)", () => {
    it("anchors each fill at its own time inside the window, grouping fills that share a timestamp", () => {
        const t = T0 + 5 * S;
        const fills = newestFirst(own(T0, "BUY", 1_000_000), own(t, "BUY", 1_000_100), own(t, "BUY", 1_000_200), own(T0 + 9 * S, "SELL", 1_000_300));
        const ms = fillMarkersForLine(fills, T0 + 1 * S, T0 + 9 * S);
        expect(ms.map((m) => [m.anchor, m.side, m.stack])).toEqual([
            [t, "BUY", 0],
            [t, "BUY", 1],
            [T0 + 9 * S, "SELL", 0],
        ]);
    });
});

describe("marker geometry and hover", () => {
    it("puts a buy below its price and a sell above it, further out per stack step", () => {
        expect(markerOffset("BUY", 0)).toBeGreaterThan(0);
        expect(markerOffset("SELL", 0)).toBeLessThan(0);
        expect(markerOffset("BUY", 2) - markerOffset("BUY", 0)).toBe(2 * MARKER_STACK_PX);
        expect(markerOffset("SELL", 0) - markerOffset("SELL", 1)).toBe(MARKER_STACK_PX);
    });

    it("nearestPoint picks the closest point within the radius, else -1", () => {
        const pts = [
            { x: 10, y: 10 },
            { x: 14, y: 10 },
        ];
        expect(nearestPoint(pts, 13, 10)).toBe(1);
        expect(nearestPoint(pts, 12, 10)).toBe(0); // a tie goes to the earlier point
        expect(nearestPoint(pts, 10, 10 + MARKER_HIT_PX + 1)).toBe(-1);
        expect(nearestPoint([], 0, 0)).toBe(-1);
    });

    it("reads a single fill as qty @ price and a count marker with its fill count", () => {
        const [single] = fillMarkersForCandles([own(T0 + 1 * S, "BUY", 14_466_600, 200)], B0, B0, NS);
        expect(markerReadout(single, formatPrice, formatQty)).toBe(`${formatQty(200)} @ 1446.66`);
        const four = newestFirst(...[1, 2, 3, 4].map((i) => own(T0 + i * S, "SELL", 14_466_600, 50)));
        const [count] = fillMarkersForCandles(four, B0, B0, NS);
        expect(markerReadout(count, formatPrice, formatQty)).toBe(`${formatQty(200)} @ 1446.66 (4 fills)`);
    });
});

function working(over: Partial<WorkingOrder> & Pick<WorkingOrder, "clOrdId">): WorkingOrder {
    return { side: "BUY", ordType: "LIMIT", limitPx: 1_000_000, remainingQty: 200, displayQty: null, ...over };
}

describe("venueMid (P18 D3)", () => {
    it("is the exact mean of the lit tops, a half cent when the spread is one tick", () => {
        expect(venueMid(1_000_000, 1_000_200)).toBe(1_000_100);
        expect(venueMid(1_000_000, 1_000_100)).toBe(1_000_050);
    });

    it("is null when either side is empty", () => {
        expect(venueMid(-1, 1_000_100)).toBeNull();
        expect(venueMid(1_000_000, -1)).toBeNull();
    });
});

describe("placeOrderLines (P18 D3)", () => {
    it("draws a limit at its price and labels it, iceberg with its displayed size", () => {
        const lines = placeOrderLines(
            [working({ clOrdId: 1 }), working({ clOrdId: 2, side: "SELL", limitPx: 1_000_400, remainingQty: 500, displayQty: 100 })],
            null,
            999_000,
            1_001_000,
        );
        expect(lines.map((l) => [l.clOrdId, l.kind, l.pricePx, l.pin, l.label, l.draggable])).toEqual([
            [1, "limit", 1_000_000, null, "BUY 200 LMT", true],
            [2, "limit", 1_000_400, null, "SELL 100/500 LMT", true],
        ]);
    });

    it("draws a peg at the mid, never draggable, and leaves it out with no mid", () => {
        const peg = working({ clOrdId: 3, ordType: "PEG_MID", limitPx: null, remainingQty: 50 });
        const [line] = placeOrderLines([peg], 1_000_050, 999_000, 1_001_000);
        expect(line).toMatchObject({ kind: "peg", pricePx: 1_000_050, draggable: false, label: "BUY 50 PEG", pin: null });
        expect(placeOrderLines([peg], null, 999_000, 1_001_000)).toHaveLength(0);
    });

    it("pins an order outside the domain to the nearer edge", () => {
        const lines = placeOrderLines(
            [working({ clOrdId: 1, limitPx: 2_000_000 }), working({ clOrdId: 2, limitPx: 500_000 }), working({ clOrdId: 3, limitPx: 1_001_000 })],
            null,
            999_000,
            1_001_000,
        );
        expect(lines.map((l) => l.pin)).toEqual(["top", "bottom", null]);
    });

    it("never shows an iceberg's displayed size above what is left, and groups thousands", () => {
        expect(orderLabel(working({ clOrdId: 1, remainingQty: 60, displayQty: 100 }))).toBe("BUY 60/60 LMT");
        expect(orderLabel(working({ clOrdId: 1, remainingQty: 2_500 }))).toBe("BUY 2,500 LMT");
    });
});

describe("orderTagYs (P18 D3)", () => {
    it("puts an in-domain tag on its line and stacks pinned tags inward from each edge", () => {
        const lines = placeOrderLines(
            [
                working({ clOrdId: 1, limitPx: 2_000_000 }),
                working({ clOrdId: 2, limitPx: 3_000_000 }),
                working({ clOrdId: 3, limitPx: 500_000 }),
                working({ clOrdId: 4, limitPx: 1_000_000 }),
            ],
            null,
            999_000,
            1_001_000,
        );
        const ys = orderTagYs(lines, () => 123, 20, 220, 14);
        expect(ys).toEqual([27, 41, 213, 123]);
    });
});
