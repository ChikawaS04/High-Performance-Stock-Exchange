/**
 * The pure state core. No React, no socket, no side effects.
 *
 * Two rules dominate, both inherited from Phase 4:
 *
 *  1. BOOK is authoritative book state and replaces bids/asks/tops wholesale.
 *     EXEC never touches the book. The two streams are independent Disruptor
 *     consumers with separate sequence counters and interleave unpredictably,
 *     so book state can never be inferred from EXEC arrival order.
 *
 *  2. A passive resting order that is hit receives no EXEC of its OWN, but the
 *     aggressor's fill EXEC names it via passiveOrderId. The engine fires one
 *     onFill per trade, reporting the aggressor's orderId and remainingQuantity;
 *     the passive order's remaining is never reported and is decremented
 *     client-side (P7-8, see passiveFill) by each matching fill's per-event
 *     filledQuantity. EXEC stays authoritative for the aggressor; the passive
 *     decrement is a documented client-side derivation, not a server value. A
 *     per-passive EXEC would be a server change and is out of scope.
 *
 * Ordering WITHIN the EXEC stream is reliable (one ring, one sequence), so a
 * PARTIALLY_FILLED followed by its trailing ACCEPTED can be trusted.
 *
 * P7-3 adds the session state the header renders: sessionVolume, sessionOpenPx,
 * a client-assigned msgSeqNum counter, and lastFrameNanos. The two aggregates are
 * accumulated here rather than folded from the tape, which only holds the newest
 * TAPE_CAP fills.
 *
 * P7-8 adds the passive-fill decrement above and a client-assigned send time on
 * each order row (sentAtNanos), captured at dispatch and rendered by the blotter.
 *
 * P7-9 adds inspectorLog: a capped, newest-first merge of the P7-2 raw inbound
 * FIX echoes and the outbound EXEC frames, in true arrival order. This is the
 * one place both streams are visible together (applyExec runs for every EXEC
 * type, not just fills, and the FIX branch is the only other frame path), so
 * storage lives here rather than in a component ref. BOOK frames are not logged.
 *
 * P13-1 adds three more session accumulators for the Price Chart side panel:
 * sessionHighPx, sessionLowPx and sessionTradeCount. They sit beside
 * sessionVolume and sessionOpenPx in the same isFill guard, and they exist here
 * for the same reason those two do: the tape is capped at TAPE_CAP, so a high or a
 * low folded from it would silently mean "high of the last TAPE_CAP prints" and
 * would drift as old prints age out. P13 D1 deferred the notional accumulator and
 * VWAP to the indicator phase; P17-3 adds them (below).
 *
 * P14-6 adds the EXPIRED terminal status (the IOC/FOK outcome, SRS §3.4) and the
 * three order-attribute fields captured at send time (ordType, tif, displayQty).
 * ORDER_EXPIRED is not a fill, so it moves none of the tape or session
 * accumulators; it only transitions the named row to its terminal state.
 *
 * UI polish D4 adds order notifications. applyExec hands the rows before and after
 * each EXEC to the pure noticesForExec (notices.ts), which sees the frame's true
 * pre-state (ownership, side, price) even when React batches several frames into
 * one render. Each notice gets a client-assigned increasing id (noticeSeq) and is
 * kept in a short capped list, orderNotices, which OrderToasts reads. A frame whose
 * venue seq was already seen raises no second notice; the recent seqs are a
 * bounded list rather than a high-water mark, so a venue restart that resets its
 * counter does not silence notices.
 *
 * P17-3 adds the candle data and the session VWAP, in the same isFill guard:
 * sessionNotional (sum of price * qty, a bigint because a long session passes
 * Number.MAX_SAFE_INTEGER, P17 D4) and bars, four clock-aligned OHLCV series (5s,
 * 15s, 1m, 5m) folded by the pure bars module. Both exist for the same reason the
 * other accumulators do: the tape is capped. A frame that is not a fill keeps the
 * bars reference. Nothing serializes AppState (P17-0 F4), so a bigint here is safe.
 *
 * P18-1 adds this terminal's own executions, for the chart overlays and the
 * Executions tab. isOwnOrder is the one ownership test. Every fill that moves one of
 * our rows (aggressor or passive) appends an OwnFill to myFills (capped, newest
 * first, for the markers) and folds into that row's exact ExecAggregate (uncapped,
 * for execution quality). Both carry the session VWAP after the fill.
 */

import { isFill } from "../protocol/messages";
import { noticesForExec } from "../notices";
import { emptyBars, foldBars, vwapUnits } from "../bars";
import type { BarSet } from "../bars";
import type { OrderNotice } from "../notices";
import type {
    ClientFrame,
    ExecFrame,
    FixFrame,
    Level,
    OrdType,
    ServerFrame,
    Side,
    TimeInForce,
} from "../protocol/messages";

/** Newest-first fill history cap. */
export const TAPE_CAP = 200;

