import { describe, expect, it } from "vitest";

import {
    CANDLE_DEFAULTS,
    PALETTE,
    PALETTE_COLUMNS,
    RECENT_CAP,
    effectiveCandleColors,
    inkOn,
    isCandleColor,
    isFillChoice,
    isHexColor,
    isWickMode,
    parseHexInput,
    pushRecent,
    sanitizeRecent,
} from "../src/chartColors";

// Pure colour rules (Charts follow-up, decisions 5 and 6).

// Every line colour the pre-follow-up popup offered, so a stored one stays valid.
const OLD_LINE_COLORS = [
    "#ffb01f", "#12d170", "#ff4d4f", "#3b93ff", "#1fd6e8", "#0fd9b6",
    "#8a6bff", "#f14fe6", "#ff7a18", "#ffe234", "#ffffff", "#a9b4c4",
];

describe("palette", () => {
    it("holds the mockup's 42 swatches in six columns, once each, all #rrggbb", () => {
        expect(PALETTE).toHaveLength(42);
        expect(PALETTE_COLUMNS).toBe(6);
        expect(new Set(PALETTE).size).toBe(42);
        for (const c of PALETTE) expect(isHexColor(c)).toBe(true);
    });

    it("includes every existing line colour and both grounds' candle defaults", () => {
        for (const c of OLD_LINE_COLORS) expect(PALETTE).toContain(c);
        for (const g of ["black", "white"] as const) {
            expect(PALETTE).toContain(CANDLE_DEFAULTS[g].up);
            expect(PALETTE).toContain(CANDLE_DEFAULTS[g].down);
        }
    });
});

describe("validators", () => {
    it("accept exactly #rrggbb for a colour, and the keyword where one exists", () => {
        expect(isHexColor("#0fd9b6")).toBe(true);
        expect(isHexColor("#0FD9B6")).toBe(false); // stored lower case
        expect(isHexColor("0fd9b6")).toBe(false);
        expect(isHexColor("#fff")).toBe(false);
        expect(isHexColor(12)).toBe(false);
        expect(isFillChoice("line")).toBe(true);
        expect(isFillChoice("#123456")).toBe(true);
        expect(isFillChoice("auto")).toBe(false);
        expect(isCandleColor("default")).toBe(true);
        expect(isCandleColor("#00ff00")).toBe(true);
        expect(isCandleColor("green")).toBe(false);
        expect(isWickMode("body")).toBe(true);
        expect(isWickMode("grey")).toBe(true);
        expect(isWickMode("gray")).toBe(false);
    });

    it("parse the Hex field: #RRGGBB in any case, anything else ignored", () => {
        expect(parseHexInput(" #0FD9B6 ")).toBe("#0fd9b6");
        expect(parseHexInput("#0fd9b")).toBeNull();
        expect(parseHexInput("0fd9b6")).toBeNull();
        expect(parseHexInput("#0fd9bz")).toBeNull();
        expect(parseHexInput("")).toBeNull();
    });
});

describe("recent colours", () => {
    it("keep the last four picks, newest first, without duplicates", () => {
        expect(RECENT_CAP).toBe(4);
        let r: readonly string[] = [];
        for (const c of ["#111111", "#222222", "#333333", "#444444", "#555555"]) r = pushRecent(r, c);
        expect(r).toEqual(["#555555", "#444444", "#333333", "#222222"]);
        r = pushRecent(r, "#333333");
        expect(r).toEqual(["#333333", "#555555", "#444444", "#222222"]);
        expect(pushRecent(r, "nope")).toBe(r);
    });

    it("sanitize a stored list to valid colours, once each, at most four", () => {
        expect(sanitizeRecent(["#111111", "bad", "#111111", "#222222", "#333333", "#444444", "#555555"])).toEqual([
            "#111111",
            "#222222",
            "#333333",
            "#444444",
        ]);
    });
});

describe("candle colours", () => {
    it("use the ground's direction colours by default", () => {
        expect(effectiveCandleColors("black", "default", "default")).toEqual({ up: "#00ff00", down: "#ff0000" });
        expect(effectiveCandleColors("white", "default", "default")).toEqual({ up: "#1f9d3a", down: "#d40000" });
    });

    it("apply a pick on both grounds; only the defaults switch with the ground", () => {
        for (const bg of ["black", "white"] as const) {
            expect(effectiveCandleColors(bg, "#0fd9b6", "default")).toEqual({
                up: "#0fd9b6",
                down: CANDLE_DEFAULTS[bg].down,
            });
        }
    });

    it("choose the tag ink by luminance", () => {
        expect(inkOn("#00ff00")).toBe("#0b0b0b");
        expect(inkOn("#ff0000")).toBe("#ffffff");
        expect(inkOn("#ffe234")).toBe("#0b0b0b");
        expect(inkOn("#1f5fd1")).toBe("#ffffff");
        // The white ground's defaults take white ink, as before.
        expect(inkOn("#1f9d3a")).toBe("#ffffff");
        expect(inkOn("#d40000")).toBe("#ffffff");
    });
});
