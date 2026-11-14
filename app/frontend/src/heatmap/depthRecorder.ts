/**
 * The depth recorder (P20-1, P20-0 F3): the one DepthRing of a browser session, fed
 * by the socket edge (useOrderBook) rather than the reducer. The reducer is pure and
 * StrictMode calls it twice, so a ring written in place cannot live in its state.
 *
 * Frames are recorded on their venue timestamps (depthRing.ts). The only timer is
 * advance(), which moves the right edge through quiet stretches (P20-0 measured gaps
 * of up to 4 s with no BOOK frame). It estimates venue time as wall time minus the
 * smallest wall-minus-venue lag seen since the socket opened: venue timestamps
 * advance at wall speed, also under the bots' --speed 2 (P20-1 measurement: rate
 * 1.00, lag p50 3.9 ms), and the smallest lag discards the stale timestamp of the
 * snapshot a connecting client is sent. It closes buckets only up to that estimate
 * minus one bucket, so a frame in flight still lands in its own bucket, and it does
 * nothing before the first frame. A background tab throttles the timer, never the
 * socket, so a late tick only delays the right edge; the next frame fills exactly.
 *
 * Consumers subscribe for a version number (useSyncExternalStore); every change of
 * the ring bumps it.
 */

import { HEATMAP_TICK_MS, advanceTo, bucketOfNs, createDepthRing, ingestBook, recordBreak } from "./depthRing";
import type { DepthRing } from "./depthRing";
import type { BookFrame } from "../protocol/messages";

export { HEATMAP_TICK_MS };

export class DepthRecorder {
    readonly ring: DepthRing;
    private version = 0;
    private readonly listeners = new Set<() => void>();
    /** Smallest wall-minus-venue lag since the socket opened, in ns; Infinity before a frame. */
    private lagNs = Infinity;
    /** The next frame is the first after a (re)connect: the venue's cached snapshot. */
    private opening = true;
    /** The lag the timer uses: this connection's, else the last connection's. */
    private estimateLagNs = Infinity;

    constructor(ring: DepthRing = createDepthRing()) {
        this.ring = ring;
    }

    /** One BOOK frame, received at wall time `wallNs` (epoch ns). */
    ingest(frame: BookFrame, wallNs: number): void {
        const lag = wallNs - frame.timestamp;
        if (lag < this.lagNs) this.lagNs = lag;
        this.estimateLagNs = this.lagNs;
        ingestBook(this.ring, frame, this.opening);
        this.opening = false;
        this.bump();
    }

    /** The socket opened: the lag estimate restarts and the next frame opens a bucket. */
    connected(): void {
        this.lagNs = Infinity;
        this.opening = true;
    }

    /** The socket dropped: one break column, then empty columns until the next frame. */
    disconnected(): void {
        const before = this.ring.head;
        recordBreak(this.ring);
        this.opening = true;
        if (this.ring.head !== before) this.bump();
    }

    /**
     * The timer tick: close buckets up to the estimated venue bucket minus one. Before
     * any frame of this connection (no lag yet) it keeps the estimate of the previous
     * one, so a disconnected gap still grows; before any frame at all it does nothing.
     */
    advance(wallNs: number): void {
        if (!Number.isFinite(this.estimateLagNs)) return;
        const target = bucketOfNs(wallNs - this.estimateLagNs) - 1;
        if (advanceTo(this.ring, target)) this.bump();
    }

    subscribe = (listener: () => void): (() => void) => {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    };

    getVersion = (): number => this.version;

    private bump(): void {
        this.version++;
        for (const l of this.listeners) l();
    }
}
