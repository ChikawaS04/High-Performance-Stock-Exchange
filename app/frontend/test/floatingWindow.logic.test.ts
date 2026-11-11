import { describe, expect, it } from "vitest";

import {
    EDGES,
    EDGE_CURSOR,
    MIN_H,
    MIN_W,
    clampRect,
    defaultRect,
    isRect,
    moveRect,
    resizeRect,
    restoreRect,
} from "../src/floatingWindow";
import type { Rect } from "../src/floatingWindow";

const VP = { w: 1600, h: 900 };
const START: Rect = { x: 400, y: 200, w: 800, h: 500 };

const right = (r: Rect): number => r.x + r.w;
const bottom = (r: Rect): number => r.y + r.h;

describe("moveRect", () => {
    it("moves by the pointer delta without changing size", () => {
        expect(moveRect(START, 50, -30, VP)).toEqual({ x: 450, y: 170, w: 800, h: 500 });
    });

    it("never moves past the viewport, so the header stays on screen", () => {
        const upLeft = moveRect(START, -5000, -5000, VP);
        expect(upLeft).toEqual({ x: 0, y: 0, w: 800, h: 500 });
        const downRight = moveRect(START, 5000, 5000, VP);
        expect(right(downRight)).toBe(VP.w);
        expect(bottom(downRight)).toBe(VP.h);
        expect(downRight.y).toBeGreaterThanOrEqual(0);
    });
});

describe("resizeRect: each edge moves only its own side", () => {
    it("e grows the width, left edge fixed", () => {
        const r = resizeRect(START, "e", 100, 40, VP);
        expect(r).toEqual({ x: 400, y: 200, w: 900, h: 500 });
    });

    it("w moves the left edge, right edge fixed", () => {
        const r = resizeRect(START, "w", -100, 40, VP);
        expect(r.x).toBe(300);
        expect(right(r)).toBe(right(START));
        expect(r.h).toBe(500);
    });

    it("s grows the height, top edge fixed", () => {
        expect(resizeRect(START, "s", 30, 100, VP)).toEqual({ x: 400, y: 200, w: 800, h: 600 });
    });

    it("n moves the top edge, bottom edge fixed", () => {
        const r = resizeRect(START, "n", 30, -100, VP);
        expect(r.y).toBe(100);
        expect(bottom(r)).toBe(bottom(START));
        expect(r.w).toBe(800);
    });

    it("corners resize both axes, each from its own side", () => {
        expect(resizeRect(START, "se", 50, 60, VP)).toEqual({ x: 400, y: 200, w: 850, h: 560 });
        const nw = resizeRect(START, "nw", -50, -60, VP);
        expect(nw).toEqual({ x: 350, y: 140, w: 850, h: 560 });
        const ne = resizeRect(START, "ne", 50, -60, VP);
        expect(ne).toEqual({ x: 400, y: 140, w: 850, h: 560 });
        const sw = resizeRect(START, "sw", -50, 60, VP);
        expect(sw).toEqual({ x: 350, y: 200, w: 850, h: 560 });
    });

    it("holds the minimum size, keeping the opposite edge fixed", () => {
        const shrunk = resizeRect(START, "se", -5000, -5000, VP);
        expect(shrunk).toEqual({ x: 400, y: 200, w: MIN_W, h: MIN_H });
        const fromLeft = resizeRect(START, "nw", 5000, 5000, VP);
        expect(fromLeft.w).toBe(MIN_W);
        expect(fromLeft.h).toBe(MIN_H);
        expect(right(fromLeft)).toBe(right(START));
        expect(bottom(fromLeft)).toBe(bottom(START));
    });

    it("never resizes past the viewport", () => {
        const big = resizeRect(START, "se", 5000, 5000, VP);
        expect(right(big)).toBe(VP.w);
        expect(bottom(big)).toBe(VP.h);
        const up = resizeRect(START, "nw", -5000, -5000, VP);
        expect(up.x).toBe(0);
        expect(up.y).toBe(0);
        expect(right(up)).toBe(right(START));
    });

    it("gives every handle a resize cursor", () => {
        expect(EDGES).toHaveLength(8);
        for (const e of EDGES) expect(EDGE_CURSOR[e]).toMatch(/-resize$/);
        expect(EDGE_CURSOR.n).toBe("ns-resize");
        expect(EDGE_CURSOR.e).toBe("ew-resize");
        expect(EDGE_CURSOR.ne).toBe("nesw-resize");
        expect(EDGE_CURSOR.se).toBe("nwse-resize");
    });
});

describe("clampRect", () => {
    it("pulls a window back inside a viewport that shrank", () => {
        const r = clampRect({ x: 1200, y: 700, w: 800, h: 500 }, { w: 1000, h: 600 });
        expect(r).toEqual({ x: 200, y: 100, w: 800, h: 500 });
    });

    it("caps the size at the viewport and keeps the header on screen", () => {
        const r = clampRect({ x: -50, y: -40, w: 3000, h: 3000 }, { w: 1000, h: 600 });
        expect(r).toEqual({ x: 0, y: 0, w: 1000, h: 600 });
    });

    it("lets the viewport win over the minimum when the viewport is smaller", () => {
        const r = clampRect({ x: 0, y: 0, w: 100, h: 100 }, { w: 300, h: 200 });
        expect(r).toEqual({ x: 0, y: 0, w: 300, h: 200 });
    });

    it("raises an undersized rect to the minimum", () => {
        expect(clampRect({ x: 10, y: 10, w: 100, h: 50 }, VP)).toEqual({ x: 10, y: 10, w: MIN_W, h: MIN_H });
    });
});

describe("defaultRect / restoreRect", () => {
    it("centres the nominal size, capped at 94% x 82% of the viewport", () => {
        expect(defaultRect({ w: 1040, h: 560 }, VP)).toEqual({ x: 280, y: 170, w: 1040, h: 560 });
        const small = defaultRect({ w: 1040, h: 560 }, { w: 800, h: 500 });
        expect(small.w).toBe(752);
        expect(small.h).toBe(410);
        expect(small.x).toBe(24);
    });

    it("restores a saved rect clamped to the current viewport", () => {
        const r = restoreRect({ x: 1500, y: 850, w: 700, h: 400 }, { w: 1040, h: 560 }, VP);
        expect(r).toEqual({ x: 900, y: 500, w: 700, h: 400 });
    });

    it("falls back to the default for a missing or invalid value", () => {
        const def = defaultRect({ w: 760, h: 350 }, VP);
        expect(restoreRect(null, { w: 760, h: 350 }, VP)).toEqual(def);
        expect(restoreRect({ x: 1, y: 2, w: "wide", h: 3 }, { w: 760, h: 350 }, VP)).toEqual(def);
        expect(restoreRect({ x: 1, y: 2, w: -5, h: 3 }, { w: 760, h: 350 }, VP)).toEqual(def);
        expect(restoreRect({ x: Number.NaN, y: 2, w: 500, h: 300 }, { w: 760, h: 350 }, VP)).toEqual(def);
    });

    it("validates the stored shape", () => {
        expect(isRect({ x: 0, y: 0, w: 400, h: 300 })).toBe(true);
        expect(isRect({ x: 0, y: 0, w: 400 })).toBe(false);
        expect(isRect("400x300")).toBe(false);
        expect(isRect(null)).toBe(false);
    });
});