/**
 * Newest-first inspector log cap (P7-9). Larger than TAPE_CAP because it merges
 * two streams (every FIX echo and every EXEC frame, not fills only).
 */
export const INSPECTOR_CAP = 500;

/** Most recent order notices kept in state (oldest first); the toast stack shows fewer. */
export const NOTICE_CAP = 32;

/** How many recent EXEC seqs are remembered to show each report's notice once. */
export const SEQ_MEMORY = 256;

/**
 * Newest-first cap on other participants' orders (marketOrders). The bots send
 * several orders a second, so the slice is bounded the way the tape is.
 */
export const MARKET_ORDERS_CAP = 500;

/**
 * Newest-first cap on this terminal's own fill records (P18-1), which the chart's
 * fill markers read. Execution quality never reads this list: each order's exact
 * aggregates live on its row (MyOrder.exec), so the cap cannot drift a metric.
 */
export const OWN_FILLS_CAP = 1000;

/**
 * Sentinel for "no traded price yet this session": no trade has printed, so there
 * is no open, no high and no low. Exported since P13-1 so the three price-valued
 * session accumulators and their tests name one constant rather than repeating a
 * bare -1. Negative by design: formatPrice renders any negative value as the
 * EMPTY_PRICE dash, so an unset accumulator formats correctly with no call-site
 * branch, and "<= 0" reads as "still unset" on every comparison below.
 */
export const SESSION_OPEN_UNSET = -1;

export type ConnectionStatus = "connecting" | "open" | "reconnecting";

export type OrderStatus =
    | "PENDING"
    | "OPEN"
    | "PARTIALLY_FILLED"
    | "FILLED"
    | "CANCELLED"
    | "REJECTED"
    | "EXPIRED";

const TERMINAL: readonly OrderStatus[] = ["FILLED", "CANCELLED", "REJECTED", "EXPIRED"];

export function isTerminal(status: OrderStatus): boolean {
    return TERMINAL.includes(status);
}

/** Cancellable rows, used by the P5-4 OpenOrders panel. */
export function isCancellable(status: OrderStatus): boolean {
    return status === "OPEN" || status === "PARTIALLY_FILLED";
}

export interface BookState {
    readonly bestBid: number;
    readonly bestAsk: number;
    readonly bids: readonly Level[];
    readonly asks: readonly Level[];
    readonly timestamp: number;
}

export interface TapeEntry {
    readonly tradeId: number;
    readonly pricePx: number;
    readonly quantity: number;
    readonly aggressorOrderId: number;
    readonly passiveOrderId: number;
    readonly timestamp: number;
    /** True when either side of the trade is one of this client's orders. */
    readonly mine: boolean;
    /**
     * Aggressor side. Read from the fill frame's aggressorSide, which the server
     * sends on every fill, so every print carries BUY or SELL. A frame without it
     * (an older server, a test fixture) falls back to the P7-6 derivation from our
     * own orders, and an anonymous print without it stays undefined.
     */
    readonly aggressorSide?: Side;
}

/**
 * A locally originated order. Side, price, and the P14-6 order attributes
 * (ordType, tif, displayQty) are captured at SEND time: no EXEC frame carries
 * any of them, so they cannot come from the wire. Status and remaining quantity
 * come only from EXEC (plus the P7-8 passive decrement).
 */
export interface MyOrder {
    readonly clOrdId: number;
    readonly side: Side;
    readonly pricePx: number;
    readonly originalQty: number;
    readonly remainingQty: number;
    readonly status: OrderStatus;
    /**
     * Order type (P14-6). "LIMIT" for every order the ticket sends today; P14-10
     * adds "PEG_MID". Captured at send time, never a server value.
     */
    readonly ordType: OrdType;
    /** Time in force (P14-6), captured at send time. GTC / IOC / FOK. */
    readonly tif: TimeInForce;
    /**
     * Iceberg display quantity (P14-6), captured at send time. 0 for a
     * non-iceberg order; P14-8 lets the ticket set it.
     */
    readonly displayQty: number;
    /**
     * Client-assigned wall-clock send time in epoch nanoseconds (P7-8). Captured at
     * dispatch by useOrderBook (Date.now lifted into the P7-1 epoch-nanos domain),
     * never a server value. Absent on rows built without one (test fixtures); the
     * blotter renders such a row's send time as EMPTY_PRICE.
     */
    readonly sentAtNanos?: number;
    /**
     * Exact running aggregates of this order's own fills (P18-1), folded in the
     * reducer from every fill that names it, aggressor or passive. Absent until the
     * first fill. Uncapped by construction (one per order), so execution quality
     * reads exact values however long the session runs.
     */
    readonly exec?: ExecAggregate;
}

/** Which side of a trade an own fill was on (P18-1). */
export type FillRole = "aggressive" | "passive";

/**
 * Running totals of one own order's fills (P18-1). Notionals are bigint (price
 * units times shares) for the same reason sessionNotional is (P17 D4).
 */
