import { describe, expect, it } from "vitest";

import { isFill, parseServerFrame, SYMBOL } from "../src/protocol/messages";
import type { BookFrame, ExecFrame } from "../src/protocol/messages";
import {
    cancelOrderFrame,
    createClOrdIdGenerator,
    newOrderFrame,
    serializeClientFrame,
} from "../src/protocol/encode";

const BOOK_JSON = JSON.stringify({
    type: "BOOK",
    bestBid: 1500000,
    bestAsk: 1502500,
    bids: [[1500000, 10], [1490000, 5]],
    asks: [[1502500, 7]],
    timestamp: 1234567890,
});

const EXEC_JSON = JSON.stringify({
    type: "EXEC",
    execType: "ORDER_FILLED",
    orderId: 2,
    tradeId: 1,
    price: 1500000,
    filledQuantity: 4,
    remainingQuantity: 0,
    aggressorOrderId: 2,
    passiveOrderId: 1,
    timestamp: 99,
});

describe("parseServerFrame — BOOK", () => {
    it("narrows a well-formed BOOK frame", () => {
        const frame = parseServerFrame(BOOK_JSON);
        expect(frame?.type).toBe("BOOK");
        const book = frame as BookFrame;
        expect(book.bestBid).toBe(1500000);
        expect(book.bestAsk).toBe(1502500);
        expect(book.bids).toEqual([[1500000, 10], [1490000, 5]]);
        expect(book.asks).toEqual([[1502500, 7]]);
        expect(book.timestamp).toBe(1234567890);
    });

    it("accepts an empty book with -1 tops", () => {
        const frame = parseServerFrame(
            JSON.stringify({ type: "BOOK", bestBid: -1, bestAsk: -1, bids: [], asks: [], timestamp: 1 }),
        );
        expect(frame).not.toBeNull();
        const book = frame as BookFrame;
        expect(book.bids).toEqual([]);
        expect(book.asks).toEqual([]);
        expect(book.bestBid).toBe(-1);
    });

    it("rejects a missing field", () => {
        expect(
            parseServerFrame(JSON.stringify({ type: "BOOK", bestBid: 1, bids: [], asks: [], timestamp: 1 })),
        ).toBeNull();
    });

    it("rejects a malformed level", () => {
        const bad = JSON.stringify({
            type: "BOOK",
            bestBid: 1,
            bestAsk: 2,
            bids: [[1500000]],
            asks: [],
            timestamp: 1,
        });
        expect(parseServerFrame(bad)).toBeNull();
    });

    it("rejects a non-integer price in a level", () => {
        const bad = JSON.stringify({
            type: "BOOK",
            bestBid: 1,
            bestAsk: 2,
            bids: [[150.25, 10]],
            asks: [],
            timestamp: 1,
        });
        expect(parseServerFrame(bad)).toBeNull();
    });

    it("rejects levels that aren't an array", () => {
        const bad = JSON.stringify({
            type: "BOOK",
            bestBid: 1,
            bestAsk: 2,
            bids: "nope",
            asks: [],
            timestamp: 1,
        });
        expect(parseServerFrame(bad)).toBeNull();
    });
});

describe("parseServerFrame — EXEC", () => {
    it("narrows a well-formed EXEC frame with all nine fields", () => {
        const frame = parseServerFrame(EXEC_JSON);
        expect(frame?.type).toBe("EXEC");
        const exec = frame as ExecFrame;
        expect(exec.execType).toBe("ORDER_FILLED");
        expect(exec.orderId).toBe(2);
        expect(exec.aggressorOrderId).toBe(2);
        expect(exec.passiveOrderId).toBe(1);
        expect(exec.price).toBe(1500000);
        expect(exec.remainingQuantity).toBe(0);
    });

    it("accepts -1 NA sentinels on an accept report", () => {
        const json = JSON.stringify({
            type: "EXEC",
            execType: "ORDER_ACCEPTED",
            orderId: 1,
            tradeId: -1,
            price: 1500000,
            filledQuantity: -1,
            remainingQuantity: 10,
            aggressorOrderId: -1,
            passiveOrderId: -1,
            timestamp: 5,
        });
        const exec = parseServerFrame(json) as ExecFrame;
        expect(exec.tradeId).toBe(-1);
        expect(exec.remainingQuantity).toBe(10);
    });

    it("rejects an unknown execType", () => {
        const bad = EXEC_JSON.replace("ORDER_FILLED", "ORDER_EXPIRED");
        expect(parseServerFrame(bad)).toBeNull();
    });

    it("rejects a frame missing one of the nine fields", () => {
        const parsed = JSON.parse(EXEC_JSON) as Record<string, unknown>;
        delete parsed.passiveOrderId;
        expect(parseServerFrame(JSON.stringify(parsed))).toBeNull();
    });
});

