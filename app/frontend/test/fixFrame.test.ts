/**
 * P7-2 / P7-3 / P7-9 — the FIX echo variant: parse narrowing and reducer effect.
 *
 * Kept in its own file rather than folded into protocol.test.ts / reducer.test.ts
 * because the three concerns here belong to one wire addition.
 *
 * Reducer effect history: P7-2 left a FIX frame fully inert (compile-forced
 * no-op). P7-3 promoted lastFrameNanos to a real liveness marker: a FIX echo
 * advances it and nothing else. P7-9 adds inspector storage: a FIX echo now also
 * prepends itself to inspectorLog. Every other slice (book, tape, myOrders,
 * sessionVolume, sessionOpenPx, msgSeqNum) still keeps its reference.
 */

import { describe, expect, it } from "vitest";

import { parseServerFrame } from "../src/protocol/messages";
import type { FixFrame } from "../src/protocol/messages";
import { initialState, reducer } from "../src/state/reducer";
import type { Action } from "../src/state/reducer";

/** A representative echo: SOH-delimited, with real 9= and 10= values from the server. */
const RAW =
    "8=FIX.4.2\u00019=51\u000135=D\u000111=7\u000155=ASML\u000154=1\u000138=100\u000144=150.25\u000110=123\u0001";

const VALID = {
    type: "FIX",
    direction: "INBOUND",
    raw: RAW,
    seqNum: 1,
    timestamp: 1_700_000_000_123_456_789,
};

const json = (o: unknown): string => JSON.stringify(o);

describe("FIX frame narrowing", () => {
    it("narrows a well-formed echo", () => {
        const frame = parseServerFrame(json(VALID));
        expect(frame).not.toBeNull();
        expect(frame?.type).toBe("FIX");

        const fix = frame as FixFrame;
        expect(fix.direction).toBe("INBOUND");
        expect(fix.seqNum).toBe(1);
        expect(fix.timestamp).toBe(1_700_000_000_123_456_789);
    });

    it("preserves the SOH delimiters and the 9=/10= fields verbatim", () => {
        const fix = parseServerFrame(json(VALID)) as FixFrame;
        expect(fix.raw).toBe(RAW);
        expect(fix.raw.split("\u0001")).toContain("9=51");
        expect(fix.raw.split("\u0001")).toContain("10=123");
        expect(fix.raw).toContain("\u0001");
    });

    it("returns a fresh object with no extra fields riding along", () => {
        const fix = parseServerFrame(json({ ...VALID, injected: "nope" })) as FixFrame;
        expect(fix).not.toBeNull();
        expect(Object.keys(fix).sort()).toEqual(
            ["direction", "raw", "seqNum", "timestamp", "type"].sort(),
        );
    });

    it("rejects a direction other than INBOUND", () => {
        expect(parseServerFrame(json({ ...VALID, direction: "OUTBOUND" }))).toBeNull();
        expect(parseServerFrame(json({ ...VALID, direction: undefined }))).toBeNull();
    });

    it("rejects a missing, empty or non-string raw packet", () => {
        expect(parseServerFrame(json({ ...VALID, raw: undefined }))).toBeNull();
        expect(parseServerFrame(json({ ...VALID, raw: "" }))).toBeNull();
        expect(parseServerFrame(json({ ...VALID, raw: 42 }))).toBeNull();
    });

    it("rejects a non-integer or missing seqNum and timestamp", () => {
        expect(parseServerFrame(json({ ...VALID, seqNum: undefined }))).toBeNull();
        expect(parseServerFrame(json({ ...VALID, seqNum: 1.5 }))).toBeNull();
        expect(parseServerFrame(json({ ...VALID, seqNum: "1" }))).toBeNull();
        expect(parseServerFrame(json({ ...VALID, timestamp: undefined }))).toBeNull();
        expect(parseServerFrame(json({ ...VALID, timestamp: 1.5 }))).toBeNull();
    });

    it("still rejects unknown frame types", () => {
        expect(parseServerFrame(json({ ...VALID, type: "FIXX" }))).toBeNull();
        expect(parseServerFrame("not json at all")).toBeNull();
    });
});

describe("FIX frames advance the last-frame marker and log to the inspector (P7-9)", () => {
    it("updates lastFrameNanos, prepends to inspectorLog, and touches nothing else", () => {
        const fix = parseServerFrame(json(VALID)) as FixFrame;
        const action: Action = { type: "FRAME", frame: fix };

        const after = reducer(initialState, action);

        // The state object is new (a frame arrived), but every OTHER application
        // slice keeps its reference: the FIX echo stores nothing beyond the log.
        expect(after).not.toBe(initialState);
        expect(after.lastFrameNanos).toBe(VALID.timestamp);
        expect(after.book).toBe(initialState.book);
        expect(after.tape).toBe(initialState.tape);
        expect(after.myOrders).toBe(initialState.myOrders);
        expect(after.sessionVolume).toBe(initialState.sessionVolume);
        expect(after.sessionOpenPx).toBe(initialState.sessionOpenPx);
        expect(after.msgSeqNum).toBe(initialState.msgSeqNum);

        expect(after.inspectorLog).toHaveLength(1);
        expect(after.inspectorLog[0]).toBe(fix);
    });

    it("leaves an established book and tape untouched, and logs only the FIX entry", () => {
        const withBook = reducer(initialState, {
            type: "FRAME",
            frame: {
                type: "BOOK",
                bestBid: 1500000,
                bestAsk: 1502500,
                bids: [[1500000, 10]],
                asks: [[1502500, 7]],
                timestamp: 1,
            },
        });
        // BOOK frames are never logged (P7-9 decision 1).
        expect(withBook.inspectorLog).toHaveLength(0);

        const fix = parseServerFrame(json({ ...VALID, seqNum: 2 })) as FixFrame;
        const after = reducer(withBook, { type: "FRAME", frame: fix });

        expect(after).not.toBe(withBook);
        expect(after.lastFrameNanos).toBe(VALID.timestamp);
        expect(after.book).toBe(withBook.book);
        expect(after.book.bids).toEqual([[1500000, 10]]);
        expect(after.inspectorLog).toHaveLength(1);
        expect(after.inspectorLog[0]).toBe(fix);
    });

    it("prepends multiple FIX echoes newest-first", () => {
        const first = parseServerFrame(json({ ...VALID, seqNum: 1, timestamp: 10 })) as FixFrame;
        const second = parseServerFrame(json({ ...VALID, seqNum: 2, timestamp: 20 })) as FixFrame;

        const after = [first, second].reduce(
            (state, fix) => reducer(state, { type: "FRAME", frame: fix }),
            initialState,
        );

        expect(after.inspectorLog).toEqual([second, first]);
    });
});
