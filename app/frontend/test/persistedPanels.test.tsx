import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { Header } from "../src/components/Header";
import { TradeTape } from "../src/components/TradeTape";
import { PriceChart } from "../src/components/PriceChart";
import { DepthChartOverlay } from "../src/components/DepthChartOverlay";
import { DepthLadder } from "../src/components/DepthLadder";
import { OpenOrders } from "../src/components/OpenOrders";
import { OrderEntry } from "../src/components/OrderEntry";
import { EMPTY_BOOK } from "../src/state/reducer";
import type { BookState, MyOrder, TapeEntry } from "../src/state/reducer";
import { emptyBars } from "../src/bars";
import { SETTING_KEYS } from "../src/settings/persist";

// Each test changes a setting, unmounts the panel (a page switch) and mounts it
// again: the setting must survive. localStorage is cleared between tests.
afterEach(() => {
    cleanup();
    localStorage.clear();
});

const BOOK: BookState = {
    bestBid: 1_500_000,
    bestAsk: 1_502_500,
    bids: [[1_500_000, 10]],
    asks: [[1_502_500, 7]],
    timestamp: 1,
};

const TAPE: TapeEntry[] = [
    { tradeId: 2, pricePx: 1_502_500, quantity: 300, aggressorOrderId: 9, passiveOrderId: 8, timestamp: 2, mine: false },
    { tradeId: 1, pricePx: 1_500_000, quantity: 100, aggressorOrderId: 9, passiveOrderId: 8, timestamp: 1, mine: false },
];

describe("persisted panel settings survive a page switch (D10)", () => {
    it("header label mode", () => {
        const ui = () => (
            <Header
                book={EMPTY_BOOK}
                tape={[]}
                orders={[]}
                sessionVolume={0}
                sessionOpenPx={-1}
                lastFrameNanos={0}
            />
        );
        const { container, unmount } = render(ui());
        fireEvent.click(screen.getByTestId("header-settings-gear"));
        fireEvent.click(screen.getByTestId("header-labels-faint"));
        expect(container.querySelector(".header--labels-faint")).not.toBeNull();
        unmount();

        const again = render(ui());
        expect(again.container.querySelector(".header--labels-faint")).not.toBeNull();
    });

    it("tape levels and time units", () => {
        const { unmount } = render(<TradeTape tape={TAPE} />);
        fireEvent.click(screen.getByTestId("tape-settings-gear"));
        fireEvent.click(screen.getByTestId("tape-filter-gt200"));
        fireEvent.click(screen.getByTestId("tape-precision-ns"));
        expect(screen.getAllByTestId("tape-row")).toHaveLength(1);
        unmount();

        render(<TradeTape tape={TAPE} />);
        expect(screen.getAllByTestId("tape-row")).toHaveLength(1);
        fireEvent.click(screen.getByTestId("tape-settings-gear"));
        expect((screen.getByTestId("tape-filter-gt200") as HTMLInputElement).checked).toBe(true);
        expect(screen.getByTestId("tape-precision-ns").getAttribute("aria-pressed")).toBe("true");
    });

    it("price chart display settings, shared by the page and the overlay", () => {
        // Line colour, area fill and fill colour apply in line mode (disabled in candles, P17 D7).
        localStorage.setItem(SETTING_KEYS.priceChartChartType, JSON.stringify("line"));
        const ui = () => <PriceChart bars={emptyBars()} tape={TAPE} sessionOpenPx={1_500_000} />;
        const { unmount } = render(ui());
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        fireEvent.click(screen.getByTestId("pc-bg-white"));
        fireEvent.click(screen.getByTestId("pc-line-#3b93ff"));
        fireEvent.click(screen.getByTestId("pc-grad-off"));
        fireEvent.click(screen.getByTestId("pc-fill-#12d170"));
        unmount();

        render(ui());
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        expect(screen.getByTestId("pc-bg-white").getAttribute("aria-pressed")).toBe("true");
        expect(screen.getByTestId("pc-line-#3b93ff").getAttribute("aria-checked")).toBe("true");
        expect(screen.getByTestId("pc-grad-off").getAttribute("aria-pressed")).toBe("true");
        expect(screen.getByTestId("pc-fill-#12d170").getAttribute("aria-checked")).toBe("true");
    });

    it("depth chart background", () => {
        const ui = () => <DepthChartOverlay open onClose={vi.fn()} book={BOOK} />;
        const { unmount } = render(ui());
        fireEvent.click(screen.getByTestId("depth-overlay-gear"));
        fireEvent.click(screen.getByTestId("depth-overlay-bg-light"));
        unmount();

        const again = render(ui());
        expect(again.container.querySelector(".depth-overlay__body--light")).not.toBeNull();
    });

    it("book reader depth", () => {
        const { unmount } = render(<DepthLadder book={BOOK} />);
        fireEvent.click(screen.getByTestId("depth-select-trigger"));
        fireEvent.mouseDown(screen.getByTestId("depth-select-opt-14"));
        unmount();

        const again = render(<DepthLadder book={BOOK} />);
        expect(screen.getByTestId("depth-select-trigger").textContent).toBe("14");
        const asks = again.container.querySelector(".depth-ladder__asks") as HTMLElement;
        expect(asks.style.getPropertyValue("--depth-rows")).toBe("14");
    });

    it("blotter status filter", () => {
        const rows: MyOrder[] = [
            {
                clOrdId: 1,
                side: "BUY",
                pricePx: 1_500_000,
                originalQty: 10,
                remainingQty: 0,
                status: "FILLED",
                ordType: "LIMIT",
                tif: "GTC",
                displayQty: 0,
            },
        ];
        const { unmount } = render(<OpenOrders orders={rows} onCancel={vi.fn()} />);
        fireEvent.click(screen.getByTestId("blotter-filter-trigger"));
        fireEvent.mouseDown(screen.getByTestId("blotter-filter-opt-ACTIVE"));
        unmount();

        render(<OpenOrders orders={rows} onCancel={vi.fn()} />);
        expect(screen.getByTestId("blotter-filter-trigger").textContent).toBe("ACTIVE");
        expect(screen.getByTestId("open-orders-filter-empty").textContent).toBe("No active orders");
    });
});

describe("what never persists (D10)", () => {
    it("order-ticket values reset on remount and touch no storage", () => {
        const { unmount } = render(<OrderEntry onSubmit={vi.fn()} />);
        fireEvent.change(screen.getByTestId("price-input"), { target: { value: "150.25" } });
        fireEvent.change(screen.getByTestId("qty-input"), { target: { value: "300" } });
        fireEvent.click(screen.getByTestId("side-sell"));
        unmount();

        render(<OrderEntry onSubmit={vi.fn()} />);
        expect((screen.getByTestId("price-input") as HTMLInputElement).value).toBe("");
        expect((screen.getByTestId("qty-input") as HTMLInputElement).value).toBe("");
        expect(screen.getByTestId("side-buy").getAttribute("aria-pressed")).toBe("true");
        expect(localStorage.length).toBe(0);
    });

    it("open popups do not reopen on remount", () => {
        const { unmount } = render(<TradeTape tape={TAPE} />);
        fireEvent.click(screen.getByTestId("tape-settings-gear"));
        expect(screen.getByTestId("tape-settings")).not.toBeNull();
        unmount();
        render(<TradeTape tape={TAPE} />);
        expect(screen.queryByTestId("tape-settings")).toBeNull();
    });
});