export interface ExecAggregate {
    /** Shares filled. */
    readonly qty: number;
    /** Sum of price * qty over every fill. */
    readonly notional: bigint;
    /** Shares filled while resting (passive). */
    readonly passiveQty: number;
    /** Shares filled as the aggressor, and their notional. */
    readonly aggQty: number;
    readonly aggNotional: bigint;
    /** Session VWAP after this order's last fill, in units (the fill included). */
    readonly lastVwap: number;
}

/**
 * One own fill (P18-1), newest first in AppState.myFills. `vwap` is the session
 * VWAP after this fill, which is what the chart's VWAP line shows at that instant.
 */
export interface OwnFill {
    readonly clOrdId: number;
    readonly tradeId: number;
    readonly side: Side;
    readonly pricePx: number;
    readonly qty: number;
    readonly timestamp: number;
    readonly role: FillRole;
    readonly vwap: number;
}

/**
 * Another participant's order (a bot's, or another terminal's), built only from
 * the EXEC stream, which every connection receives. Nothing here was sent by this
 * terminal, so every field is what the wire stated:
 *
 *  - side: the ACCEPTED frame's side, or a fill's aggressorSide.
 *  - pricePx: the limit price from ORDER_ACCEPTED; -1 (rendered as a dash) for an
 *    order seen only as an aggressor, whose limit the wire never states.
 *  - ordType: PEG_MID when it rested with no limit price, LIMIT otherwise. An
 *    iceberg's display size is not on the wire, so it shows as LIMIT.
 *  - tif: GTC once it has rested (only GTC rests); null while unknown.
 *  - firstSeenNanos: venue time of the first report about it, the row's time.
 *
 * Only orders first seen through ORDER_ACCEPTED or an aggressor fill get a row: an
 * order that rested before this tab connected, or a rejected or expired order with
 * no earlier report, has no side on the wire and is left out.
 */
export interface MarketOrder {
    readonly clOrdId: number;
    readonly side: Side;
    readonly pricePx: number;
    readonly originalQty: number;
    readonly remainingQty: number;
    readonly status: OrderStatus;
    readonly ordType: OrdType;
    readonly tif: TimeInForce | null;
    readonly firstSeenNanos: number;
}

/**
 * One P7-9 inspector entry: either a raw inbound FIX echo or an outbound EXEC
 * notification. Both already carry `type` and `timestamp`, so no wrapper is
 * needed; the discriminant on `type` narrows exactly like ServerFrame.
 */
export type InspectorEntry = FixFrame | ExecFrame;

export interface AppState {
    readonly connection: ConnectionStatus;
    readonly book: BookState;
    readonly tape: readonly TapeEntry[];
    /** Newest first. Keyed by clOrdId, which the server echoes as EXEC orderId. */
    readonly myOrders: readonly MyOrder[];
    /**
     * This terminal's own fills (P18-1), newest first, capped at OWN_FILLS_CAP. One
     * entry per own side of a fill, aggressor or passive, decided by the same
     * ownership test as myOrders (isOwnOrder). Keeps its reference on every frame
     * that records no own fill.
     */
    readonly myFills: readonly OwnFill[];
    /**
     * Every other participant's order seen on the EXEC stream, newest first,
     * capped at MARKET_ORDERS_CAP. Never holds an order that is in myOrders.
     */
    readonly marketOrders: readonly MarketOrder[];
    /**
     * Total quantity traded this session. Accumulated per fill EXEC. One onFill per
     * trade, aggressor-only, filledQuantity per-event (P7-0/Q7-2), so summing across
     * fills cannot double count. Held here, never folded from the capped tape.
     */
    readonly sessionVolume: number;
    /**
     * The session's first trade price in units. Set once by the first fill EXEC and
     * never overwritten; SESSION_OPEN_UNSET until then. Session change is measured
     * against this, never a previous close.
     */
    readonly sessionOpenPx: number;
    /**
     * Highest and lowest traded price this session in units, accumulated per fill.
     * SESSION_OPEN_UNSET until the first fill, which seeds both to its own price.
     * Held here, never folded from the capped tape, for the same reason
     * sessionVolume is: the tape forgets.
     */
    readonly sessionHighPx: number;
    readonly sessionLowPx: number;
    /**
     * Count of trade prints this session. One onFill per trade, aggressor-only
     * (P7-0/Q7-2), so incrementing once per fill EXEC counts trades, not sides. A
     * partial fill is a print like any other and counts once. Zero is a real value,
     * never a missing one.
     */
    readonly sessionTradeCount: number;
    /**
     * Sum of price * quantity over every fill this session, in units times shares
     * (P17-3). A bigint: at $0.0001 units one 10,000-share print of a $1,446 stock
     * adds about 1.4e11, so a long bot session passes Number.MAX_SAFE_INTEGER.
     * Session VWAP is sessionNotional / sessionVolume (sessionVwap below).
     */
    readonly sessionNotional: bigint;
    /**
     * Candle bars at the four sizes (P17-3, P17 D2), folded from every fill and
     * independent of TAPE_CAP. Bars start when the tab connects (no backfill).
     */
    readonly bars: BarSet;
    /**
     * Client-assigned outbound message counter. Incremented once per SENT frame,
     * NEW and CANCEL alike. NOT the FIX 34= MsgSeqNum (the produced subset carries
     * no tag 34, P7-0/Q7-4) and NOT the P7-2 server per-channel echo seqNum. It is
     * labelled client-assigned wherever shown.
     */
    readonly msgSeqNum: number;
    /**
     * Epoch-nanos timestamp of the most recent frame received from the server, of
     * any type (BOOK, EXEC, or the P7-2 FIX echo). A pure liveness marker for the
     * session header; 0 until the first frame arrives. The epoch domain (P7-1) is
     * what makes wall-clock rendering possible.
     */
    readonly lastFrameNanos: number;
    /**
     * FIX/EXEC inspector log (P7-9), newest first, capped at INSPECTOR_CAP. Holds
     * both streams in true arrival order: no timestamp sort, no inference from the
     * carried-constraint-1 independent sequence counters. BOOK frames are not
     * logged; the inspector's filter set is FIX NEW, FIX CANCEL, and EXEC only.
     */
    readonly inspectorLog: readonly InspectorEntry[];
    /**
     * Order notifications for this terminal's own orders (UI polish D4), oldest
     * first, capped at NOTICE_CAP. Raised only by live EXEC frames.
     */
    readonly orderNotices: readonly OrderNotice[];
    /** Id of the last notice raised; 0 before the first. */
    readonly noticeSeq: number;
    /** Recently seen EXEC venue seqs (at most SEQ_MEMORY), for notice dedupe. */
    readonly recentExecSeqs: readonly number[];
}

