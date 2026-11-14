import { describe, expect, it } from "vitest";

import {
    COL_BID_TRUNC,
    COL_BOOK,
    COL_BREAK,
    HEATMAP_BAND_TICKS,
    HEATMAP_COLS,
    HEATMAP_MAX_LEVELS,
    HEATMAP_STRIDE,
    HEATMAP_TICK_NS,
    advanceTo,
    anchorOf,
    bucketOfNs,
    createDepthRing,
    ingestBook,
    oldestBucket,
    recordBreak,
    sizeAt,
    slotFor,
} from "../src/heatmap/depthRing";
import type { DepthRing } from "../src/heatmap/depthRing";
import { DepthRecorder } from "../src/heatmap/depthRecorder";
import { parseServerFrame } from "../src/protocol/messages";
import type { BookFrame, Level } from "../src/protocol/messages";

const T = 100; // one tick in units
const B0 = 7_000_000_000; // a bucket index near today's (epoch ns / 250 ms)
const tAt = (bucket: number, offsetMs = 10): number => bucket * HEATMAP_TICK_NS + offsetMs * 1_000_000;

function book(t: number, bids: readonly Level[], asks: readonly Level[]): BookFrame {
    return {
        type: "BOOK",
        bestBid: bids.length > 0 ? bids[0][0] : -1,
        bestAsk: asks.length > 0 ? asks[0][0] : -1,
        bids,
        asks,
        timestamp: t,
    };
}

const PX = 1_500_000; // $150.00
const simple = (t: number, bidQty = 100): BookFrame => book(t, [[PX - T, bidQty]], [[PX + T, 200]]);

function levels(ring: DepthRing, bucket: number): { bids: [number, number][]; asks: [number, number][] } {
    const slot = slotFor(ring, bucket);
    const base = slot * HEATMAP_STRIDE;
    const bids: [number, number][] = [];
    const asks: [number, number][] = [];
    for (let i = 0; i < ring.bidCount[slot]; i++) bids.push([ring.offsets[base + i], ring.sizes[base + i]]);
    for (let i = 0; i < ring.askCount[slot]; i++) {
        const k = base + HEATMAP_MAX_LEVELS + i;
        asks.push([ring.offsets[k], ring.sizes[k]]);
    }
    return { bids, asks };
}

