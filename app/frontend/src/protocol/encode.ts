/**
 * Outbound client frame builders and the client-owned ClOrdID source.
 *
 * clOrdId is client-owned, numeric, and monotonic. The counter is seeded at
 * Date.now() so a page reload cannot collide with orders still resting on the
 * server from before the refresh, and every outbound message (cancels included)
 * consumes an id. It is generated in exactly one place and never derived from
 * server data.
 *
 * P7-7 adds a non-consuming peek() to the generator so the order ticket can show
 * the id the next send will use WITHOUT advancing the sequence. Only a call to
 * the generator itself consumes an id; peek() never does. Generation stays here;
 * the ticket only reads and displays the peeked value.
 *
 * P14-6 changes newOrderFrame to take the resolved OrderIntent (decision H): the
 * order type, time in force and display quantity all travel in one object, so the
 * builder emits the SRS §3.6 NEW frame without the signature growing again at
 * P14-8 / P14-10.
 */

import { NA, SYMBOL } from "./messages";
import type { CancelOrderFrame, ClientFrame, NewOrderFrame, OrderIntent } from "./messages";

function requirePositiveInt(value: number, label: string): void {
    if (!Number.isSafeInteger(value) || value <= 0) {
        throw new RangeError(`${label} must be a positive safe integer, got ${value}`);
    }
}

/** A monotonic id source: callable for the next id, with a non-consuming peek. */
export interface ClOrdIdGenerator {
    (): number;
    /** The id the next call will return, without consuming it. */
    peek(): number;
}

/**
 * Builds an independent monotonic generator. The exported `nextClOrdId` is the
 * app-wide instance; tests build their own with a fixed seed for determinism.
 * The returned value is callable (each call yields the next id) and carries a
 * `peek()` that reads the next id without advancing, so the ticket can display
 * the id a send will use without consuming the sequence early.
 */
export function createClOrdIdGenerator(seed: number = Date.now()): ClOrdIdGenerator {
    requirePositiveInt(seed, "clOrdId seed");
    let next = seed;
    const gen = (() => {
        if (!Number.isSafeInteger(next)) {
            throw new RangeError("clOrdId exhausted the safe-integer range");
        }
        return next++;
    }) as ClOrdIdGenerator;
    gen.peek = () => next;
    return gen;
}

/** App-wide ClOrdID source. Generated in exactly one place; never derived from server data. */
export const nextClOrdId: ClOrdIdGenerator = createClOrdIdGenerator();

/**
 * Builds the NEW frame from the resolved intent. `pricePx` is integer units of
 * $0.0001 — the server converts to FIX decimal dollars. The positive-price check
 * is conditional on order type (P14-10): a LIMIT must carry a positive on-tick
 * price, while a PEG_MID carries no price and the frame sends the `-1` NA
 * sentinel (SRS §3.6), which the server does not read for a peg. `ordType`, `tif`
 * and `maxFloor` ride through verbatim from the intent, in the SRS §3.6 field order.
 */
export function newOrderFrame(clOrdId: number, intent: OrderIntent): NewOrderFrame {
    requirePositiveInt(clOrdId, "clOrdId");
    const isPeg = intent.ordType === "PEG_MID";
    if (!isPeg) {
        requirePositiveInt(intent.pricePx, "pricePx");
    }
    requirePositiveInt(intent.qty, "qty");
    return {
        type: "NEW",
        clOrdId,
        side: intent.side,
        ordType: intent.ordType,
        tif: intent.tif,
        price: isPeg ? NA : intent.pricePx,
        qty: intent.qty,
        maxFloor: intent.displayQty,
        symbol: SYMBOL,
    };
}

/**
 * `origClOrdId` is the resting order being cancelled; the request itself takes a
 * fresh `clOrdId`. The resulting ORDER_CANCELLED echoes `origClOrdId` as its
 * `orderId`, never the request's own id.
 */
export function cancelOrderFrame(clOrdId: number, origClOrdId: number): CancelOrderFrame {
    requirePositiveInt(clOrdId, "clOrdId");
    requirePositiveInt(origClOrdId, "origClOrdId");
    return { type: "CANCEL", clOrdId, origClOrdId };
}

export function serializeClientFrame(frame: ClientFrame): string {
    return JSON.stringify(frame);
}