export const EMPTY_BOOK: BookState = {
    bestBid: -1,
    bestAsk: -1,
    bids: [],
    asks: [],
    timestamp: 0,
};

export const initialState: AppState = {
    connection: "connecting",
    book: EMPTY_BOOK,
    tape: [],
    myOrders: [],
    myFills: [],
    marketOrders: [],
    sessionVolume: 0,
    sessionOpenPx: SESSION_OPEN_UNSET,
    sessionHighPx: SESSION_OPEN_UNSET,
    sessionLowPx: SESSION_OPEN_UNSET,
    sessionTradeCount: 0,
    sessionNotional: 0n,
    bars: emptyBars(),
    msgSeqNum: 0,
    lastFrameNanos: 0,
    inspectorLog: [],
    orderNotices: [],
    noticeSeq: 0,
    recentExecSeqs: [],
};

/**
 * Session VWAP in integer units, rounded half up (bars.vwapUnits); -1, the
 * SESSION_OPEN_UNSET dash, before the first fill. A statistic, never an order price.
 */
export function sessionVwap(state: Pick<AppState, "sessionNotional" | "sessionVolume">): number {
    return vwapUnits(state.sessionNotional, state.sessionVolume);
}

export type Action =
    | { readonly type: "CONNECTION"; readonly status: ConnectionStatus }
    | { readonly type: "FRAME"; readonly frame: ServerFrame }
    | { readonly type: "SENT"; readonly frame: ClientFrame; readonly sentAtNanos?: number };

/**
 * Aggressor side for a fill, at the only fidelity the wire supports (P7-6).
 *
 * No EXEC frame carries a side, so the aggressor's side is knowable only when one
 * of this client's own orders is in the trade: if we are the aggressor it is our
 * order's side; if we are the passive side it is the opposite, since a trade
 * always crosses a buy against a sell. Foreign-vs-foreign trades return undefined.
 * The derivable set is exactly the `mine` set, so a side tag appears precisely on
 * the own-trade rows and nowhere else. Pure and exported for direct unit testing.
 */
export function aggressorSideFor(
    myOrders: readonly MyOrder[],
    aggressorOrderId: number,
    passiveOrderId: number,
): Side | undefined {
    const sideOf = (id: number): Side | undefined =>
        id > 0 ? myOrders.find((o) => o.clOrdId === id)?.side : undefined;

    const aggressor = sideOf(aggressorOrderId);
    if (aggressor !== undefined) return aggressor;

    const passive = sideOf(passiveOrderId);
    if (passive !== undefined) return passive === "BUY" ? "SELL" : "BUY";

    return undefined;
}

/**
 * The one ownership test (P18 D1): an id is this terminal's when it names a row in
 * myOrders, the orders this terminal sent. Never decided from the id's range: the
 * bots' blocks are known (participants.ts), but another terminal's ids look exactly
 * like ours. The NA sentinel (-1) and any non-positive id are never ours.
 */
export function isOwnOrder(myOrders: readonly MyOrder[], id: number): boolean {
    return id > 0 && myOrders.some((o) => o.clOrdId === id);
}

/**
 * Fold one own fill into an order's running aggregates (P18-1). Pure. Prices and
 * quantities are integers on the wire, so the BigInt products are exact.
 */
