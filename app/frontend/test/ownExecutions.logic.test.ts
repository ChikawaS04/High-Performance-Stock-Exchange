import { describe, expect, it } from "vitest";

import { foldExec, initialState, isOwnOrder, OWN_FILLS_CAP, reducer, sessionVwap } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";
import { buildOwnExecutions } from "../src/ownExecutions";
import { newOrderFrame } from "../src/protocol/encode";
import type { ExecFrame, ExecType, OrdType, Side, TimeInForce } from "../src/protocol/messages";
import { BOT_BLOCK } from "../src/participants";

function run(state: AppState, ...actions: Action[]): AppState {
    return actions.reduce(reducer, state);
}

let ts = 1_763_000_000_000_000_000;
function nextTs(): number {
    ts += 1_000_000;
    return ts;
}

function sent(
    clOrdId: number,
    side: Side,
    pricePx: number,
    qty: number,
    opts: { ordType?: OrdType; tif?: TimeInForce; displayQty?: number } = {},
): Action {
    const frame = newOrderFrame(clOrdId, {
        side,
        ordType: opts.ordType ?? "LIMIT",
        tif: opts.tif ?? "GTC",
        pricePx,
        qty,
        displayQty: opts.displayQty ?? 0,
    });
    return { type: "SENT", frame };
}

function exec(execType: ExecType, orderId: number, over: Partial<ExecFrame> = {}): Action {
    const frame: ExecFrame = {
        type: "EXEC",
        execType,
        orderId,
        tradeId: -1,
        price: -1,
        filledQuantity: -1,
        remainingQuantity: -1,
        aggressorOrderId: -1,
        passiveOrderId: -1,
        timestamp: nextTs(),
        ...over,
    };
    return { type: "FRAME", frame };
}

let tradeId = 0;
/** One trade: `agg` hits `pas`. The EXEC names the aggressor, as the engine sends it. */
function trade(agg: number, pas: number, price: number, qty: number, aggRemaining: number): Action {
    tradeId += 1;
    return exec(aggRemaining === 0 ? "ORDER_FILLED" : "ORDER_PARTIALLY_FILLED", agg, {
        tradeId,
        price,
        filledQuantity: qty,
        remainingQuantity: aggRemaining,
        aggressorOrderId: agg,
        passiveOrderId: pas,
        aggressorSide: "BUY",
    });
}

const accepted = (id: number, price: number, remaining: number): Action =>
    exec("ORDER_ACCEPTED", id, { price, remainingQuantity: remaining });

const MINE_A = 1_763_000_000_001;
const MINE_B = 1_763_000_000_002;
const MAKER = BOT_BLOCK + 7;
const TREND = 2 * BOT_BLOCK + 9;
const OTHER_TERMINAL = 1_763_000_000_999; // looks like ours, but this terminal never sent it

describe("isOwnOrder", () => {
    it("is true only for an id in myOrders, never for NA or a lookalike id", () => {
        const s = run(initialState, sent(MINE_A, "BUY", 1_500_000, 10));
        expect(isOwnOrder(s.myOrders, MINE_A)).toBe(true);
        expect(isOwnOrder(s.myOrders, OTHER_TERMINAL)).toBe(false);
        expect(isOwnOrder(s.myOrders, MAKER)).toBe(false);
        expect(isOwnOrder(s.myOrders, -1)).toBe(false);
    });
});

