/**
 * The wire contract, and the ONLY place raw JSON becomes typed.
 *
 * Shapes are transcribed from the Phase-4 as-built `WebSocketPublisher`
 * (serializeExecution / serializeSnapshot) and `WebSocketFrameHandler`, not from
 * a guide summary. Every price is integer units of $0.0001 in both directions;
 * dollars exist only at the render/parse edge (format.ts).
 *
 * Server -> client: BOOK (authoritative book state) | EXEC (notification) |
 * FIX (raw inbound packet echo). Client -> server: NEW | CANCEL.
 */

/** Single instrument; the server emits no symbol field, and expects this one inbound. */
export const SYMBOL = "ASML" as const;

export type Side = "BUY" | "SELL";

/** Full Java enum names, via ExecutionEventType.name(). */
export type ExecType =
    | "ORDER_ACCEPTED"
    | "ORDER_FILLED"
    | "ORDER_PARTIALLY_FILLED"
    | "ORDER_CANCELLED"
    | "ORDER_REJECTED";

/** One depth level: [pricePx, aggregatedQty]. */
export type Level = readonly [price: number, qty: number];

/**
 * Authoritative book state. Already trimmed server-side to the valid prefix
 * (P4-6 honours the count-authoritative contract), so arrays hold only real
 * levels. `-1` tops and empty arrays on an empty side. bids highest-first,
 * asks lowest-first.
 */
export interface BookFrame {
    readonly type: "BOOK";
    readonly bestBid: number;
    readonly bestAsk: number;
    readonly bids: readonly Level[];
    readonly asks: readonly Level[];
    readonly timestamp: number;
}

/**
 * Execution report. All nine fields are always present; `-1` marks NA — the
 * server emits a fixed schema and never omits keys.
 *
 * On ORDER_FILLED / ORDER_PARTIALLY_FILLED, `orderId === aggressorOrderId` and
 * `remainingQuantity` is the AGGRESSOR's remaining size. A passive resting order
 * that is hit receives no EXEC of its own (confirmed against MatchingEngine's
 * match loops) — see reducer.ts for how that gap is modelled.
 */
export interface ExecFrame {
    readonly type: "EXEC";
    readonly execType: ExecType;
    readonly orderId: number;
    readonly tradeId: number;
    readonly price: number;
    readonly filledQuantity: number;
    readonly remainingQuantity: number;
    readonly aggressorOrderId: number;
    readonly passiveOrderId: number;
    readonly timestamp: number;
}

/**
 * Raw inbound FIX packet echo (P7-2). `raw` is the exact SOH-delimited byte
 * sequence that FixParser consumed, decoded Latin-1 server-side — not a
 * client-side reconstruction, which is the whole point: the frontend has no FIX
 * encoder, so a rebuilt packet would have to invent `9=BodyLength` and
 * `10=CheckSum`. Those two fields are displayed verbatim by the P7-9 inspector
 * and must never be recomputed here.
 *
 * `direction` is fixed at "INBOUND": the system is FIX in, JSON out, and the
 * server builds no outbound tag-value message at all. Outbound entries in the
 * inspector are the EXEC JSON frames, labelled as such.
 *
 * `seqNum` is the server's per-connection counter for this echo stream. It is
 * NOT the FIX `34=` MsgSeqNum — the produced-and-parsed subset carries no tag
 * 34/49/56/52 (P7-0/Q7-4), so the tag breakdown must not synthesize them — and
 * it is NOT the client-assigned session counter shown in the header.
 */
export interface FixFrame {
    readonly type: "FIX";
    readonly direction: "INBOUND";
    readonly raw: string;
    readonly seqNum: number;
    readonly timestamp: number;
}

export type ServerFrame = BookFrame | ExecFrame | FixFrame;

export interface NewOrderFrame {
    readonly type: "NEW";
    readonly clOrdId: number;
    readonly side: Side;
    /** Integer units of $0.0001. JsonToFix.formatPrice does units -> FIX decimal server-side. */
    readonly price: number;
    readonly qty: number;
    readonly symbol: typeof SYMBOL;
}