export function foldExec(exec: ExecAggregate | undefined, pricePx: number, qty: number, role: FillRole, vwap: number): ExecAggregate {
    const notional = BigInt(pricePx) * BigInt(qty);
    const base: ExecAggregate = exec ?? { qty: 0, notional: 0n, passiveQty: 0, aggQty: 0, aggNotional: 0n, lastVwap: vwap };
    return {
        qty: base.qty + qty,
        notional: base.notional + notional,
        passiveQty: base.passiveQty + (role === "passive" ? qty : 0),
        aggQty: base.aggQty + (role === "aggressive" ? qty : 0),
        aggNotional: base.aggNotional + (role === "aggressive" ? notional : 0n),
        lastVwap: vwap,
    };
}

/** A market order's status after a fill leaves it `remainingQty` to go. */
function filledStatus(remainingQty: number): OrderStatus {
    return remainingQty === 0 ? "FILLED" : "PARTIALLY_FILLED";
}

/** Replace the row for `id` with fn(row); null when there is no such row. */
function updateMarketOrder(
    orders: readonly MarketOrder[],
    id: number,
    fn: (o: MarketOrder) => MarketOrder,
): readonly MarketOrder[] | null {
    const i = orders.findIndex((o) => o.clOrdId === id);
    if (i === -1) return null;
    const next = fn(orders[i]);
    if (next === orders[i]) return orders;
    return orders.map((o, j) => (j === i ? next : o));
}

function addMarketOrder(orders: readonly MarketOrder[], order: MarketOrder): readonly MarketOrder[] {
    return [order, ...orders].slice(0, MARKET_ORDERS_CAP);
}

/**
 * Fold one EXEC into the other participants' orders (see MarketOrder). Pure and
 * exported for direct unit testing. `isMine` names this terminal's own ids, which
 * never get a row here. Returns the same array when nothing changes, so a no-op
 * frame keeps the slice reference.
 */
export function applyMarketExec(
    orders: readonly MarketOrder[],
    frame: ExecFrame,
    isMine: (id: number) => boolean,
): readonly MarketOrder[] {
    if (isFill(frame)) {
        let out = orders;
        const agg = frame.aggressorOrderId;
        if (agg > 0 && !isMine(agg)) {
            const remainingQty = frame.remainingQuantity;
            const updated = updateMarketOrder(out, agg, (o) => {
                const filled = o.originalQty - o.remainingQty + frame.filledQuantity;
                return { ...o, remainingQty, originalQty: filled + remainingQty, status: filledStatus(remainingQty) };
            });
            if (updated !== null) {
                out = updated;
            } else if (frame.aggressorSide !== undefined) {
                out = addMarketOrder(out, {
                    clOrdId: agg,
                    side: frame.aggressorSide,
                    pricePx: -1,
                    originalQty: frame.filledQuantity + remainingQty,
                    remainingQty,
                    status: filledStatus(remainingQty),
                    ordType: "LIMIT",
                    tif: null,
                    firstSeenNanos: frame.timestamp,
                });
            }
        }
        // Passive path: the resting order gets no EXEC of its own, so decrement it here,
        // as passiveFill does for our own orders. A terminal row is never resurrected.
        const pas = frame.passiveOrderId;
        if (pas > 0 && pas !== agg && !isMine(pas)) {
            const updated = updateMarketOrder(out, pas, (o) => {
                if (isTerminal(o.status)) return o;
                const remainingQty = Math.max(0, o.remainingQty - frame.filledQuantity);
                return { ...o, remainingQty, status: filledStatus(remainingQty) };
            });
            if (updated !== null) out = updated;
        }
        return out;
    }

    const id = frame.orderId;
    if (id <= 0 || isMine(id)) return orders;
    switch (frame.execType) {
        case "ORDER_ACCEPTED": {
            const ordType: OrdType = frame.price < 0 ? "PEG_MID" : "LIMIT";
            const updated = updateMarketOrder(orders, id, (o) => {
                // A trailing ACCEPTED after fills reports the rested remainder.
                const filled = o.originalQty - o.remainingQty;
                return {
                    ...o,
                    side: frame.side ?? o.side,
                    pricePx: frame.price,
                    remainingQty: frame.remainingQuantity,
                    originalQty: filled + frame.remainingQuantity,
                    status: filled > 0 ? "PARTIALLY_FILLED" : "OPEN",
                    ordType,
                    tif: "GTC",
                };
            });
            if (updated !== null) return updated;
            if (frame.side === undefined) return orders;
            return addMarketOrder(orders, {
                clOrdId: id,
                side: frame.side,
                pricePx: frame.price,
                originalQty: frame.remainingQuantity,
                remainingQty: frame.remainingQuantity,
                status: "OPEN",
                ordType,
                tif: "GTC",
                firstSeenNanos: frame.timestamp,
            });
        }
        case "ORDER_CANCELLED":
            return updateMarketOrder(orders, id, (o) => (isTerminal(o.status) ? o : { ...o, status: "CANCELLED" })) ?? orders;
        case "ORDER_EXPIRED":
            return updateMarketOrder(orders, id, (o) => (isTerminal(o.status) ? o : { ...o, status: "EXPIRED" })) ?? orders;
        default:
            // ORDER_REJECTED: a NEW rejected outright never had a row, and a CANCEL for an
            // order that already traded away is rejected under that order's id while the
            // order itself is unaffected. Either way there is nothing to change.
            return orders;
    }
}

