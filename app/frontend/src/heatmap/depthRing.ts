/**
 * Depth history for the liquidity heatmap (P20-1, P20 D1 as amended at P20-0).
 *
 * The book is recorded as one column per clock-aligned bucket of HEATMAP_TICK_MS on
 * the venue's own timestamps, not on a client timer: bucket = floor(t / 250 ms) of
 * each BOOK frame's epoch-nanosecond timestamp. A BOOK frame is a full snapshot of
 * at most HEATMAP_MAX_LEVELS populated levels a side, published after every inbound
 * command and nothing else (P20-0 F2), so between two frames the book does not
 * change. A frame that lands in a later bucket therefore fills every skipped bucket
 * with the book before it, which is exact. The last frame in a bucket wins.
 *
 * Storage is a ring of HEATMAP_COLS columns in preallocated typed arrays, allocated
 * once and never reallocated. Column time is the integer bucket index (P20-0
 * amendment 2): epoch nanoseconds exceed 2^53, a bucket index (about 7e9) does not.
 * Each column holds up to HEATMAP_MAX_LEVELS bids then as many asks, each level an
 * Int16 tick offset from the column's anchor and a Uint32 resting size, so the ring's
 * memory is fixed (about 0.62 MB). The anchor is the mid floored to the tick (a
 * half-tick mid floors to the bid side), the one best when a side is empty. A level
 * more than HEATMAP_BAND_TICKS from the anchor is cropped. An empty side stores
 * nothing. A side that carries the full HEATMAP_MAX_LEVELS levels is flagged
 * truncated: beyond its deepest level the size is unknown ("beyond depth"); a side
 * with fewer levels is complete, so an unlisted tick has no liquidity.
 *
 * The latest book is kept in a staging column. A column is written by copying the
 * staging column, so a fill-forward is a typed-array copy, never a re-parse.
 *
 * Breaks. A disconnect stores one empty column flagged as a break and clears the
 * staging book: until the next BOOK frame, later columns are empty, never filled
 * forward, so the outage shows as a gap. The first frame after a (re)connect is the
 * venue's cached snapshot, whose timestamp is when the book last changed and can be
 * older than columns already closed; it opens the next bucket (`opening`).
 *
 * Late frames (amendment 2). The recorder's timer closes buckets only up to the
 * estimated venue time minus one bucket of grace (advanceTo). A frame for the newest
 * column, closed or not, overwrites it. A frame for an older bucket is dropped and
 * counted in `dropped`; its book still becomes the staging book, because BOOK frames
 * arrive in order, so it is the latest book and the next fill-forward must use it.
 *
 * Pure in the sense the reducer is not: these functions mutate the ring passed in and
 * nothing else. No React, no clock. The recorder (depthRecorder.ts) owns the instance.
 */

import { TICK_PX } from "../prices";
import type { BookFrame, Level } from "../protocol/messages";

/** Sampling interval: one column per bucket. */
export const HEATMAP_TICK_MS = 250;
export const HEATMAP_TICK_NS = HEATMAP_TICK_MS * 1_000_000;
/** Columns kept: 10 minutes at 250 ms. */
export const HEATMAP_COLS = 2400;
/** Ticks either side of the anchor kept per column. */
export const HEATMAP_BAND_TICKS = 200;
/** Populated levels per side in a BOOK frame (the server's BookSnapshotEvent.MAX_DEPTH_LEVELS). */
export const HEATMAP_MAX_LEVELS = 20;
/** Level slots per column: the bids, then the asks. */
export const HEATMAP_STRIDE = HEATMAP_MAX_LEVELS * 2;

/** The column holds a recorded book (possibly with both sides empty). */
export const COL_BOOK = 1;
/** The column is the empty column a disconnect stores. */
export const COL_BREAK = 2;
/** The side carried HEATMAP_MAX_LEVELS levels: past its deepest level is unknown. */
export const COL_BID_TRUNC = 4;
export const COL_ASK_TRUNC = 8;

const UINT32_MAX = 0xffffffff;

