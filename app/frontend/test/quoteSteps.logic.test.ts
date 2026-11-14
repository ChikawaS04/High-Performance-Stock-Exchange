import { describe, expect, it } from "vitest";

import { candleQuoteSteps, lineQuoteSteps, stepRuns } from "../src/chartOverlays";
import type { QuoteBucket, QuotePoint } from "../src/quotes";

// P18-7: bid/ask step geometry. Pure.

describe("stepRuns (P18 D7)", () => {
    it("holds each value until the next change, the last one to the end of the window", () => {
        expect(
            stepRuns(
                [
                    { at: 2, px: 100 },
                    { at: 5, px: 110 },
                ],
                0,
                8,
            ),
        ).toEqual([
            [
                { a0: 2, a1: 5, px: 100 },
                { a0: 5, a1: 8, px: 110 },
            ],
        ]);
    });

    it("carries in the value from before the window", () => {
        expect(
            stepRuns(
                [
                    { at: -10, px: 100 },
                    { at: 4, px: 120 },
                ],
                0,
                6,
            ),
        ).toEqual([
            [
                { a0: 0, a1: 4, px: 100 },
                { a0: 4, a1: 6, px: 120 },
            ],
        ]);
    });

    it("breaks the line at an empty side and resumes at the next quote", () => {
        const runs = stepRuns(
            [
                { at: 0, px: 100 },
                { at: 3, px: -1 },
                { at: 6, px: 105 },
            ],
            0,
            9,
        );
        expect(runs).toEqual([[{ a0: 0, a1: 3, px: 100 }], [{ a0: 6, a1: 9, px: 105 }]]);
    });

    it("merges a repeated value and draws nothing for an empty window or no quotes", () => {
        expect(
            stepRuns(
                [
                    { at: 0, px: 100 },
                    { at: 2, px: 100 },
                ],
                0,
                4,
            ),
        ).toEqual([[{ a0: 0, a1: 4, px: 100 }]]);
        expect(stepRuns([{ at: 0, px: 100 }], 5, 5)).toEqual([]);
        expect(stepRuns([], 0, 5)).toEqual([]);
        expect(stepRuns([{ at: 0, px: -1 }], 0, 5)).toEqual([]);
    });
});

describe("candleQuoteSteps (P18 D7, greenlight amendment 7)", () => {
    it("carries a quote across a run of unchanged buckets; only -1 breaks the line", () => {
        // Buckets 10..19 visible. Changes at 8 (before the view), 12 and 17; the bid
        // goes empty at 15 and comes back at 17.
        const buckets: QuoteBucket[] = [
            { bucket: 8, bid: 1_000, ask: 1_010 },
            { bucket: 12, bid: 1_002, ask: 1_010 },
            { bucket: 15, bid: -1, ask: 1_012 },
            { bucket: 17, bid: 1_004, ask: 1_012 },
        ];
        const steps = candleQuoteSteps(buckets, 10, 19);
        expect(steps.bid).toEqual([
            [
                { a0: 0, a1: 2, px: 1_000 },
                { a0: 2, a1: 5, px: 1_002 },
            ],
            [{ a0: 7, a1: 10, px: 1_004 }],
        ]);
        // The ask never breaks: 1_010 over slots 0-4, 1_012 from 5 to the right edge.
        expect(steps.ask).toEqual([
            [
                { a0: 0, a1: 5, px: 1_010 },
                { a0: 5, a1: 10, px: 1_012 },
            ],
        ]);
    });
});

describe("lineQuoteSteps (P18 D7)", () => {
    it("steps on the time window from the raw ring, breaking at the disconnect point", () => {
        const ring: QuotePoint[] = [
            { t: 100, bid: 1_000, ask: 1_010 },
            { t: 200, bid: -1, ask: -1 },
            { t: 300, bid: 1_001, ask: 1_011 },
        ];
        const steps = lineQuoteSteps(ring, 150, 400);
        expect(steps.bid).toEqual([[{ a0: 150, a1: 200, px: 1_000 }], [{ a0: 300, a1: 400, px: 1_001 }]]);
        expect(steps.ask).toEqual([[{ a0: 150, a1: 200, px: 1_010 }], [{ a0: 300, a1: 400, px: 1_011 }]]);
    });
});
