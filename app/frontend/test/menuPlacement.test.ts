import { describe, expect, it } from "vitest";

import { LIST_ROOM, placeList } from "../src/menuPlacement";

// Dropdown proportions (Charts follow-up, decision 2): content-sized, under the
// trigger, left edges aligned unless that runs past the panel's right edge.

const VW = 1600;
const VH = 900;
const trigger = (left: number, width: number, top = 100) => ({ left, right: left + width, top, bottom: top + 22, width });

describe("placeList", () => {
    it("is at least the trigger's width and never a fixed width", () => {
        const p = placeList(trigger(100, 60), 0, 1000, VW, VH);
        expect(p.minWidth).toBe(60);
        expect(p).not.toHaveProperty("width");
    });

    it("opens directly under the trigger with the left edges aligned", () => {
        const p = placeList(trigger(100, 60), 140, 1000, VW, VH);
        expect(p.top).toBe(124);
        expect(p.left).toBe(100);
        expect(p.right).toBeUndefined();
    });

    it("aligns its right edge to the trigger's when it would run past the panel's right edge", () => {
        // A 150px list from a trigger at 900 would end at 1050, past the panel at 1000.
        const p = placeList(trigger(900, 60), 150, 1000, VW, VH);
        expect(p.left).toBeUndefined();
        expect(p.right).toBe(VW - 960);
    });

    it("stays left-aligned when the content is narrower than the trigger and fits", () => {
        const p = placeList(trigger(950, 50), 30, 1000, VW, VH);
        expect(p.left).toBe(950);
    });

    it("opens upward when there is no room below and more above", () => {
        const p = placeList(trigger(100, 60, VH - 60), 100, 1000, VW, VH);
        expect(VH - (VH - 60 + 22)).toBeLessThan(LIST_ROOM);
        expect(p.bottom).toBe(VH - (VH - 60) + 2);
        expect(p.top).toBeUndefined();
    });
});
