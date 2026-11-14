import { describe, expect, it } from "vitest";

import {
    appendQuote,
    emptyQuoteSeries,
    foldQuoteAt,
    foldQuoteBreak,
    foldQuotes,
    emptyQuoteSet,
    NO_QUOTE,
    QUOTE_CAP,
    quoteBuckets,
} from "../src/quotes";
import type { QuotePoint } from "../src/quotes";
import { BAR_CAP, bucketOf } from "../src/bars";
import { initialState, reducer } from "../src/state/reducer";
import type { Action, AppState } from "../src/state/reducer";

const S = 1_000_000_000;
const NS5 = 5 * S;
const T0 = Math.floor(1.75e18 / (300 * S)) * (300 * S);

function book(t: number, bid: number, ask: number): Action {
    return {
        type: "FRAME",
        frame: {
            type: "BOOK",
            bestBid: bid,
            bestAsk: ask,
            bids: bid > 0 ? [[bid, 10]] : [],
            asks: ask > 0 ? [[ask, 10]] : [],
            timestamp: t,
        },
    };
}
const open: Action = { type: "CONNECTION", status: "open" };
const drop: Action = { type: "CONNECTION", status: "reconnecting" };

function run(state: AppState, ...actions: Action[]): AppState {
    return actions.reduce(reducer, state);
}

describe("quote ring in the reducer (P18-6)", () => {
    it("a change of the best bid or ask appends a point", () => {
        const s = run(initialState, open, book(T0, 1_000_000, 1_000_200), book(T0 + S, 1_000_100, 1_000_200), book(T0 + 2 * S, 1_000_100, 1_000_300));
        expect(s.quotes).toEqual([
            { t: T0, bid: 1_000_000, ask: 1_000_200 },
            { t: T0 + S, bid: 1_000_100, ask: 1_000_200 },
            { t: T0 + 2 * S, bid: 1_000_100, ask: 1_000_300 },
        ]);
    });

    it("an unchanged best quote appends nothing and keeps both references", () => {
        const s1 = run(initialState, open, book(T0, 1_000_000, 1_000_200));
        // Depth changed below the top; the tops did not.
        const s2 = reducer(s1, book(T0 + S, 1_000_000, 1_000_200));
        expect(s2.quotes).toBe(s1.quotes);
        expect(s2.quoteBars).toBe(s1.quoteBars);
        expect(s2.book).not.toBe(s1.book);
    });

    it("keeps an empty side as -1", () => {
        const s = run(initialState, open, book(T0, 1_000_000, -1));
        expect(s.quotes[0]).toEqual({ t: T0, bid: 1_000_000, ask: NO_QUOTE });
    });

    it("drops the oldest point past QUOTE_CAP", () => {
        let ring: readonly QuotePoint[] = [];
        for (let i = 0; i < QUOTE_CAP + 3; i++) ring = appendQuote(ring, { t: i, bid: 1_000_000 + i, ask: 2_000_000 });
        expect(ring).toHaveLength(QUOTE_CAP);
        expect(ring[0].t).toBe(3);
        expect(ring[ring.length - 1].t).toBe(QUOTE_CAP + 2);
    });

    it("a drop from open appends one empty point at the last frame's time, and the book is kept", () => {
        const live = run(initialState, open, book(T0, 1_000_000, 1_000_200), book(T0 + 3 * S, 1_000_100, 1_000_200));
        const dropped = reducer(live, drop);
        expect(dropped.quotes[dropped.quotes.length - 1]).toEqual({ t: T0 + 3 * S, bid: NO_QUOTE, ask: NO_QUOTE });
        expect(dropped.quotes).toHaveLength(3);
        expect(dropped.book).toBe(live.book);
        // A second status change while down adds nothing.
        const still = reducer(dropped, { type: "CONNECTION", status: "connecting" });
        expect(still.quotes).toBe(dropped.quotes);
    });

    it("appends no break with no quote yet, or when not coming from open", () => {
        expect(run(initialState, open, drop).quotes).toEqual([]);
        const s = run(initialState, book(T0, 1_000_000, 1_000_200));
        expect(reducer(s, drop).quotes).toBe(s.quotes);
    });

    it("the first quote after a reconnect resumes the line even when the tops are unchanged", () => {
        const s = run(initialState, open, book(T0, 1_000_000, 1_000_200), drop, open, book(T0 + 60 * S, 1_000_000, 1_000_200));
        expect(s.quotes.map((p) => [p.bid, p.ask])).toEqual([
            [1_000_000, 1_000_200],
            [NO_QUOTE, NO_QUOTE],
            [1_000_000, 1_000_200],
        ]);
    });

    it("a non-book frame keeps the quote references", () => {
        const s = run(initialState, open, book(T0, 1_000_000, 1_000_200));
        const after = reducer(s, {
            type: "FRAME",
            frame: { type: "FIX", direction: "INBOUND", raw: "8=FIX.4.4\u00019=5\u000135=D\u000110=000\u0001", seqNum: 1, timestamp: T0 + S },
        });
        expect(after.quotes).toBe(s.quotes);
        expect(after.quoteBars).toBe(s.quoteBars);
    });
});

