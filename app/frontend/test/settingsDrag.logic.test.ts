import { describe, expect, it } from "vitest";

import { clampOffset } from "../src/components/ChartSettingsPopup";

// Dragging the chart settings view: the offset from its centred place, clamped so the
// whole view stays inside the viewport.

// The 680 x 460 view centred in a 1280 x 720 viewport.
const BASE = { left: 300, top: 130, width: 680, height: 460 };

describe("clampOffset", () => {
    it("passes an offset that keeps the view inside", () => {
        expect(clampOffset({ x: -120, y: 60 }, BASE, 1280, 720)).toEqual({ x: -120, y: 60 });
    });

    it("stops at every edge", () => {
        expect(clampOffset({ x: -999, y: -999 }, BASE, 1280, 720)).toEqual({ x: -300, y: -130 });
        expect(clampOffset({ x: 999, y: 999 }, BASE, 1280, 720)).toEqual({ x: 300, y: 130 });
    });

    it("keeps a view larger than the viewport centred on that axis", () => {
        expect(clampOffset({ x: 50, y: 50 }, { left: -10, top: 130, width: 700, height: 460 }, 680, 720)).toEqual({ x: 0, y: 50 });
    });

    it("pulls a dragged view back inside when the viewport shrinks", () => {
        // Dragged 300 right at 1280 wide, then the window narrows to 1000 (base re-centres at 160).
        expect(clampOffset({ x: 300, y: 0 }, { left: 160, top: 130, width: 680, height: 460 }, 1000, 720)).toEqual({ x: 160, y: 0 });
    });
});