describe("buildOwnExecutions: fills", () => {
    it("records an aggressive own fill with side, price, quantity, time and role", () => {
        const s = run(initialState, sent(MINE_A, "BUY", 1_501_000, 10), trade(MINE_A, MAKER, 1_501_000, 4, 6));
        const { fills } = buildOwnExecutions(s);
        expect(fills).toHaveLength(1);
        expect(fills[0]).toMatchObject({
            clOrdId: MINE_A,
            side: "BUY",
            pricePx: 1_501_000,
            qty: 4,
            role: "aggressive",
            tradeId,
        });
        expect(fills[0].timestamp).toBe(s.lastFrameNanos);
    });

    it("records a passive own fill named by passiveOrderId", () => {
        const s = run(
            initialState,
            sent(MINE_A, "SELL", 1_502_000, 10),
            accepted(MINE_A, 1_502_000, 10),
            trade(TREND, MINE_A, 1_502_000, 3, 0),
        );
        const { fills } = buildOwnExecutions(s);
        expect(fills).toHaveLength(1);
        expect(fills[0]).toMatchObject({ clOrdId: MINE_A, side: "SELL", qty: 3, role: "passive" });
    });

    it("ignores other participants' fills: bot against bot, and a bot against another terminal", () => {
        const s = run(
            initialState,
            sent(MINE_A, "BUY", 1_500_000, 10),
            trade(TREND, MAKER, 1_500_000, 5, 0),
            trade(MAKER, OTHER_TERMINAL, 1_500_000, 2, 0),
        );
        expect(buildOwnExecutions(s).fills).toHaveLength(0);
        expect(s.myOrders[0].exec).toBeUndefined();
    });

    it("stamps the session VWAP after the fill on the fill and on the order", () => {
        const s = run(
            initialState,
            sent(MINE_A, "BUY", 1_510_000, 10),
            trade(TREND, MAKER, 1_500_000, 10, 0),
            trade(MINE_A, MAKER, 1_510_000, 10, 0),
        );
        // (1_500_000 * 10 + 1_510_000 * 10) / 20 = 1_505_000, our own fill included.
        expect(sessionVwap(s)).toBe(1_505_000);
        expect(s.myFills[0].vwap).toBe(1_505_000);
        expect(s.myOrders[0].exec?.lastVwap).toBe(1_505_000);
    });

    it("folds one order's aggressive and passive fills into one exact aggregate", () => {
        // MINE_A takes 6 on entry (aggressive), rests the remaining 4, and is later
        // hit for them (passive): one aggressive and one passive fill on one order.
        const s = run(
            initialState,
            sent(MINE_A, "BUY", 1_500_000, 10),
            trade(MINE_A, TREND, 1_499_900, 6, 4),
            accepted(MINE_A, 1_500_000, 4),
            trade(MAKER, MINE_A, 1_500_000, 4, 0),
        );
        const e = s.myOrders[0].exec;
        expect(e).toBeDefined();
        expect(e?.qty).toBe(10);
        expect(e?.passiveQty).toBe(4);
        expect(e?.aggQty).toBe(6);
        expect(e?.notional).toBe(1_500_000n * 4n + 1_499_900n * 6n);
        expect(e?.aggNotional).toBe(1_499_900n * 6n);
        expect(s.myOrders[0].status).toBe("FILLED");
        expect(buildOwnExecutions(s).fills.map((f) => f.role)).toEqual(["passive", "aggressive"]);
    });

    it("records both sides when both orders of a trade are ours, aggressor first", () => {
        const s = run(
            initialState,
            sent(MINE_A, "SELL", 1_500_000, 5),
            accepted(MINE_A, 1_500_000, 5),
            sent(MINE_B, "BUY", 1_500_000, 5),
            trade(MINE_B, MINE_A, 1_500_000, 5, 0),
        );
        expect(s.myFills.map((f) => [f.clOrdId, f.role])).toEqual([
            [MINE_B, "aggressive"],
            [MINE_A, "passive"],
        ]);
    });

    it("keeps exact sums past Number.MAX_SAFE_INTEGER", () => {
        const big = 99_999_900; // $9,999.99
        const qty = 1_000_000_000;
        const once = foldExec(undefined, big, qty, "aggressive", 1);
        const twice = foldExec(once, big, qty, "passive", 2);
        expect(twice.notional).toBe(2n * BigInt(big) * BigInt(qty));
        expect(twice.notional > BigInt(Number.MAX_SAFE_INTEGER)).toBe(true);
        expect(twice.aggNotional).toBe(BigInt(big) * BigInt(qty));
        expect(twice.lastVwap).toBe(2);
    });

    it("caps the marker list at OWN_FILLS_CAP, oldest dropped, while the aggregate stays exact", () => {
        const n = OWN_FILLS_CAP + 5;
        const actions: Action[] = [sent(MINE_A, "SELL", 1_500_000, n + 10), accepted(MINE_A, 1_500_000, n + 10)];
        for (let i = 0; i < n; i++) actions.push(trade(MAKER + i + 1, MINE_A, 1_500_000, 1, 0));
        const s = run(initialState, ...actions);
        expect(s.myFills).toHaveLength(OWN_FILLS_CAP);
        expect(s.myFills[0].tradeId).toBe(tradeId);
        expect(s.myOrders[0].exec?.qty).toBe(n);
        expect(s.myOrders[0].exec?.passiveQty).toBe(n);
    });

    it("keeps the myFills reference on frames that record no own fill", () => {
        const s = run(initialState, sent(MINE_A, "BUY", 1_500_000, 10), trade(MINE_A, MAKER, 1_500_000, 2, 8));
        const afterAccept = reducer(s, accepted(MINE_A, 1_500_000, 8));
        expect(afterAccept.myFills).toBe(s.myFills);
        const afterBotTrade = reducer(afterAccept, trade(TREND, MAKER, 1_500_000, 1, 0));
        expect(afterBotTrade.myFills).toBe(s.myFills);
        const afterBook = reducer(afterBotTrade, {
            type: "FRAME",
            frame: { type: "BOOK", bestBid: 1_500_000, bestAsk: 1_500_100, bids: [], asks: [], timestamp: nextTs() },
        });
        expect(afterBook.myFills).toBe(s.myFills);
    });
});