describe("parseServerFrame — rejects", () => {
    it("rejects malformed JSON", () => {
        expect(parseServerFrame('{"type":"BOOK", this is not json')).toBeNull();
    });

    it("rejects an unknown type", () => {
        expect(parseServerFrame(JSON.stringify({ type: "HEARTBEAT" }))).toBeNull();
    });

    it("rejects a missing type", () => {
        expect(parseServerFrame(JSON.stringify({ bestBid: 1 }))).toBeNull();
    });

    it("rejects non-object JSON", () => {
        expect(parseServerFrame("42")).toBeNull();
        expect(parseServerFrame("null")).toBeNull();
        expect(parseServerFrame("[]")).toBeNull();
        expect(parseServerFrame('"BOOK"')).toBeNull();
    });

    it("rejects empty input", () => {
        expect(parseServerFrame("")).toBeNull();
    });
});

describe("isFill", () => {
    it("is true only for the two trade reports", () => {
        const base = parseServerFrame(EXEC_JSON) as ExecFrame;
        expect(isFill(base)).toBe(true);
        expect(isFill({ ...base, execType: "ORDER_PARTIALLY_FILLED" })).toBe(true);
        expect(isFill({ ...base, execType: "ORDER_ACCEPTED" })).toBe(false);
        expect(isFill({ ...base, execType: "ORDER_CANCELLED" })).toBe(false);
        expect(isFill({ ...base, execType: "ORDER_REJECTED" })).toBe(false);
    });
});

describe("outbound encoders", () => {
    it("emits the exact NEW shape in integer units", () => {
        const frame = newOrderFrame(7, "BUY", 1502500, 10);
        expect(frame).toEqual({
            type: "NEW",
            clOrdId: 7,
            side: "BUY",
            price: 1502500,
            qty: 10,
            symbol: "ASML",
        });
        expect(SYMBOL).toBe("ASML");
    });

    it("emits the exact CANCEL shape", () => {
        expect(cancelOrderFrame(9, 1)).toEqual({ type: "CANCEL", clOrdId: 9, origClOrdId: 1 });
    });

    it("serializes to the JSON the server parses", () => {
        expect(serializeClientFrame(newOrderFrame(7, "SELL", 500, 3))).toBe(
            '{"type":"NEW","clOrdId":7,"side":"SELL","price":500,"qty":3,"symbol":"ASML"}',
        );
        expect(serializeClientFrame(cancelOrderFrame(9, 1))).toBe(
            '{"type":"CANCEL","clOrdId":9,"origClOrdId":1}',
        );
    });

    it("rejects non-positive or non-integer fields", () => {
        expect(() => newOrderFrame(7, "BUY", 0, 10)).toThrow(RangeError);
        expect(() => newOrderFrame(7, "BUY", -1, 10)).toThrow(RangeError);
        expect(() => newOrderFrame(7, "BUY", 1502500, 0)).toThrow(RangeError);
        expect(() => newOrderFrame(7, "BUY", 150.25, 10)).toThrow(RangeError);
        expect(() => newOrderFrame(0, "BUY", 1502500, 10)).toThrow(RangeError);
        expect(() => cancelOrderFrame(9, 0)).toThrow(RangeError);
    });
});

describe("clOrdId generator", () => {
    it("starts at the seed and increments monotonically", () => {
        const next = createClOrdIdGenerator(1000);
        expect(next()).toBe(1000);
        expect(next()).toBe(1001);
        expect(next()).toBe(1002);
    });

    it("issues an id for every message, cancels included", () => {
        const next = createClOrdIdGenerator(500);
        const ids = [next(), next(), next(), next()];
        expect(new Set(ids).size).toBe(4);
        expect(ids).toEqual([500, 501, 502, 503]);
    });

    it("produces independent sequences per generator", () => {
        const a = createClOrdIdGenerator(10);
        const b = createClOrdIdGenerator(10);
        expect(a()).toBe(10);
        expect(a()).toBe(11);
        expect(b()).toBe(10);
    });

    it("rejects an invalid seed", () => {
        expect(() => createClOrdIdGenerator(0)).toThrow(RangeError);
        expect(() => createClOrdIdGenerator(-1)).toThrow(RangeError);
        expect(() => createClOrdIdGenerator(1.5)).toThrow(RangeError);
    });
});
