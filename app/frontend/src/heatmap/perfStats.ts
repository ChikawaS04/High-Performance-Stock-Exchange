/**
 * Dev-only performance collection for the heatmap (P20-6, P20 D7 with greenlight
 * amendment 7). Three series, read from the browser console or the preview tools as
 * window.__hpseHeatmap:
 *
 *  - paints: each HeatmapLayer paint's duration (renderer work only), with its mode;
 *  - longTasks: every main-thread task over 50 ms (PerformanceObserver "longtask"),
 *    which catches React re-renders and anything else, not only the canvas;
 *  - commits: PriceChart's React commit time (actualDuration from a Profiler, which
 *    React reports only in development builds).
 *
 * Plus the depth recorder, for its late-frame `dropped` counter (amendment 2).
 * Nothing here exists in a production build: heatPerf is null unless import.meta.env.DEV.
 */

import type { ProfilerOnRenderCallback } from "react";

import type { DepthRecorder } from "./depthRecorder";
import type { PaintMode } from "./renderer";

const CAP = 20_000;

export interface Series {
    readonly values: Float64Array;
    count: number;
}

function series(): Series {
    return { values: new Float64Array(CAP), count: 0 };
}

function push(s: Series, v: number): void {
    s.values[s.count % CAP] = v;
    s.count++;
}

function held(s: Series): number[] {
    const n = Math.min(s.count, CAP);
    return Array.from(s.values.subarray(0, n));
}

export interface Summary {
    readonly n: number;
    readonly p50: number;
    readonly p95: number;
    readonly p99: number;
    readonly max: number;
    readonly over16: number;
}

export function summarize(values: readonly number[]): Summary {
    const v = [...values].sort((a, b) => a - b);
    const q = (p: number): number => (v.length === 0 ? 0 : v[Math.min(v.length - 1, Math.floor(p * v.length))]);
    return { n: v.length, p50: q(0.5), p95: q(0.95), p99: q(0.99), max: q(1), over16: v.filter((x) => x > 16).length };
}

export interface HeatPerf {
    readonly paints: Series;
    readonly fullPaints: Series;
    readonly longTasks: Series;
    readonly commits: Series;
    depth: DepthRecorder | null;
    readonly longTaskSupported: boolean;
    reset(): void;
    summary(): Record<string, Summary | number | boolean>;
}

function create(): HeatPerf {
    const perf: HeatPerf = {
        paints: series(),
        fullPaints: series(),
        longTasks: series(),
        commits: series(),
        depth: null,
        longTaskSupported: false,
        reset() {
            perf.paints.count = 0;
            perf.fullPaints.count = 0;
            perf.longTasks.count = 0;
            perf.commits.count = 0;
        },
        summary() {
            return {
                paint: summarize(held(perf.paints)),
                fullPaint: summarize(held(perf.fullPaints)),
                longTask: summarize(held(perf.longTasks)),
                commit: summarize(held(perf.commits)),
                dropped: perf.depth?.ring.dropped ?? 0,
                columns: perf.depth?.ring.count ?? 0,
                longTaskSupported: perf.longTaskSupported,
            };
        },
    };
    let supported = false;
    if (typeof PerformanceObserver !== "undefined" && PerformanceObserver.supportedEntryTypes?.includes("longtask")) {
        new PerformanceObserver((list) => {
            for (const e of list.getEntries()) push(perf.longTasks, e.duration);
        }).observe({ type: "longtask", buffered: false });
        supported = true;
    }
    (perf as { longTaskSupported: boolean }).longTaskSupported = supported;
    return perf;
}

export const heatPerf: HeatPerf | null = import.meta.env.DEV && typeof window !== "undefined" ? create() : null;

if (heatPerf !== null) (window as unknown as { __hpseHeatmap: HeatPerf }).__hpseHeatmap = heatPerf;

/** HeatmapLayer's onPaint in development; undefined in production. */
export const recordPaint: ((stats: { readonly mode: PaintMode }, ms: number) => void) | undefined =
    heatPerf === null
        ? undefined
        : (stats, ms) => {
              push(heatPerf.paints, ms);
              if (stats.mode === "full") push(heatPerf.fullPaints, ms);
          };

/** The React Profiler callback for PriceChart in development. */
export const recordCommit: ProfilerOnRenderCallback | undefined =
    heatPerf === null ? undefined : (_id, _phase, actualDuration) => push(heatPerf.commits, actualDuration);

/** Expose the recorder for its dropped counter. */
export function attachDepth(rec: DepthRecorder): void {
    if (heatPerf !== null) heatPerf.depth = rec;
}