describe("buildOwnExecutions: working", () => {
    it("lists resting own orders only, newest first, and leaves PENDING out", () => {
        const s = run(
            initialState,
            sent(MINE_A, "BUY", 1_500_000, 10),
            accepted(MINE_A, 1_500_000, 10),
            sent(MINE_B, "SELL", 1_501_000, 5),
        );
        expect(buildOwnExecutions(s).working.map((w) => w.clOrdId)).toEqual([MINE_A]);
        const both = reducer(s, accepted(MINE_B, 1_501_000, 5));
        expect(buildOwnExecutions(both).working.map((w) => w.clOrdId)).toEqual([MINE_B, MINE_A]);
    });

    it("a partially filled then cancelled order appears in fills and not in working", () => {
        const s = run(
            initialState,
            sent(MINE_A, "BUY", 1_500_000, 10),
            accepted(MINE_A, 1_500_000, 10),
            trade(MAKER, MINE_A, 1_500_000, 3, 0),
        );
        expect(buildOwnExecutions(s).working).toHaveLength(1);
        expect(buildOwnExecutions(s).working[0].remainingQty).toBe(7);
        const cancelled = reducer(s, exec("ORDER_CANCELLED", MINE_A));
        const own = buildOwnExecutions(cancelled);
        expect(own.working).toHaveLength(0);
        expect(own.fills).toHaveLength(1);
        expect(own.fills[0].clOrdId).toBe(MINE_A);
    });

    it("carries an iceberg's displayed quantity and gives a plain limit none", () => {
        const s = run(
            initialState,
            sent(MINE_A, "BUY", 1_500_000, 500, { displayQty: 100 }),
            accepted(MINE_A, 1_500_000, 500),
            sent(MINE_B, "BUY", 1_499_000, 50),
            accepted(MINE_B, 1_499_000, 50),
        );
        const byId = new Map(buildOwnExecutions(s).working.map((w) => [w.clOrdId, w]));
        expect(byId.get(MINE_A)).toMatchObject({ limitPx: 1_500_000, remainingQty: 500, displayQty: 100, ordType: "LIMIT" });
        expect(byId.get(MINE_B)?.displayQty).toBeNull();
    });

    it("gives a midpoint peg a null limit price", () => {
        const s = run(
            initialState,
            sent(MINE_A, "SELL", 0, 20, { ordType: "PEG_MID" }),
            accepted(MINE_A, -1, 20),
        );
        const [w] = buildOwnExecutions(s).working;
        expect(w).toMatchObject({ clOrdId: MINE_A, ordType: "PEG_MID", limitPx: null, side: "SELL" });
    });

    it("never lists another participant's resting order", () => {
        const s = run(
            initialState,
            exec("ORDER_ACCEPTED", MAKER, { price: 1_500_000, remainingQuantity: 10, side: "BUY" }),
            exec("ORDER_ACCEPTED", OTHER_TERMINAL, { price: 1_500_100, remainingQuantity: 10, side: "SELL" }),
        );
        expect(buildOwnExecutions(s).working).toHaveLength(0);
        expect(s.marketOrders).toHaveLength(2);
    });
});
