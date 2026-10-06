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
 * P7-3 adds the session state the header renders: sessionVolume, sessionOpenCents,
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
 * sessionHighCents, sessionLowCents and sessionTradeCount. They sit beside
 * sessionVolume and sessionOpenCents in the same isFill guard, and they exist here
 * for the same reason those two do: the tape is capped at TAPE_CAP, so a high or a
 * low folded from it would silently mean "high of the last TAPE_CAP prints" and
 * would drift as old prints age out. No notional accumulator and no VWAP this
 * phase (P13 D1): VWAP's only input would be dead state until an indicator phase
 * consumes it, and adding it then is one more line in this same block.
 */

import { isFill } from "../protocol/messages";
import type { ClientFrame, ExecFrame, FixFrame, Level, ServerFrame, Side } from "../protocol/messages";

/** Newest-first fill history cap. */
export const TAPE_CAP = 200;

/**
 * Newest-first inspector log cap (P7-9). Larger than TAPE_CAP because it merges
 * two streams (every FIX echo and every EXEC frame, not fills only).
 */
export const INSPECTOR_CAP = 500;

/**
 * Sentinel for "no traded price yet this session": no trade has printed, so there
 * is no open, no high and no low. Exported since P13-1 so the three price-valued
 * session accumulators and their tests name one constant rather than repeating a
 * bare -1. Negative by design: centsToDollars renders any negative value as the
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
    | "REJECTED";

const TERMINAL: readonly OrderStatus[] = ["FILLED", "CANCELLED", "REJECTED"];

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
    readonly priceCents: number;
    readonly quantity: number;
    readonly aggressorOrderId: number;
    readonly passiveOrderId: number;
    readonly timestamp: number;
    /** True when either side of the trade is one of this client's orders. */
    readonly mine: boolean;
    /**
     * Aggressor side (P7-6), derived client-side where knowable. No EXEC frame
     * carries a side, so this is set only when one of our own orders is in the
     * trade: our side when we are the aggressor, the opposite when we are the
     * passive side (a trade always crosses a buy against a sell). undefined for
     * anonymous prints. That derivable set equals `mine`, so the tag shows on
     * own-trade rows only.
     */
    readonly aggressorSide?: Side;
}

/**
 * A locally originated order. Side and price are captured at SEND time: no EXEC
 * frame carries a side, so they cannot come from the wire. Status and remaining
 * quantity come only from EXEC (plus the P7-8 passive decrement).
 */