describe("depthRing columns", () => {
    it("stores one column per tick, keyed by the integer bucket", () => {
        const r = createDepthRing();
        ingestBook(r, simple(tAt(B0)));
        ingestBook(r, simple(tAt(B0 + 1), 110));
        ingestBook(r, simple(tAt(B0 + 2), 120));
        expect(r.count).toBe(3);
        expect(r.head).toBe(B0 + 2);
        expect(oldestBucket(r)).toBe(B0);
        expect(r.bucket[slotFor(r, B0 + 1)]).toBe(B0 + 1);
        expect(Number.isInteger(r.bucket[slotFor(r, B0 + 2)])).toBe(true);
        expect(levels(r, B0 + 1).bids).toEqual([[-1, 110]]);
    });

    it("keeps the last frame in a bucket", () => {
        const r = createDepthRing();
        ingestBook(r, simple(tAt(B0, 10), 100));
        expect(ingestBook(r, simple(tAt(B0, 200), 300))).toBe("overwrote");
        expect(r.count).toBe(1);
        expect(levels(r, B0).bids).toEqual([[-1, 300]]);
    });

    it("fills skipped buckets with the book before the frame", () => {
        const r = createDepthRing();
        ingestBook(r, simple(tAt(B0), 100));
        ingestBook(r, simple(tAt(B0 + 4), 500));
        expect(r.count).toBe(5);
        for (let b = B0; b <= B0 + 3; b++) expect(levels(r, b).bids).toEqual([[-1, 100]]);
        expect(levels(r, B0 + 4).bids).toEqual([[-1, 500]]);
    });

    it("crops levels beyond the band", () => {
        const r = createDepthRing();
        const anchor = anchorOf(PX - T, PX + T);
        const f = book(
            tAt(B0),
            [
                [PX - T, 10],
                [anchor - HEATMAP_BAND_TICKS * T, 20],
                [anchor - (HEATMAP_BAND_TICKS + 1) * T, 30],
            ],
            [[PX + T, 40]],
        );
        ingestBook(r, f);
        expect(levels(r, B0).bids).toEqual([
            [-1, 10],
            [-HEATMAP_BAND_TICKS, 20],
        ]);
    });

    it("anchors a half-tick mid on the bid-side tick and a one-sided book on its best", () => {
        expect(anchorOf(PX, PX + T)).toBe(PX);
        expect(anchorOf(PX, PX + 2 * T)).toBe(PX + T);
        expect(anchorOf(PX, -1)).toBe(PX);
        expect(anchorOf(-1, PX)).toBe(PX);
        expect(anchorOf(-1, -1)).toBe(-1);
        const r = createDepthRing();
        ingestBook(r, book(tAt(B0), [[PX, 5]], [[PX + T, 6]]));
        expect(levels(r, B0)).toEqual({ bids: [[0, 5]], asks: [[1, 6]] });
    });

    it("stores nothing for an empty side", () => {
        const r = createDepthRing();
        ingestBook(r, book(tAt(B0), [], [[PX + T, 7]]));
        expect(levels(r, B0)).toEqual({ bids: [], asks: [[0, 7]] });
        expect(sizeAt(r, B0, PX - 5 * T)).toBe(0);
    });

    it("drops the oldest column at the cap", () => {
        const r = createDepthRing();
        for (let i = 0; i <= HEATMAP_COLS; i++) ingestBook(r, simple(tAt(B0 + i), i + 1));
        expect(r.count).toBe(HEATMAP_COLS);
        expect(oldestBucket(r)).toBe(B0 + 1);
        expect(slotFor(r, B0)).toBe(-1);
        expect(levels(r, B0 + 1).bids).toEqual([[-1, 2]]);
        expect(levels(r, B0 + HEATMAP_COLS).bids).toEqual([[-1, HEATMAP_COLS + 1]]);
    });

    it("writes at most one ring of columns across a gap longer than the ring", () => {
        const r = createDepthRing();
        ingestBook(r, simple(tAt(B0)));
        ingestBook(r, simple(tAt(B0 + 3 * HEATMAP_COLS), 9));
        expect(r.count).toBe(HEATMAP_COLS);
        expect(r.head).toBe(B0 + 3 * HEATMAP_COLS);
        expect(oldestBucket(r)).toBe(B0 + 2 * HEATMAP_COLS + 1);
    });

    it("reuses its typed arrays", () => {
        const r = createDepthRing();
        const refs = [r.bucket, r.anchor, r.flags, r.bidCount, r.askCount, r.offsets, r.sizes, r.stage.offsets, r.stage.sizes];
        for (let i = 0; i < 3 * HEATMAP_COLS; i++) ingestBook(r, simple(tAt(B0 + i)));
        expect([r.bucket, r.anchor, r.flags, r.bidCount, r.askCount, r.offsets, r.sizes, r.stage.offsets, r.stage.sizes]).toEqual(refs);
        refs.forEach((a, i) => expect(a).toBe([r.bucket, r.anchor, r.flags, r.bidCount, r.askCount, r.offsets, r.sizes, r.stage.offsets, r.stage.sizes][i]));
    });
});

describe("depthRing truncation", () => {
    it("flags a side with the full level count and reads beyond it as unknown", () => {
        const r = createDepthRing();
        const bids: Level[] = [];
        for (let i = 0; i < HEATMAP_MAX_LEVELS; i++) bids.push([PX - (i + 1) * T, 10]);
        ingestBook(r, book(tAt(B0), bids, [[PX + T, 5]]));
        const slot = slotFor(r, B0);
        expect(r.flags[slot] & COL_BID_TRUNC).toBe(COL_BID_TRUNC);
        // Deepest listed bid is 20 ticks below; inside it an unlisted tick would be 0, past it unknown.
        expect(sizeAt(r, B0, PX - 20 * T)).toBe(10);
        expect(sizeAt(r, B0, PX - 21 * T)).toBeNull();
        // The ask side has one level and is complete: far asks are empty, not unknown.
        expect(sizeAt(r, B0, PX + 30 * T)).toBe(0);
    });
});

describe("depthRing breaks", () => {
    it("stores an empty break column on disconnect and fills nothing forward", () => {
        const r = createDepthRing();
        ingestBook(r, simple(tAt(B0)));
        recordBreak(r);
        const brk = slotFor(r, B0 + 1);
        expect(r.flags[brk]).toBe(COL_BREAK);
        expect(r.bidCount[brk] + r.askCount[brk]).toBe(0);
        advanceTo(r, B0 + 4);
        for (let b = B0 + 2; b <= B0 + 4; b++) {
            const s = slotFor(r, b);
            expect(r.flags[s] & COL_BOOK).toBe(0);
            expect(sizeAt(r, b, PX - T)).toBeNull();
        }
    });

    it("opens the next bucket with a stale snapshot after a reconnect", () => {
        const r = createDepthRing();
        ingestBook(r, simple(tAt(B0)));
        recordBreak(r);
        advanceTo(r, B0 + 5);
        // The cached snapshot carries the time the book last changed: bucket B0.
        expect(ingestBook(r, simple(tAt(B0), 77), true)).toBe("opened");
        expect(r.head).toBe(B0 + 6);
        expect(levels(r, B0 + 6).bids).toEqual([[-1, 77]]);
        expect(r.dropped).toBe(0);
    });
});