export interface DepthRing {
    readonly cols: number;
    /** Bucket index per slot. */
    readonly bucket: Float64Array;
    /** Anchor price in units per slot; -1 when both sides are empty. */
    readonly anchor: Float64Array;
    readonly flags: Uint8Array;
    readonly bidCount: Uint8Array;
    readonly askCount: Uint8Array;
    /** Tick offsets from the anchor, HEATMAP_STRIDE per slot: bids at [0, 20), asks at [20, 40). */
    readonly offsets: Int16Array;
    /** Resting sizes, parallel to offsets. */
    readonly sizes: Uint32Array;
    /** The latest book, encoded as one column (slot 0 of these arrays). */
    readonly stage: {
        readonly offsets: Int16Array;
        readonly sizes: Uint32Array;
        anchor: number;
        flags: number;
        bidCount: number;
        askCount: number;
    };
    /** Newest bucket held, or -1 when the ring is empty. */
    head: number;
    /** Columns held, at most cols. */
    count: number;
    /** The staging column holds a book (false before the first frame and after a break). */
    hasBook: boolean;
    /** Late frames dropped (amendment 2); read only by dev tooling. */
    dropped: number;
    /**
     * Column writes so far (P20-3). A painter that knows the head it last painted can
     * tell new columns from a rewrite of that head: more writes than new columns means
     * the old head changed too.
     */
    writes: number;
}

export function createDepthRing(cols: number = HEATMAP_COLS): DepthRing {
    return {
        cols,
        bucket: new Float64Array(cols),
        anchor: new Float64Array(cols),
        flags: new Uint8Array(cols),
        bidCount: new Uint8Array(cols),
        askCount: new Uint8Array(cols),
        offsets: new Int16Array(cols * HEATMAP_STRIDE),
        sizes: new Uint32Array(cols * HEATMAP_STRIDE),
        stage: {
            offsets: new Int16Array(HEATMAP_STRIDE),
            sizes: new Uint32Array(HEATMAP_STRIDE),
            anchor: -1,
            flags: 0,
            bidCount: 0,
            askCount: 0,
        },
        head: -1,
        count: 0,
        hasBook: false,
        dropped: 0,
        writes: 0,
    };
}

/** The bucket of an epoch-nanosecond timestamp. */
export function bucketOfNs(t: number): number {
    return Math.floor(t / HEATMAP_TICK_NS);
}

/** Epoch nanoseconds at the start of a bucket. */
export function bucketStartNs(bucket: number): number {
    return bucket * HEATMAP_TICK_NS;
}

/** The anchor of a book in units: the mid floored to the tick, else the one best, else -1. */
export function anchorOf(bestBid: number, bestAsk: number): number {
    if (bestBid > 0 && bestAsk > 0) return Math.floor((bestBid + bestAsk) / 2 / TICK_PX) * TICK_PX;
    if (bestBid > 0) return bestBid;
    if (bestAsk > 0) return bestAsk;
    return -1;
}

function encodeSide(levels: readonly Level[], anchor: number, offsets: Int16Array, sizes: Uint32Array, base: number): number {
    let n = 0;
    const max = Math.min(levels.length, HEATMAP_MAX_LEVELS);
    for (let i = 0; i < max; i++) {
        const [px, qty] = levels[i];
        const off = Math.round((px - anchor) / TICK_PX);
        if (off < -HEATMAP_BAND_TICKS || off > HEATMAP_BAND_TICKS || !(qty > 0)) continue;
        offsets[base + n] = off;
        sizes[base + n] = Math.min(UINT32_MAX, qty);
        n++;
    }
    return n;
}

/** Encode a BOOK frame into the staging column. */
export function stageBook(ring: DepthRing, frame: BookFrame): void {
    const s = ring.stage;
    const anchor = anchorOf(frame.bestBid, frame.bestAsk);
    s.anchor = anchor;
    let flags = COL_BOOK;
    if (anchor < 0) {
        s.bidCount = 0;
        s.askCount = 0;
    } else {
        s.bidCount = encodeSide(frame.bids, anchor, s.offsets, s.sizes, 0);
        s.askCount = encodeSide(frame.asks, anchor, s.offsets, s.sizes, HEATMAP_MAX_LEVELS);
        if (frame.bids.length >= HEATMAP_MAX_LEVELS) flags |= COL_BID_TRUNC;
        if (frame.asks.length >= HEATMAP_MAX_LEVELS) flags |= COL_ASK_TRUNC;
    }
    s.flags = flags;
    ring.hasBook = true;
}

/** The slot a bucket occupies. */
function slotOf(ring: DepthRing, bucket: number): number {
    return ((bucket % ring.cols) + ring.cols) % ring.cols;
}

/** Write one column at `bucket`: the staging book, or an empty column (flags given). */
function writeColumn(ring: DepthRing, bucket: number, fromStage: boolean, emptyFlags = 0): void {
    const slot = slotOf(ring, bucket);
    ring.writes++;
    ring.bucket[slot] = bucket;
    if (fromStage && ring.hasBook) {
        const s = ring.stage;
        ring.anchor[slot] = s.anchor;
        ring.flags[slot] = s.flags;
        ring.bidCount[slot] = s.bidCount;
        ring.askCount[slot] = s.askCount;
        const base = slot * HEATMAP_STRIDE;
        ring.offsets.set(s.offsets, base);
        ring.sizes.set(s.sizes, base);
    } else {
        ring.anchor[slot] = -1;
        ring.flags[slot] = emptyFlags;
        ring.bidCount[slot] = 0;
        ring.askCount[slot] = 0;
    }
}