/** Status transition for one EXEC applied to one of my orders (aggressor path). */
function nextOrder(order: MyOrder, frame: ExecFrame): MyOrder {
    if (isTerminal(order.status)) return order;

    switch (frame.execType) {
        case "ORDER_ACCEPTED":
            // A trailing ACCEPTED after a partial fill reports the rested remainder.
            // Update the quantity but keep the more informative PARTIALLY_FILLED label.
            return {
                ...order,
                status: order.status === "PARTIALLY_FILLED" ? "PARTIALLY_FILLED" : "OPEN",
                remainingQty: frame.remainingQuantity,
            };
        case "ORDER_PARTIALLY_FILLED":
            return { ...order, status: "PARTIALLY_FILLED", remainingQty: frame.remainingQuantity };
        case "ORDER_FILLED":
            return { ...order, status: "FILLED", remainingQty: 0 };
        case "ORDER_CANCELLED":
            // Remaining is left at its last known value; the row is terminal either way.
            return { ...order, status: "CANCELLED" };
        case "ORDER_REJECTED":
            return { ...order, status: "REJECTED" };
        case "ORDER_EXPIRED":
            // IOC/FOK terminal (SRS §3.4): remainingQuantity is the quantity that
            // expired unexecuted, which is exactly this row's current remaining, so
            // adopting it keeps filled/total consistent after zero or more fills.
            return { ...order, status: "EXPIRED", remainingQty: frame.remainingQuantity };
    }
}

/**
 * Passive-side fill against one of our resting orders (P7-8).
 *
 * A passive resting order receives no EXEC of its own (carried constraint 2): the
 * engine reports only the aggressor. So when a fill names one of our orders as the
 * passive side, we decrement its remaining quantity locally by that fill's
 * per-event filledQuantity (Q7-2). This is a documented client-side derivation,
 * not a server-reported value. Remaining is clamped at zero; a decrement to zero
 * is a full consumption (FILLED, terminal), otherwise the row is PARTIALLY_FILLED.
 * A terminal row is never resurrected, mirroring nextOrder's aggressor guard.
 *
 * Pure and exported for direct unit testing (mirrors aggressorSideFor).
 */
export function passiveFill(order: MyOrder, filledQty: number): MyOrder {
    if (isTerminal(order.status)) return order;
    const remainingQty = Math.max(0, order.remainingQty - filledQty);
    const status: OrderStatus = remainingQty === 0 ? "FILLED" : "PARTIALLY_FILLED";
    return { ...order, remainingQty, status };
}