describe("depthRing late frames (amendment 2)", () => {
    it("lands a late frame for the newest closed column", () => {
        const r = createDepthRing();
        ingestBook(r, simple(tAt(B0), 100));
        advanceTo(r, B0 + 3); // the timer closed B0+1 .. B0+3 with the held book
        expect(ingestBook(r, simple(tAt(B0 + 3, 240), 300))).toBe("overwrote");
        expect(levels(r, B0 + 3).bids).toEqual([[-1, 300]]);
        expect(r.dropped).toBe(0);
    });

    it("drops and counts an older frame, but keeps its book for the next fill", () => {
        const r = createDepthRing();
        ingestBook(r, simple(tAt(B0), 100));
        advanceTo(r, B0 + 3);
        expect(ingestBook(r, simple(tAt(B0 + 1), 400))).toBe("dropped");
        expect(r.dropped).toBe(1);
        expect(levels(r, B0 + 1).bids).toEqual([[-1, 100]]);
        advanceTo(r, B0 + 4);
        expect(levels(r, B0 + 4).bids).toEqual([[-1, 400]]);
    });

    it("does not advance before the first frame", () => {
        const r = createDepthRing();
        expect(advanceTo(r, B0)).toBe(false);
        expect(r.count).toBe(0);
    });
});

describe("DepthRecorder", () => {
    const wall = (bucket: number, ms: number): number => bucket * HEATMAP_TICK_NS + ms * 1_000_000;

    it("lands a frame in flight within the one-bucket grace", () => {
        const rec = new DepthRecorder();
        rec.connected();
        rec.ingest(simple(tAt(B0, 0), 100), wall(B0, 4)); // lag 4 ms
        // Wall time is 30 ms into B0+3: the timer closes up to B0+2 only.
        rec.advance(wall(B0 + 3, 30));
        expect(rec.ring.head).toBe(B0 + 2);
        // A frame stamped late in B0+2 is still in flight; it lands.
        rec.ingest(simple(tAt(B0 + 2, 245), 300), wall(B0 + 3, 40));
        expect(rec.ring.dropped).toBe(0);
        expect(levels(rec.ring, B0 + 2).bids).toEqual([[-1, 300]]);
    });

    it("estimates venue time from the smallest lag, ignoring a stale snapshot", () => {
        const rec = new DepthRecorder();
        rec.connected();
        rec.ingest(simple(tAt(B0, 0)), wall(B0 + 2, 100)); // snapshot: 600 ms stale
        rec.ingest(simple(tAt(B0 + 4, 0), 50), wall(B0 + 4, 3)); // live: 3 ms lag
        rec.advance(wall(B0 + 8, 10));
        expect(rec.ring.head).toBe(B0 + 7);
    });

    it("does nothing on the timer before any frame, and bumps the version on changes", () => {
        const rec = new DepthRecorder();
        let calls = 0;
        const unsub = rec.subscribe(() => calls++);
        rec.advance(wall(B0, 0));
        expect(rec.getVersion()).toBe(0);
        rec.ingest(simple(tAt(B0)), wall(B0, 20));
        rec.advance(wall(B0 + 5, 30));
        rec.disconnected();
        expect(calls).toBe(3);
        expect(rec.getVersion()).toBe(3);
        unsub();
        rec.advance(wall(B0 + 9, 30));
        expect(calls).toBe(3);
    });

    it("records a raw BOOK frame from wire text", () => {
        const text = JSON.stringify({
            type: "BOOK",
            bestBid: 1_498_400,
            bestAsk: 1_498_600,
            bids: [
                [1_498_400, 50],
                [1_498_200, 130],
            ],
            asks: [[1_498_600, 100]],
            lastSeq: 100,
            timestamp: tAt(B0),
        });
        const frame = parseServerFrame(text);
        expect(frame?.type).toBe("BOOK");
        const rec = new DepthRecorder();
        rec.ingest(frame as BookFrame, tAt(B0) + 2_000_000);
        expect(bucketOfNs(tAt(B0))).toBe(B0);
        expect(levels(rec.ring, B0)).toEqual({
            bids: [
                [-1, 50],
                [-3, 130],
            ],
            asks: [[1, 100]],
        });
        expect(sizeAt(rec.ring, B0, 1_498_200)).toBe(130);
        expect(sizeAt(rec.ring, B0, 1_498_300)).toBe(0);
    });
});
