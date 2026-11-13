import { describe, expect, it } from "vitest";

import { BOT_BLOCK, botOwnerOf, ownerOf } from "../src/participants";
import {
    DEFAULT_OWNER_FILTER,
    OWNER_FILTER_OPTIONS,
    blotterRows,
    emptyBlotterMessage,
    filterOrders,
    isOwnerFilter,
} from "../src/blotterFilter";
import type { MarketOrder, MyOrder } from "../src/state/reducer";

describe("participants (pure)", () => {
    it("maps each bot block to its bot, matching Bots.java", () => {
        expect(botOwnerOf(1 * BOT_BLOCK)).toBe("MAKER");
        expect(botOwnerOf(2 * BOT_BLOCK + 5)).toBe("TREND");
        expect(botOwnerOf(4 * BOT_BLOCK - 1)).toBe("REVERT");
        expect(botOwnerOf(4 * BOT_BLOCK)).toBe("NOISE");
        expect(botOwnerOf(6 * BOT_BLOCK - 1)).toBe("INST");
    });

    it("a terminal id or anything outside the blocks is no bot", () => {
        expect(botOwnerOf(1_763_000_000_000)).toBeNull();
        expect(botOwnerOf(6 * BOT_BLOCK)).toBeNull();
        expect(botOwnerOf(-1)).toBeNull();
        expect(ownerOf(1_763_000_000_000, false)).toBe("OTHER");
        expect(ownerOf(1_763_000_000_000, true)).toBe("YOU");
    });
});

function mine(clOrdId: number, sentAtNanos: number): MyOrder {
    return {
        clOrdId,
        side: "BUY",
        pricePx: 1_500_000,
        originalQty: 10,
        remainingQty: 10,
        status: "OPEN",
        ordType: "LIMIT",
        tif: "GTC",
        displayQty: 0,
        sentAtNanos,
    };
}

function market(clOrdId: number, firstSeenNanos: number, status: MarketOrder["status"] = "OPEN"): MarketOrder {
    return {
        clOrdId,
        side: "SELL",
        pricePx: 1_500_100,
        originalQty: 50,
        remainingQty: status === "FILLED" ? 0 : 50,
        status,
        ordType: "LIMIT",
        tif: "GTC",
        firstSeenNanos,
    };
}

const MY = [mine(1_763_000_000_002, 300), mine(1_763_000_000_001, 100)];
const BOTS = [market(4 * BOT_BLOCK + 1, 400), market(1 * BOT_BLOCK + 1, 200, "FILLED")];

describe("owner filter (pure)", () => {
    it("defaults to MINE and validates stored values", () => {
        expect(DEFAULT_OWNER_FILTER).toBe("MINE");
        expect(OWNER_FILTER_OPTIONS.map((o) => o.value)).toEqual(["MINE", "ALL", "MAKER", "TREND", "REVERT", "NOISE", "INST"]);
        expect(isOwnerFilter("NOISE")).toBe(true);
        expect(isOwnerFilter("WHALE")).toBe(false);
    });

    it("MINE shows only this terminal's rows, in their own order", () => {
        const rows = blotterRows(MY, BOTS, "MINE");
        expect(rows.map((r) => r.clOrdId)).toEqual(MY.map((o) => o.clOrdId));
        expect(rows.every((r) => r.owner === "YOU")).toBe(true);
        expect(rows[0].timeNanos).toBe(300);
    });

    it("ALL merges both sources newest first", () => {
        const rows = blotterRows(MY, BOTS, "ALL");
        expect(rows.map((r) => r.owner)).toEqual(["NOISE", "YOU", "MAKER", "YOU"]);
    });

    it("a bot filter shows that bot alone, and the status filter composes with it", () => {
        expect(blotterRows(MY, BOTS, "MAKER").map((r) => r.owner)).toEqual(["MAKER"]);
        expect(filterOrders(blotterRows(MY, BOTS, "ALL"), "FILLED").map((r) => r.owner)).toEqual(["MAKER"]);
        expect(blotterRows(MY, BOTS, "INST")).toEqual([]);
    });

    it("another participant's row carries no display size", () => {
        expect(blotterRows([], BOTS, "ALL")[0].displayQty).toBe(0);
    });

    it("the empty message names the bot when a bot has no rows", () => {
        expect(emptyBlotterMessage("INST", "ALL")).toBe("No INST orders yet");
        expect(emptyBlotterMessage("ALL", "ALL")).toBe("No orders yet");
        expect(emptyBlotterMessage("INST", "FILLED")).toBe("No filled orders");
    });
});