function applyExec(state: AppState, frame: ExecFrame): AppState {
    const knows = (id: number): boolean => isOwnOrder(state.myOrders, id);

    let tape = state.tape;
    let sessionVolume = state.sessionVolume;
    let sessionOpenPx = state.sessionOpenPx;
    let sessionHighPx = state.sessionHighPx;
    let sessionLowPx = state.sessionLowPx;
    let sessionTradeCount = state.sessionTradeCount;
    let sessionNotional = state.sessionNotional;
    let bars = state.bars;
    // Session VWAP after this fill (P18-1 stamps it on own fills); unset off fills.
    let fillVwap = SESSION_OPEN_UNSET;

    if (isFill(frame)) {
        const entry: TapeEntry = {
            tradeId: frame.tradeId,
            pricePx: frame.price,
            quantity: frame.filledQuantity,
            aggressorOrderId: frame.aggressorOrderId,
            passiveOrderId: frame.passiveOrderId,
            timestamp: frame.timestamp,
            mine: knows(frame.aggressorOrderId) || knows(frame.passiveOrderId),
            aggressorSide:
                frame.aggressorSide ??
                aggressorSideFor(state.myOrders, frame.aggressorOrderId, frame.passiveOrderId),
        };
        tape = [entry, ...state.tape].slice(0, TAPE_CAP);

        // One onFill per trade, aggressor-only, filledQuantity is the per-event slice
        // (P7-0/Q7-2), so this running sum is the true session quantity with no double
        // count. Accumulated here precisely because the tape is capped.
        sessionVolume = state.sessionVolume + frame.filledQuantity;

        // The first trade sets the session-open reference, once and never again.
        // frame.price is the resting/passive execution price (carried constraint 6).
        // Prices are positive units, so "<= 0" reads as "still unset".
        if (sessionOpenPx <= 0) {
            sessionOpenPx = frame.price;
        }

        // Session high and low (P13-1), from the same frame.price. The first fill
        // SEEDS both rather than being compared against them: an unset accumulator
        // holds SESSION_OPEN_UNSET, and -1 would win every Math.min against a real
        // positive price, pinning the low at the sentinel forever. Guarded on
        // the same "<= 0 means unset" test the open uses, so the two stay in step.
        if (sessionHighPx <= 0 || sessionLowPx <= 0) {
            sessionHighPx = frame.price;
            sessionLowPx = frame.price;
        } else {
            sessionHighPx = Math.max(sessionHighPx, frame.price);
            sessionLowPx = Math.min(sessionLowPx, frame.price);
        }

        // One onFill per trade, aggressor-only (P7-0/Q7-2), so one increment per fill
        // EXEC counts trades and not sides. Unconditional: a PARTIALLY_FILLED print
        // is a trade exactly as a FILLED one is, and both reach this guard.
        sessionTradeCount = sessionTradeCount + 1;

        // Session notional and VWAP (P17-3), then the four bar series. Each bar records
        // the session VWAP after this fill, so a closed bar holds the VWAP after its
        // last fill. Prices and quantities are integers on the wire, so BigInt() is exact.
        sessionNotional = state.sessionNotional + BigInt(frame.price) * BigInt(frame.filledQuantity);
        fillVwap = vwapUnits(sessionNotional, sessionVolume);
        bars = foldBars(state.bars, frame.timestamp, frame.price, frame.filledQuantity, fillVwap);
    }

    // Aggressor path: EXEC orderId names the aggressor on fills, and the
    // cancelled/rejected/expired order otherwise. Authoritative for that row.
    const aggIndex = state.myOrders.findIndex((o) => o.clOrdId === frame.orderId);

    // Passive path (P7-8): a fill's passiveOrderId names our resting order, which
    // receives no EXEC of its own (carried constraint 2), so we decrement it
    // locally. Guarded on isFill and passiveOrderId > 0 (client clOrdIds are always
    // positive, so the -1 NA sentinel can never match), and on passiveOrderId !==
    // orderId so a single frame can never drive both transitions on one row (an
    // order never trades with itself, so on any fill aggressor != passive anyway).
    const passiveIndex =
        isFill(frame) && frame.passiveOrderId > 0 && frame.passiveOrderId !== frame.orderId
            ? state.myOrders.findIndex((o) => o.clOrdId === frame.passiveOrderId)
            : -1;

    // Own fills (P18-1): one record per own side of this fill. A fill is folded only
    // when it actually moved the row: nextOrder and passiveFill return the same row
    // for a terminal order, which is also what keeps notices from firing twice, so
    // the aggregates, the fill list and the row's status can never disagree.
    const ownFills: OwnFill[] = [];
    const withFill = (o: MyOrder, role: FillRole): MyOrder => {
        ownFills.push({
            clOrdId: o.clOrdId,
            tradeId: frame.tradeId,
            side: o.side,
            pricePx: frame.price,
            qty: frame.filledQuantity,
            timestamp: frame.timestamp,
            role,
            vwap: fillVwap,
        });
        return { ...o, exec: foldExec(o.exec, frame.price, frame.filledQuantity, role, fillVwap) };
    };

    let myOrders = state.myOrders;
    if (aggIndex !== -1 || passiveIndex !== -1) {
        let changed = false;
        const next = state.myOrders.map((o, i) => {
            if (i === aggIndex) {
                let updated = nextOrder(o, frame);
                if (updated !== o) {
                    changed = true;
                    if (isFill(frame)) updated = withFill(updated, "aggressive");
                }
                return updated;
            }
            if (i === passiveIndex) {
                let updated = passiveFill(o, frame.filledQuantity);
                if (updated !== o) {
                    changed = true;
                    updated = withFill(updated, "passive");
                }
                return updated;
            }
            return o;
        });
        // Allocate a new array only when a row actually changed, so a no-op frame
        // keeps the myOrders slice reference (the P7-3 session/fixFrame identity
        // checks depend on slice-level identity holding on no-op paths).
        if (changed) myOrders = next;
    }

    // Newest first; a frame with no own fill keeps the slice reference.
    const myFills =
        ownFills.length === 0 ? state.myFills : [...ownFills, ...state.myFills].slice(0, OWN_FILLS_CAP);

    // Other participants' orders (the bots'), from the same frame.
    const marketOrders = applyMarketExec(state.marketOrders, frame, knows);

    // Every EXEC belongs in the P7-9 inspector log, whatever its execType: the
    // inspector's honest promise is to show the actual outbound EXEC stream, not
    // fills only. Newest first, capped.
    const inspectorLog = [frame, ...state.inspectorLog].slice(0, INSPECTOR_CAP);

    // Order notifications (UI polish D4), at most once per venue seq.
    let orderNotices = state.orderNotices;
    let noticeSeq = state.noticeSeq;
    let recentExecSeqs = state.recentExecSeqs;
    const seenBefore = frame.seq !== undefined && state.recentExecSeqs.includes(frame.seq);
    if (!seenBefore) {
        const drafts = noticesForExec(state.myOrders, myOrders, frame);
        if (drafts.length > 0) {
            const raised = drafts.map((d) => {
                noticeSeq += 1;
                return { ...d, id: noticeSeq };
            });
            orderNotices = [...state.orderNotices, ...raised].slice(-NOTICE_CAP);
        }
        if (frame.seq !== undefined) {
            recentExecSeqs = [...state.recentExecSeqs, frame.seq].slice(-SEQ_MEMORY);
        }
    }

    // Every EXEC is a frame received from the server, so it advances the liveness
    // marker even when it changes nothing else (a rejection for an unknown id, a
    // fill against no owned order). tape and myOrders keep their existing references
    // on those no-op paths, so referential-equality checks on those slices still
    // hold; only the top-level object is newly allocated.
    return {
        ...state,
        tape,
        myOrders,
        myFills,
        marketOrders,
        sessionVolume,
        sessionOpenPx,
        sessionHighPx,
        sessionLowPx,
        sessionTradeCount,
        sessionNotional,
        bars,
        lastFrameNanos: frame.timestamp,
        inspectorLog,
        orderNotices,
        noticeSeq,
        recentExecSeqs,
    };
}