/**
 * Extend the ring so its newest column is `target`: each new bucket gets the staging
 * book (or an empty column while there is none). Only the newest `cols` buckets are
 * written, so a gap longer than the ring writes at most one ring of columns.
 */
function extendTo(ring: DepthRing, target: number): void {
    if (ring.head < 0) {
        ring.head = target - 1;
    }
    if (target <= ring.head) return;
    const from = Math.max(ring.head + 1, target - ring.cols + 1);
    for (let b = from; b <= target; b++) writeColumn(ring, b, true);
    ring.count = Math.min(ring.cols, ring.count + (target - ring.head));
    ring.head = target;
}

export type IngestResult = "opened" | "overwrote" | "dropped";

/**
 * Apply one BOOK frame. `opening` marks the first frame after a (re)connect (the
 * venue's cached snapshot, see the module comment).
 */
export function ingestBook(ring: DepthRing, frame: BookFrame, opening = false): IngestResult {
    let b = bucketOfNs(frame.timestamp);
    if (opening && ring.head >= 0 && b <= ring.head) b = ring.head + 1;
    if (ring.head < 0 || b > ring.head) {
        // The skipped buckets held the previous book (or nothing after a break).
        if (ring.head >= 0) extendTo(ring, b - 1);
        stageBook(ring, frame);
        extendTo(ring, b);
        return "opened";
    }
    stageBook(ring, frame);
    if (b === ring.head) {
        writeColumn(ring, b, true);
        return "overwrote";
    }
    ring.dropped++;
    return "dropped";
}

/** A disconnect: one empty break column, and no book until the next frame. */
export function recordBreak(ring: DepthRing): void {
    ring.hasBook = false;
    if (ring.head < 0) return;
    const b = ring.head + 1;
    writeColumn(ring, b, false, COL_BREAK);
    ring.count = Math.min(ring.cols, ring.count + 1);
    ring.head = b;
}

/**
 * Close buckets up to `target` (the timer's estimated venue bucket minus one bucket
 * of grace): fills forward with the staging book, or empty columns after a break.
 * Does nothing before the first frame. Returns true when columns were added.
 */
export function advanceTo(ring: DepthRing, target: number): boolean {
    if (ring.head < 0 || target <= ring.head) return false;
    extendTo(ring, target);
    return true;
}

/** Oldest bucket held, or -1 when empty. */
export function oldestBucket(ring: DepthRing): number {
    return ring.count === 0 ? -1 : ring.head - ring.count + 1;
}

/** The slot holding `bucket`, or -1 when the ring does not hold it. */
export function slotFor(ring: DepthRing, bucket: number): number {
    if (ring.count === 0 || bucket > ring.head || bucket < ring.head - ring.count + 1) return -1;
    return slotOf(ring, bucket);
}

/**
 * The resting size at `pricePx` in the column holding `bucket`: the size, 0 for no
 * liquidity, or null when the column has no book or the price is beyond a truncated
 * side's deepest level ("beyond depth").
 */
export function sizeAt(ring: DepthRing, bucket: number, pricePx: number): number | null {
    const slot = slotFor(ring, bucket);
    if (slot < 0 || (ring.flags[slot] & COL_BOOK) === 0) return null;
    const anchor = ring.anchor[slot];
    if (anchor < 0) return 0;
    const off = Math.round((pricePx - anchor) / TICK_PX);
    const base = slot * HEATMAP_STRIDE;
    const nb = ring.bidCount[slot];
    const na = ring.askCount[slot];
    for (let i = 0; i < nb; i++) if (ring.offsets[base + i] === off) return ring.sizes[base + i];
    for (let i = 0; i < na; i++) {
        const k = base + HEATMAP_MAX_LEVELS + i;
        if (ring.offsets[k] === off) return ring.sizes[k];
    }
    const flags = ring.flags[slot];
    if ((flags & COL_BID_TRUNC) !== 0 && nb > 0 && off < ring.offsets[base + nb - 1]) return null;
    if ((flags & COL_ASK_TRUNC) !== 0 && na > 0 && off > ring.offsets[base + HEATMAP_MAX_LEVELS + na - 1]) return null;
    return 0;
}
