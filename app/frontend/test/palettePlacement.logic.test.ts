import { describe, expect, it } from "vitest";

import { placePalette } from "../src/components/ColorPalette";

// The colour palette inside the chart settings view: placed in the dialog's layer from
// the chip's rect, under the chip and right-aligned to it, flipped above when short.

const LAYER = { top: 100, left: 200 };
const POP = { width: 186, height: 240 };

describe("palette placement in the settings view layer", () => {
    it("sits under the chip, right-aligned, in layer coordinates", () => {
        const at = placePalette({ top: 150, bottom: 170, right: 600 }, LAYER, POP, 720);
        expect(at).toEqual({ top: 170 + 4 - 100, left: 600 - 186 - 200 });
    });

    it("flips above the chip when it would run past the viewport's bottom", () => {
        // Below: 504 + 240 = 744 > 712, with 216 px below against 452 px above.
        const at = placePalette({ top: 480, bottom: 500, right: 600 }, LAYER, POP, 720);
        expect(at.top).toBe(480 - 4 - 240 - 100);
    });

    it("stays below when there is less room above than below", () => {
        // A short viewport where neither side fits: keep the larger side, below.
        const at = placePalette({ top: 60, bottom: 80, right: 600 }, LAYER, POP, 300);
        expect(at.top).toBe(80 + 4 - 100);
    });

    it("fits exactly at the margin without flipping", () => {
        // 472 + 240 = 712, exactly the viewport less its 8 px margin.
        const at = placePalette({ top: 448, bottom: 468, right: 600 }, LAYER, POP, 720);
        expect(at.top).toBe(468 + 4 - 100);
    });
});
