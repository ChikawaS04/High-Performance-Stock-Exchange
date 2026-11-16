import { describe, expect, it } from "vitest";

import { WINDOW_Z_BASE, isFront, raise, remove, windowZ } from "../src/windowStack";
import type { WindowStack } from "../src/windowStack";

describe("windowStack", () => {
    it("opening a window puts it in front", () => {
        expect(raise([], "depth")).toEqual(["depth"]);
        expect(raise(["depth"], "price")).toEqual(["depth", "price"]);
    });

    it("raising the window behind moves it to the front", () => {
        expect(raise(["depth", "price"], "depth")).toEqual(["price", "depth"]);
    });

    it("raising the window already in front is a no-op that returns the same array", () => {
        const s: WindowStack = ["depth", "price"];
        expect(raise(s, "price")).toBe(s);
    });

    it("closing drops the window; closing an absent one returns the same array", () => {
        expect(remove(["depth", "price"], "price")).toEqual(["depth"]);
        const s: WindowStack = ["depth"];
        expect(remove(s, "price")).toBe(s);
    });

    it("z values stay in the 200 band, front highest", () => {
        const s: WindowStack = ["price", "depth"];
        expect(windowZ(s, "price")).toBe(WINDOW_Z_BASE);
        expect(windowZ(s, "depth")).toBe(WINDOW_Z_BASE + 1);
        expect(isFront(s, "depth")).toBe(true);
        expect(isFront(s, "price")).toBe(false);
    });

    it("a window not stacked yet reads as front", () => {
        expect(windowZ(["depth"], "price")).toBe(WINDOW_Z_BASE + 1);
        expect(isFront(["depth"], "price")).toBe(true);
    });
});