describe("bucketed quote series (P18-6)", () => {
    it("a change inside the open bucket replaces only the open point and keeps the closed list", () => {
        const s1 = run(initialState, open, book(T0, 1_000_000, 1_000_200), book(T0 + 6 * S, 1_000_100, 1_000_200));
        const closedBefore = s1.quoteBars["5s"].closed;
        const s2 = reducer(s1, book(T0 + 7 * S, 1_000_100, 1_000_300));
        expect(s2.quoteBars["5s"].closed).toBe(closedBefore);
        expect(s2.quoteBars["5s"].open).toEqual({ bucket: bucketOf(T0 + 7 * S, NS5), bid: 1_000_100, ask: 1_000_300 });
    });

    it("a later bucket closes the open one; every size folds every change", () => {
        const s = run(initialState, open, book(T0 + S, 1_000_000, 1_000_200), book(T0 + 2 * S, 1_000_100, 1_000_200), book(T0 + 6 * S, 1_000_100, 1_000_300));
        expect(quoteBuckets(s.quoteBars["5s"])).toEqual([
            { bucket: bucketOf(T0, NS5), bid: 1_000_100, ask: 1_000_200 },
            { bucket: bucketOf(T0 + 6 * S, NS5), bid: 1_000_100, ask: 1_000_300 },
        ]);
        // At 15s, 1m and 5m all three changes share one bucket: its last quote wins.
        for (const id of ["15s", "1m", "5m"] as const) {
            expect(s.quoteBars[id].closed).toHaveLength(0);
            expect(s.quoteBars[id].open).toMatchObject({ bid: 1_000_100, ask: 1_000_300 });
        }
    });

    it("a run of unchanged buckets adds no entries (the drawing carries the quote forward)", () => {
        const s = run(initialState, open, book(T0 + S, 1_000_000, 1_000_200), book(T0 + 40 * S, 1_000_000, 1_000_200), book(T0 + 61 * S, 1_000_100, 1_000_200));
        const buckets = quoteBuckets(s.quoteBars["5s"]);
        expect(buckets.map((b) => b.bucket - bucketOf(T0, NS5))).toEqual([0, 12]);
    });

    it("the disconnect marker empties the bucket after the last frame, keeping that bucket's quote", () => {
        const s = run(initialState, open, book(T0 + S, 1_000_000, 1_000_200), drop);
        expect(quoteBuckets(s.quoteBars["5s"])).toEqual([
            { bucket: bucketOf(T0, NS5), bid: 1_000_000, ask: 1_000_200 },
            { bucket: bucketOf(T0, NS5) + 1, bid: NO_QUOTE, ask: NO_QUOTE },
        ]);
        const set = foldQuoteBreak(emptyQuoteSet(), T0);
        expect(set["1m"].open).toEqual({ bucket: bucketOf(T0, 60 * S) + 1, bid: NO_QUOTE, ask: NO_QUOTE });
    });

    it("caps the closed list at BAR_CAP, and folds an older bucket into the open one", () => {
        let series = emptyQuoteSeries();
        for (let b = 0; b < BAR_CAP + 3; b++) series = foldQuoteAt(series, b, 1_000_000 + b, 2_000_000);
        expect(series.closed).toHaveLength(BAR_CAP);
        expect(series.closed[0].bucket).toBe(2);
        const older = foldQuoteAt(series, 5, 1, 2);
        expect(older.open).toEqual({ bucket: BAR_CAP + 2, bid: 1, ask: 2 });
        expect(older.closed).toBe(series.closed);
    });

    it("foldQuotes on the empty set opens one bucket per size", () => {
        const set = foldQuotes(emptyQuoteSet(), T0, 10, 20);
        expect(set["5s"].open).toEqual({ bucket: bucketOf(T0, NS5), bid: 10, ask: 20 });
        expect(set["5m"].open).toEqual({ bucket: bucketOf(T0, 300 * S), bid: 10, ask: 20 });
    });
});
