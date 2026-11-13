import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { OpenOrders } from "../src/components/OpenOrders";
import type { MarketOrder, MyOrder } from "../src/state/reducer";
import { BOT_BLOCK } from "../src/participants";
import { EMPTY_PRICE } from "../src/format";
import { SETTING_KEYS } from "../src/settings/persist";

afterEach(() => {
    cleanup();
    localStorage.clear();
});

const MINE: MyOrder = {
    clOrdId: 1_763_000_000_001,
    side: "BUY",
    pricePx: 1_500_000,
    originalQty: 10,
    remainingQty: 10,
    status: "OPEN",
    ordType: "LIMIT",
    tif: "GTC",
    displayQty: 0,
    sentAtNanos: 100,
};

const MAKER_ASK: MarketOrder = {
    clOrdId: BOT_BLOCK + 1,
    side: "SELL",
    pricePx: 1_500_200,
    originalQty: 100,
    remainingQty: 100,
    status: "OPEN",
    ordType: "LIMIT",
    tif: "GTC",
    firstSeenNanos: 200,
};

const TREND_IOC: MarketOrder = {
    clOrdId: 2 * BOT_BLOCK + 1,
    side: "BUY",
    pricePx: -1,
    originalQty: 50,
    remainingQty: 0,
    status: "FILLED",
    ordType: "LIMIT",
    tif: null,
    firstSeenNanos: 300,
};

function pickOwner(testId: string): void {
    fireEvent.click(screen.getByTestId("blotter-owner-filter-trigger"));
    fireEvent.mouseDown(screen.getByTestId(`blotter-owner-filter-opt-${testId}`));
}

describe("OpenOrders owner filter and Owner column", () => {
    it("shows only this terminal's orders by default", () => {
        render(<OpenOrders orders={[MINE]} marketOrders={[MAKER_ASK, TREND_IOC]} onCancel={vi.fn()} />);
        expect(screen.getAllByTestId("open-orders-row")).toHaveLength(1);
        expect(screen.getByTestId(`owner-${MINE.clOrdId}`).textContent).toBe("YOU");
        expect(screen.getByTestId(`cancel-${MINE.clOrdId}`)).toBeTruthy();
    });

    it("ALL PARTICIPANTS adds the bots' orders with their owner and no cancel", () => {
        render(<OpenOrders orders={[MINE]} marketOrders={[MAKER_ASK, TREND_IOC]} onCancel={vi.fn()} />);
        pickOwner("ALL");
        expect(screen.getAllByTestId("open-orders-row")).toHaveLength(3);
        expect(screen.getByTestId(`owner-${MAKER_ASK.clOrdId}`).textContent).toBe("MAKER");
        expect(screen.getByTestId(`owner-${TREND_IOC.clOrdId}`).textContent).toBe("TREND");
        expect(screen.queryByTestId(`cancel-${MAKER_ASK.clOrdId}`)).toBeNull();
        expect(screen.getByTestId(`tif-${TREND_IOC.clOrdId}`).textContent).toBe(EMPTY_PRICE);
        expect(localStorage.getItem(SETTING_KEYS.blotterOwnerFilter)).toBe(JSON.stringify("ALL"));
    });

    it("a bot filter shows that bot alone, and an idle bot shows its empty line", () => {
        render(<OpenOrders orders={[MINE]} marketOrders={[MAKER_ASK, TREND_IOC]} onCancel={vi.fn()} />);
        pickOwner("MAKER");
        const rows = screen.getAllByTestId("open-orders-row");
        expect(rows).toHaveLength(1);
        expect(rows[0].className).toContain("open-orders__row--sell");
        pickOwner("INST");
        expect(screen.getByTestId("open-orders-empty").textContent).toBe("No INST orders yet");
    });
});