export interface MyOrder {
    readonly clOrdId: number;
    readonly side: Side;
    readonly priceCents: number;
    readonly originalQty: number;
    readonly remainingQty: number;
    readonly status: OrderStatus;
    /**
     * Client-assigned wall-clock send time in epoch nanoseconds (P7-8). Captured at
     * dispatch by useOrderBook (Date.now lifted into the P7-1 epoch-nanos domain),
     * never a server value. Absent on rows built without one (test fixtures); the
     * blotter renders such a row's send time as EMPTY_PRICE.
     */
    readonly sentAtNanos?: number;
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
     * Total quantity traded this session. Accumulated per fill EXEC. One onFill per
     * trade, aggressor-only, filledQuantity per-event (P7-0/Q7-2), so summing across
     * fills cannot double count. Held here, never folded from the capped tape.
     */
    readonly sessionVolume: number;
    /**
     * The session's first trade price in cents. Set once by the first fill EXEC and
     * never overwritten; SESSION_OPEN_UNSET until then. Session change is measured
     * against this, never a previous close.
     */
    readonly sessionOpenCents: number;
    /**
     * Highest and lowest traded price this session in cents, accumulated per fill.
     * SESSION_OPEN_UNSET until the first fill, which seeds both to its own price.
     * Held here, never folded from the capped tape, for the same reason
     * sessionVolume is: the tape forgets.
     */
    readonly sessionHighCents: number;
    readonly sessionLowCents: number;
    /**
     * Count of trade prints this session. One onFill per trade, aggressor-only
     * (P7-0/Q7-2), so incrementing once per fill EXEC counts trades, not sides. A
     * partial fill is a print like any other and counts once. Zero is a real value,
     * never a missing one.
     */
    readonly sessionTradeCount: number;
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
    sessionVolume: 0,
    sessionOpenCents: SESSION_OPEN_UNSET,
    sessionHighCents: SESSION_OPEN_UNSET,
    sessionLowCents: SESSION_OPEN_UNSET,
    sessionTradeCount: 0,
    msgSeqNum: 0,
    lastFrameNanos: 0,
    inspectorLog: [],
};

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
    const knows = (id: number): boolean =>
        id > 0 && state.myOrders.some((o) => o.clOrdId === id);

    let tape = state.tape;
    let sessionVolume = state.sessionVolume;
    let sessionOpenCents = state.sessionOpenCents;
    let sessionHighCents = state.sessionHighCents;
    let sessionLowCents = state.sessionLowCents;
    let sessionTradeCount = state.sessionTradeCount;

    if (isFill(frame)) {
        const entry: TapeEntry = {
            tradeId: frame.tradeId,
            priceCents: frame.price,
            quantity: frame.filledQuantity,
            aggressorOrderId: frame.aggressorOrderId,
            passiveOrderId: frame.passiveOrderId,
            timestamp: frame.timestamp,
            mine: knows(frame.aggressorOrderId) || knows(frame.passiveOrderId),
            aggressorSide: aggressorSideFor(state.myOrders, frame.aggressorOrderId, frame.passiveOrderId),
        };
        tape = [entry, ...state.tape].slice(0, TAPE_CAP);

        // One onFill per trade, aggressor-only, filledQuantity is the per-event slice
        // (P7-0/Q7-2), so this running sum is the true session quantity with no double
        // count. Accumulated here precisely because the tape is capped.
        sessionVolume = state.sessionVolume + frame.filledQuantity;

        // The first trade sets the session-open reference, once and never again.
        // frame.price is the resting/passive execution price (carried constraint 6).
        // Prices are positive cents, so "<= 0" reads as "still unset".
        if (sessionOpenCents <= 0) {
            sessionOpenCents = frame.price;
        }

        // Session high and low (P13-1), from the same frame.price. The first fill
        // SEEDS both rather than being compared against them: an unset accumulator
        // holds SESSION_OPEN_UNSET, and -1 would win every Math.min against a real
        // positive cent price, pinning the low at the sentinel forever. Guarded on
        // the same "<= 0 means unset" test the open uses, so the two stay in step.
        if (sessionHighCents <= 0 || sessionLowCents <= 0) {
            sessionHighCents = frame.price;
            sessionLowCents = frame.price;
        } else {
            sessionHighCents = Math.max(sessionHighCents, frame.price);
            sessionLowCents = Math.min(sessionLowCents, frame.price);
        }

        // One onFill per trade, aggressor-only (P7-0/Q7-2), so one increment per fill
        // EXEC counts trades and not sides. Unconditional: a PARTIALLY_FILLED print
        // is a trade exactly as a FILLED one is, and both reach this guard.
        sessionTradeCount = sessionTradeCount + 1;
    }

    // Aggressor path: EXEC orderId names the aggressor on fills, and the
    // cancelled/rejected order otherwise. Authoritative for that row.
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

    let myOrders = state.myOrders;
    if (aggIndex !== -1 || passiveIndex !== -1) {
        let changed = false;
        const next = state.myOrders.map((o, i) => {
            if (i === aggIndex) {
                const updated = nextOrder(o, frame);
                if (updated !== o) changed = true;
                return updated;
            }
            if (i === passiveIndex) {
                const updated = passiveFill(o, frame.filledQuantity);
                if (updated !== o) changed = true;
                return updated;
            }
            return o;
        });
        // Allocate a new array only when a row actually changed, so a no-op frame
        // keeps the myOrders slice reference (the P7-3 session/fixFrame identity
        // checks depend on slice-level identity holding on no-op paths).
        if (changed) myOrders = next;
    }

    // Every EXEC belongs in the P7-9 inspector log, whatever its execType: the
    // inspector's honest promise is to show the actual outbound EXEC stream, not
    // fills only. Newest first, capped.
    const inspectorLog = [frame, ...state.inspectorLog].slice(0, INSPECTOR_CAP);

    // Every EXEC is a frame received from the server, so it advances the liveness
    // marker even when it changes nothing else (a rejection for an unknown id, a
    // fill against no owned order). tape and myOrders keep their existing references
    // on those no-op paths, so referential-equality checks on those slices still
    // hold; only the top-level object is newly allocated.
    return {
        ...state,
        tape,
        myOrders,
        sessionVolume,
        sessionOpenCents,
        sessionHighCents,
        sessionLowCents,
        sessionTradeCount,
        lastFrameNanos: frame.timestamp,
        inspectorLog,
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
            priceCents: frame.price,
            originalQty: frame.qty,
            remainingQty: frame.qty,
            status: "PENDING",
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