export interface CancelOrderFrame {
    readonly type: "CANCEL";
    readonly clOrdId: number;
    readonly origClOrdId: number;
}

export type ClientFrame = NewOrderFrame | CancelOrderFrame;

/** The NA sentinel used by every optional numeric field on the wire. */
export const NA = -1;

const EXEC_TYPES: readonly string[] = [
    "ORDER_ACCEPTED",
    "ORDER_FILLED",
    "ORDER_PARTIALLY_FILLED",
    "ORDER_CANCELLED",
    "ORDER_REJECTED",
];

export function isExecType(value: unknown): value is ExecType {
    return typeof value === "string" && EXEC_TYPES.includes(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Integer-or-null. Callers must compare `=== null` — 0 is a legitimate value. */
function int(value: unknown): number | null {
    return typeof value === "number" && Number.isInteger(value) ? value : null;
}

/** String-or-null. The empty string is not valid for any field that uses this. */
function str(value: unknown): string | null {
    return typeof value === "string" && value.length > 0 ? value : null;
}

function levels(value: unknown): Level[] | null {
    if (!Array.isArray(value)) return null;
    const out: Level[] = [];
    for (const raw of value) {
        if (!Array.isArray(raw) || raw.length !== 2) return null;
        const price = int(raw[0]);
        const qty = int(raw[1]);
        if (price === null || qty === null) return null;
        out.push([price, qty]);
    }
    return out;
}

/**
 * The single narrowing point: raw socket text -> typed frame, or null.
 *
 * Returns a freshly constructed object rather than the parsed one, so an
 * accepted frame provably matches its declared type and no unvalidated extra
 * field can ride along into state.
 */
export function parseServerFrame(raw: string): ServerFrame | null {
    let parsed: unknown;
    try {
        parsed = JSON.parse(raw);
    } catch {
        return null;
    }
    if (!isRecord(parsed)) return null;

    switch (parsed.type) {
        case "BOOK": {
            const bestBid = int(parsed.bestBid);
            const bestAsk = int(parsed.bestAsk);
            const timestamp = int(parsed.timestamp);
            const bids = levels(parsed.bids);
            const asks = levels(parsed.asks);
            if (bestBid === null || bestAsk === null || timestamp === null) return null;
            if (bids === null || asks === null) return null;
            return { type: "BOOK", bestBid, bestAsk, bids, asks, timestamp };
        }
        case "EXEC": {
            const execType = parsed.execType;
            if (!isExecType(execType)) return null;
            const orderId = int(parsed.orderId);
            const tradeId = int(parsed.tradeId);
            const price = int(parsed.price);
            const filledQuantity = int(parsed.filledQuantity);
            const remainingQuantity = int(parsed.remainingQuantity);
            const aggressorOrderId = int(parsed.aggressorOrderId);
            const passiveOrderId = int(parsed.passiveOrderId);
            const timestamp = int(parsed.timestamp);
            if (
                orderId === null ||
                tradeId === null ||
                price === null ||
                filledQuantity === null ||
                remainingQuantity === null ||
                aggressorOrderId === null ||
                passiveOrderId === null ||
                timestamp === null
            ) {
                return null;
            }
            return {
                type: "EXEC",
                execType,
                orderId,
                tradeId,
                price,
                filledQuantity,
                remainingQuantity,
                aggressorOrderId,
                passiveOrderId,
                timestamp,
            };
        }
        case "FIX": {
            // direction is a fixed literal, not a free field: the server never echoes
            // anything but inbound packets, so anything else is a contract violation.
            if (parsed.direction !== "INBOUND") return null;
            const rawPacket = str(parsed.raw);
            const seqNum = int(parsed.seqNum);
            const timestamp = int(parsed.timestamp);
            if (rawPacket === null || seqNum === null || timestamp === null) return null;
            return { type: "FIX", direction: "INBOUND", raw: rawPacket, seqNum, timestamp };
        }
        default:
            return null;
    }
}

/** True for the two EXEC types that represent an actual trade. */
export function isFill(frame: ExecFrame): boolean {
    return frame.execType === "ORDER_FILLED" || frame.execType === "ORDER_PARTIALLY_FILLED";
}