function applySent(state: AppState, frame: ClientFrame, sentAtNanos?: number): AppState {
    // MsgSeqNum counts every outbound frame that actually went on the wire, NEW and
    // CANCEL alike: both are real FIX messages (35=D / 35=F) and each consumes a
    // sequence number. `send` dispatches SENT only on a real socket write, so a
    // no-op send while disconnected consumes nothing.
    const msgSeqNum = state.msgSeqNum + 1;

    // Order-row bookkeeping is NEW-only and unchanged from P5-1: CANCEL records no
    // row (EXEC stays the sole authority on status), and a duplicate clOrdId is
    // ignored. Both paths keep the myOrders reference intact, so the counter can
    // advance without disturbing the panel or its identity guarantees.
    let myOrders = state.myOrders;
    if (frame.type === "NEW" && !state.myOrders.some((o) => o.clOrdId === frame.clOrdId)) {
        const order: MyOrder = {
            clOrdId: frame.clOrdId,
            side: frame.side,
            pricePx: frame.price,
            originalQty: frame.qty,
            remainingQty: frame.qty,
            status: "PENDING",
            // Order attributes captured at send time (P14-6): no EXEC frame carries
            // any of them, so the blotter reads them from the row, not the wire.
            ordType: frame.ordType,
            tif: frame.tif,
            displayQty: frame.maxFloor,
            // Client-assigned send time (P7-8), included only when the dispatch supplied
            // it, so a row built without one is byte-identical to the pre-P7-8 shape.
            ...(sentAtNanos !== undefined ? { sentAtNanos } : {}),
        };
        myOrders = [order, ...state.myOrders];
    }

    return { ...state, myOrders, msgSeqNum };
}

export function reducer(state: AppState, action: Action): AppState {
    switch (action.type) {
        case "CONNECTION": {
            if (action.status === state.connection) return state;
            // The book SURVIVES a drop rather than being cleared. A frozen ladder marked
            // stale (TradingPage dims the depth panel while connection !== "open", and the
            // ConnectionBadge shows the state) is more useful than a blank one: those levels
            // were last-known real state, and the orders behind them may still be resting
            // server-side. It is replaced wholesale by the next BOOK frame — the snapshot a
            // reconnecting client is sent on connect, or the next order flow. Everything else
            // survives too: myOrders and tape, the session aggregates and liveness marker, and
            // the P7-9 inspector log all belong to the browser session, not the socket.
            return { ...state, connection: action.status };
        }
        case "FRAME": {
            const frame = action.frame;
            if (frame.type === "BOOK") {
                // Wholesale replacement. Never merged with anything. BOOK frames are not
                // inspector material (P7-9): the filter set is FIX NEW, FIX CANCEL, and
                // EXEC only, so no log entry is appended here.
                return {
                    ...state,
                    book: {
                        bestBid: frame.bestBid,
                        bestAsk: frame.bestAsk,
                        bids: frame.bids,
                        asks: frame.asks,
                        timestamp: frame.timestamp,
                    },
                    lastFrameNanos: frame.timestamp,
                };
            }
            if (frame.type === "FIX") {
                // The FIX echo carries no other application state, but it IS a frame the
                // client received (advances lastFrameNanos, P7-3) and it IS P7-9 inspector
                // material: every inbound packet the server echoed back, newest first,
                // capped. This is the one line the P7-2 note anticipated changing, now
                // changed twice.
                return {
                    ...state,
                    lastFrameNanos: frame.timestamp,
                    inspectorLog: [frame, ...state.inspectorLog].slice(0, INSPECTOR_CAP),
                };
            }
            return applyExec(state, frame);
        }
        case "SENT":
            return applySent(state, action.frame, action.sentAtNanos);
    }
}
