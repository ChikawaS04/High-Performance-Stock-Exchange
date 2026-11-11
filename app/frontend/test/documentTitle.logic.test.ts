import { describe, expect, it } from "vitest";

import { BRAND, PAGE_NAMES, documentTitle } from "../src/documentTitle";
import { SYMBOL } from "../src/protocol/messages";

describe("documentTitle (DESIGN_SYSTEM.md §15.6)", () => {
    it("builds the Trading tab title", () => {
        expect(documentTitle(SYMBOL, PAGE_NAMES.trading)).toBe("ASML · Trading | directROUTE");
    });

    it("builds the Price Chart tab title", () => {
        expect(documentTitle(SYMBOL, PAGE_NAMES.priceChart)).toBe("ASML · Price Chart | directROUTE");
    });

    it("separates symbol and page with a spaced middle dot (U+00B7)", () => {
        const title = documentTitle("ASML", "Trading");
        const sep = title.indexOf(" ", 0) + 1;
        expect(title.charCodeAt(sep)).toBe(0x00b7);
        expect(title.slice(sep - 1, sep + 2)).toBe(" · ");
    });

    it("ends with the brand after a pipe", () => {
        expect(BRAND).toBe("directROUTE");
        expect(documentTitle("X", "Y").endsWith(" | directROUTE")).toBe(true);
    });
});
