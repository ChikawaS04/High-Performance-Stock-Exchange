import { describe, expect, it } from "vitest";

import { MID_LABEL_PX, VIEW_W, clampTagX, midLabelLayout, svgScale } from "../src/depthChartLayout";

// UI polish D3: the mid label renders at one pixel size in every view.
describe("svgScale", () => {
    it("is the meet scale: the smaller of the two axis ratios", () => {
        expect(svgScale(396, 176)).toBeCloseTo(0.55, 5);
        expect(svgScale(1018, 526)).toBeCloseTo(1018 / 720, 5); // width-bound
        expect(svgScale(1400, 320)).toBe(1); // height-bound
    });

    it("falls back to 1 before the box is measured", () => {
        expect(svgScale(0, 0)).toBe(1);
        expect(svgScale(Number.NaN, 100)).toBe(1);
    });
});

describe("midLabelLayout", () => {
    it("renders the regular and the expanded view at the same MID_LABEL_PX", () => {
        const regular = svgScale(396, 176);
        const expanded = svgScale(1018, 526);
        expect(midLabelLayout(regular, 8).fontSize * regular).toBeCloseTo(MID_LABEL_PX, 6);
        expect(midLabelLayout(expanded, 8).fontSize * expanded).toBeCloseTo(MID_LABEL_PX, 6);
        expect(MID_LABEL_PX).toBeGreaterThanOrEqual(13);
    });

    it("scales the tag box with the font so the text always fits", () => {
        const small = midLabelLayout(1.4, 8);
        const large = midLabelLayout(0.5, 8);
        expect(large.boxH / small.boxH).toBeCloseTo(1.4 / 0.5, 6);
        expect(large.boxW).toBeGreaterThan(8 * large.fontSize * 0.6);
        expect(large.boxH).toBeGreaterThan(large.fontSize);
        // the baseline sits inside the box
        expect(large.textY).toBeGreaterThan(large.boxY);
        expect(large.textY).toBeLessThan(large.bottom);
    });

    it("keeps the tag clear of the top edge", () => {
        expect(midLabelLayout(0.4, 8).boxY).toBeGreaterThan(0);
    });
});

describe("clampTagX", () => {
    it("keeps the whole tag inside the viewBox near either edge", () => {
        expect(clampTagX(10, 100)).toBe(50);
        expect(clampTagX(VIEW_W - 10, 100)).toBe(VIEW_W - 50);
        expect(clampTagX(360, 100)).toBe(360);
    });
});
