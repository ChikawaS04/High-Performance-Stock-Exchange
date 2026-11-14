import { afterEach, describe, expect, it, vi } from "vitest";

import { installFakeCanvas } from "./fakeCanvas";
import {
    BUBBLE_FILL_ALPHA,
    HEAT_ASK,
    HEAT_BID,
    HEAT_CASING,
    HEAT_CASING_W,
    HEAT_NEUTRAL,
    bubbleMarks,
    drawOverlay,
    hitBubble,
    quotePaths,
} from "../src/heatmap/overlay";
import { BUBBLE_MAX_R, gridFor, rowReadout, xOfNs, yOfPx } from "../src/heatmap/layout";
import type { HeatView } from "../src/heatmap/layout";
import { HEATMAP_TICK_NS, createDepthRing, ingestBook } from "../src/heatmap/depthRing";
import { HEATMAP_TRADE_CAP, initialState, reducer } from "../src/state/reducer";
import type { Action, AppState, TapeEntry } from "../src/state/reducer";
import { parseServerFrame } from "../src/protocol/messages";
import type { ServerFrame } from "../src/protocol/messages";

const T = 100;
const PX = 1_500_000;
const B0 = 7_000_000_000;
const T0 = B0 * HEATMAP_TICK_NS;
const S = 1_000_000_000;
const view: HeatView = { lo: T0, hi: T0 + 60 * S, plotLeft: 10, plotTop: 24, plotW: 600, plotH: 300 };
const grid = gridFor({ lo: PX - 20 * T, hi: PX + 20 * T });

function trade(i: number, over: Partial<TapeEntry> = {}): TapeEntry {
    return {
        tradeId: i,
        pricePx: PX,
        quantity: 100,
        aggressorOrderId: 1,
        passiveOrderId: 2,
        timestamp: T0 + i * S,
        mine: false,
        aggressorSide: "BUY",
        ...over,
    };
}

afterEach(() => {
    vi.restoreAllMocks();
});

describe("bubbles (D5)", () => {
    it("places, sizes and sides each trade in the window", () => {
        const marks = bubbleMarks(
            [trade(1, { quantity: 100 }), trade(2, { aggressorSide: "SELL", quantity: 1_000_000 }), trade(3, { aggressorSide: undefined })],
            view,
            grid,
        );
        expect(marks.map((m) => m.side)).toEqual(["BUY", "SELL", null]);
        expect(marks[0].r).toBe(5);
        expect(marks[1].r).toBe(BUBBLE_MAX_R);
        expect(marks[0].x).toBeCloseTo(xOfNs(view, T0 + S), 6);
        expect(marks[0].y).toBeCloseTo(yOfPx(view, grid, PX), 6);
    });

    it("leaves out trades outside the window or the price range", () => {
        const marks = bubbleMarks([trade(-5), trade(61), trade(4, { pricePx: PX + 100 * T })], view, grid);
        expect(marks).toEqual([]);
    });

    it("hits the newest bubble under the pointer", () => {
        const marks = bubbleMarks([trade(1), trade(1, { tradeId: 9 })], view, grid);
        expect(hitBubble(marks, marks[0].x + 1, marks[0].y)).toBe(1);
        expect(hitBubble(marks, marks[0].x + 50, marks[0].y)).toBe(-1);
    });

    it("outlines by side over the casing, with a translucent fill; unsided is neutral", () => {
        const fake = installFakeCanvas();
        const canvas = document.createElement("canvas");
        const ctx = canvas.getContext("2d")!;
        const marks = bubbleMarks([trade(1), trade(2, { aggressorSide: "SELL" }), trade(3, { aggressorSide: undefined })], view, grid);
        drawOverlay(ctx, view, [], marks);
        const calls = fake.ctxOf(canvas).calls;
        const strokes = calls.filter((c) => c[0] === "set strokeStyle").map((c) => c[1]);
        expect(strokes).toEqual([HEAT_CASING, HEAT_BID, HEAT_CASING, HEAT_ASK, HEAT_CASING, HEAT_NEUTRAL]);
        const fills = calls.filter((c) => c[0] === "set fillStyle").map((c) => c[1]);
        expect(fills[0]).toBe(`rgba(90, 150, 255, ${BUBBLE_FILL_ALPHA})`);
        const arc = calls.find((c) => c[0] === "arc")!;
        expect(arc[1]).toBeCloseTo(marks[0].x - view.plotLeft, 6);
        expect(arc[2]).toBeCloseTo(marks[0].y - view.plotTop, 6);
        expect(calls.filter((c) => c[0] === "set lineWidth").map((c) => c[1]).slice(0, 2)).toEqual([HEAT_CASING_W, 1]);
    });
});

