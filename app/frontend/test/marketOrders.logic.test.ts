import { describe, expect, it } from "vitest";

import { MARKET_ORDERS_CAP, applyMarketExec, initialState, reducer } from "../src/state/reducer";
import type { Action, AppState, MarketOrder } from "../src/state/reducer";
import { newOrderFrame } from "../src/protocol/encode";
import type { ExecFrame, ExecType, Side } from "../src/protocol/messages";

const MAKER_BID = 100_000_000_000_001;
const TREND_BUY = 200_000_000_000_001;
const PX = 1_500_000;
const notMine = (): boolean => false;

function exec(execType: ExecType, fields: Partial<ExecFrame>): ExecFrame {
    return {
        type: "EXEC",
        execType,
        orderId: -1,
        tradeId: -1,
        price: -1,
        filledQuantity: -1,
        remainingQuantity: -1,
        aggressorOrderId: -1,
        passiveOrderId: -1,
        timestamp: 1,
        ...fields,
    };
}

const accepted = (id: number, side: Side | undefined, price: number, remaining: number, ts = 1): ExecFrame =>
    exec("ORDER_ACCEPTED", { orderId: id, price, remainingQuantity: remaining, timestamp: ts, ...(side ? { side } : {}) });

function fill(agg: number, pas: number, aggSide: Side | undefined, qty: number, aggRemaining: number): ExecFrame {
    return exec(aggRemaining === 0 ? "ORDER_FILLED" : "ORDER_PARTIALLY_FILLED", {
        orderId: agg,
        tradeId: 7,
        price: PX,
        filledQuantity: qty,
        remainingQuantity: aggRemaining,
        aggressorOrderId: agg,
        passiveOrderId: pas,
        ...(aggSide ? { aggressorSide: aggSide } : {}),
    });
}

function fold(...frames: ExecFrame[]): readonly MarketOrder[] {
    return frames.reduce<readonly MarketOrder[]>((o, f) => applyMarketExec(o, f, notMine), []);
}

describe("applyMarketExec (pure)", () => {
    it("an ACCEPTED with a side opens a GTC row at its limit", () => {
        const [row] = fold(accepted(MAKER_BID, "BUY", PX, 100, 42));
        expect(row).toEqual({
            clOrdId: MAKER_BID,
            side: "BUY",
            pricePx: PX,
            originalQty: 100,
            remainingQty: 100,
            status: "OPEN",
            ordType: "LIMIT",
            tif: "GTC",
            firstSeenNanos: 42,
        });
    });

    it("an ACCEPTED with no price rested as a midpoint peg", () => {
        expect(fold(accepted(MAKER_BID, "SELL", -1, 10))[0].ordType).toBe("PEG_MID");
    });

    it("passive fills decrement the resting row to FILLED", () => {
        const orders = fold(
            accepted(MAKER_BID, "BUY", PX, 100),
            fill(9, MAKER_BID, "SELL", 40, 0),
            fill(8, MAKER_BID, "SELL", 60, 0),
        );
        const row = orders.find((o) => o.clOrdId === MAKER_BID);
        expect(row?.remainingQty).toBe(0);
        expect(row?.status).toBe("FILLED");
        expect(row?.originalQty).toBe(100);
    });

    it("an aggressor seen only through fills gets a row from aggressorSide with an unknown limit", () => {
        const orders = fold(fill(TREND_BUY, 9, "BUY", 30, 20), exec("ORDER_EXPIRED", { orderId: TREND_BUY, remainingQuantity: 20 }));
        expect(orders).toHaveLength(1);
        expect(orders[0]).toMatchObject({
            side: "BUY",
            pricePx: -1,
            originalQty: 50,
            remainingQty: 20,
            status: "EXPIRED",
            tif: null,
        });
    });

    it("a GTC that trades on arrival then rests keeps its fills and takes the limit from the ACCEPTED", () => {
        const orders = fold(fill(TREND_BUY, 9, "BUY", 30, 70), accepted(TREND_BUY, "BUY", PX + 100, 70));
        expect(orders[0]).toMatchObject({ pricePx: PX + 100, originalQty: 100, remainingQty: 70, status: "PARTIALLY_FILLED", tif: "GTC" });
    });

    it("a CANCELLED closes the row and a later report never reopens it", () => {
        const orders = fold(
            accepted(MAKER_BID, "SELL", PX, 10),
            exec("ORDER_CANCELLED", { orderId: MAKER_BID }),
            fill(9, MAKER_BID, "BUY", 5, 0),
        );
        const row = orders.find((o) => o.clOrdId === MAKER_BID);
        expect(row?.status).toBe("CANCELLED");
        expect(row?.remainingQty).toBe(10);
    });

    it("orders with no side on the wire, unknown ids and rejects add nothing and keep the reference", () => {
        const start = fold(accepted(MAKER_BID, "BUY", PX, 10));
        expect(applyMarketExec(start, accepted(5, undefined, PX, 10), notMine)).toBe(start);
        expect(applyMarketExec(start, exec("ORDER_CANCELLED", { orderId: 77 }), notMine)).toBe(start);
        expect(applyMarketExec(start, exec("ORDER_REJECTED", { orderId: MAKER_BID }), notMine)).toBe(start);
        expect(applyMarketExec(start, fill(8, 9, undefined, 5, 0), notMine)).toBe(start);
    });

    it("this terminal's own ids never get a row", () => {
        const mine = (id: number): boolean => id === MAKER_BID;
        expect(applyMarketExec([], accepted(MAKER_BID, "BUY", PX, 10), mine)).toEqual([]);
    });

    it("is newest first and capped", () => {
        let orders: readonly MarketOrder[] = [];
        for (let i = 1; i <= MARKET_ORDERS_CAP + 5; i++) {
            orders = applyMarketExec(orders, accepted(MAKER_BID + i, "BUY", PX, 1), notMine);
        }
        expect(orders).toHaveLength(MARKET_ORDERS_CAP);
        expect(orders[0].clOrdId).toBe(MAKER_BID + MARKET_ORDERS_CAP + 5);
    });
});

describe("reducer: market orders and tape side", () => {
    const frame = (f: ExecFrame): Action => ({ type: "FRAME", frame: f });

    it("every print takes its side from the frame", () => {
        const s = [frame(accepted(MAKER_BID, "SELL", PX, 50)), frame(fill(TREND_BUY, MAKER_BID, "BUY", 20, 0))]
            .reduce(reducer, initialState);
        expect(s.tape[0].aggressorSide).toBe("BUY");
        expect(s.tape[0].mine).toBe(false);
        expect(s.marketOrders.map((o) => o.clOrdId).sort()).toEqual([MAKER_BID, TREND_BUY].sort());
        expect(s.marketOrders.find((o) => o.clOrdId === MAKER_BID)?.remainingQty).toBe(30);
    });

    it("this terminal's orders stay in myOrders only", () => {
        const sent: Action = {
            type: "SENT",
            frame: newOrderFrame(1_763_000_000_000, { side: "BUY", ordType: "LIMIT", tif: "GTC", pricePx: PX, qty: 10, displayQty: 0 }),
        };
        const s: AppState = [sent, frame(accepted(1_763_000_000_000, "BUY", PX, 10))].reduce(reducer, initialState);
        expect(s.myOrders[0].status).toBe("OPEN");
        expect(s.marketOrders).toEqual([]);
    });
});