describe("best bid and ask lines", () => {
    it("steps from the quote history and breaks on an empty side", () => {
        const quotes = [
            { t: T0 + 2 * S, bid: PX, ask: PX + T },
            { t: T0 + 10 * S, bid: PX - T, ask: PX + T },
            { t: T0 + 20 * S, bid: -1, ask: -1 },
            { t: T0 + 30 * S, bid: PX, ask: PX + 2 * T },
        ];
        const paths = quotePaths(quotes, view, grid);
        const bids = paths.filter((p) => p.side === "bid");
        const asks = paths.filter((p) => p.side === "ask");
        expect(bids).toHaveLength(2);
        expect(asks).toHaveLength(2);
        const yb = yOfPx(view, grid, PX);
        const yb2 = yOfPx(view, grid, PX - T);
        expect(bids[0].points[0]).toBeCloseTo(xOfNs(view, T0 + 2 * S), 6);
        expect(bids[0].points[1]).toBeCloseTo(yb, 6);
        expect(bids[0].points[5]).toBeCloseTo(yb2, 6);
        expect(bids[1].points[bids[1].points.length - 2]).toBeCloseTo(xOfNs(view, view.hi), 6);
    });

    it("draws each line over its casing", () => {
        const fake = installFakeCanvas();
        const canvas = document.createElement("canvas");
        const ctx = canvas.getContext("2d")!;
        drawOverlay(ctx, view, quotePaths([{ t: T0, bid: PX, ask: PX + T }], view, grid), []);
        const strokes = fake.ctxOf(canvas).calls.filter((c) => c[0] === "set strokeStyle").map((c) => c[1]);
        expect(strokes).toEqual([HEAT_CASING, HEAT_BID, HEAT_CASING, HEAT_ASK]);
    });
});

describe("heatTrades (reducer)", () => {
    const fill = (i: number, side?: "BUY" | "SELL"): Action => ({
        type: "FRAME",
        frame: {
            type: "EXEC",
            execType: "ORDER_FILLED",
            orderId: 900 + i,
            tradeId: i,
            price: PX,
            filledQuantity: 10,
            remainingQuantity: 0,
            aggressorOrderId: 900 + i,
            passiveOrderId: 800 + i,
            timestamp: T0 + i,
            ...(side !== undefined ? { aggressorSide: side } : {}),
        },
    });

    it("keeps every fill oldest first, capped", () => {
        let s: AppState = initialState;
        for (let i = 1; i <= HEATMAP_TRADE_CAP + 5; i++) s = reducer(s, fill(i, "SELL"));
        expect(s.heatTrades).toHaveLength(HEATMAP_TRADE_CAP);
        expect(s.heatTrades[0].tradeId).toBe(6);
        expect(s.heatTrades[s.heatTrades.length - 1].tradeId).toBe(HEATMAP_TRADE_CAP + 5);
        expect(s.heatTrades[0].aggressorSide).toBe("SELL");
    });

    it("keeps its reference on a frame that is not a fill", () => {
        const s = reducer(initialState, fill(1, "BUY"));
        const next = reducer(s, { type: "FRAME", frame: { type: "BOOK", bestBid: -1, bestAsk: -1, bids: [], asks: [], timestamp: T0 + 5 } });
        expect(next.heatTrades).toBe(s.heatTrades);
    });

    it("draws a bubble from raw fill bytes", () => {
        const text = JSON.stringify({
            type: "EXEC",
            execType: "ORDER_PARTIALLY_FILLED",
            orderId: 55,
            tradeId: 7,
            price: PX + T,
            filledQuantity: 400,
            remainingQuantity: 100,
            aggressorOrderId: 55,
            passiveOrderId: 66,
            timestamp: T0 + 5 * S,
            aggressorSide: "SELL",
            seq: 1,
        });
        const frame = parseServerFrame(text) as ServerFrame;
        const s = reducer(initialState, { type: "FRAME", frame });
        const marks = bubbleMarks(s.heatTrades, view, grid);
        expect(marks).toHaveLength(1);
        expect(marks[0]).toMatchObject({ side: "SELL", qty: 400, pricePx: PX + T, r: 10 });
    });
});

describe("readout lookup", () => {
    it("reads the resting size under the pointer", () => {
        const ring = createDepthRing();
        ingestBook(ring, { type: "BOOK", bestBid: PX, bestAsk: PX + T, bids: [[PX, 250]], asks: [[PX + T, 75]], timestamp: T0 + 1 });
        expect(rowReadout(ring, grid, B0, PX)).toEqual({ lo: PX, hi: PX, size: 250 });
        expect(rowReadout(ring, grid, B0, PX + 3 * T)).toEqual({ lo: PX + 3 * T, hi: PX + 3 * T, size: 0 });
        expect(rowReadout(ring, grid, B0 + 9, PX)?.size).toBeNull();
    });
});
