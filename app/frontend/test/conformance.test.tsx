import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { OpenOrders } from "../src/components/OpenOrders";
import { DepthLadder } from "../src/components/DepthLadder";
import { TradeTape } from "../src/components/TradeTape";
import { PriceChart } from "../src/components/PriceChart";
import { DepthChartOverlay } from "../src/components/DepthChartOverlay";
import type { BookState, MyOrder, TapeEntry } from "../src/state/reducer";

// UI polish conformance: the code matches the locked design text (§9, §10, §11).
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    localStorage.clear();
});

const CSS = readFileSync(resolve(__dirname, "../src/styles/terminal.css"), "utf-8");

function rule(selector: string): string {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const m = new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`).exec(CSS);
    if (m === null) throw new Error(`no rule for ${selector}`);
    return m[1];
}

const BOOK: BookState = {
    bestBid: 1_500_000,
    bestAsk: 1_502_500,
    bids: [[1_500_000, 10]],
    asks: [[1_502_500, 7]],
    timestamp: 1,
};

const ORDER: MyOrder = {
    clOrdId: 1,
    side: "BUY",
    pricePx: 1_500_000,
    originalQty: 10,
    remainingQty: 10,
    status: "OPEN",
    ordType: "LIMIT",
    tif: "GTC",
    displayQty: 0,
};

const TAPE: TapeEntry[] = [
    { tradeId: 1, pricePx: 1_500_000, quantity: 5, aggressorOrderId: 9, passiveOrderId: 8, timestamp: 1, mine: false },
];

describe("§11.5 dropdown lists escape their card", () => {
    it("renders the blotter filter list outside a clipped, short card with every option", () => {
        render(
            <div data-testid="clip" style={{ overflow: "hidden", height: "30px" }}>
                <OpenOrders orders={[ORDER]} onCancel={vi.fn()} />
            </div>,
        );
        fireEvent.click(screen.getByTestId("blotter-filter-trigger"));
        const list = screen.getByRole("listbox", { name: "Status filter options" });
        expect(screen.getByTestId("clip").contains(list)).toBe(false);
        expect(list.parentElement).toBe(document.body);
        expect(list.style.position).toBe("fixed");
        expect(list.querySelectorAll('[role="option"]')).toHaveLength(5);
    });

    it("renders the book reader depth list outside its clipped panel", () => {
        render(
            <section data-testid="clip" style={{ overflow: "hidden", height: "20px" }}>
                <DepthLadder book={BOOK} />
            </section>,
        );
        fireEvent.click(screen.getByTestId("depth-select-trigger"));
        const list = screen.getByRole("listbox", { name: "Depth levels options" });
        expect(screen.getByTestId("clip").contains(list)).toBe(false);
        expect(list.querySelectorAll('[role="option"]')).toHaveLength(3);
    });

    it("follows its trigger on scroll and closes on Escape and outside click", () => {
        let bottom = 100;
        vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(
            () =>
                ({
                    width: 80,
                    height: 20,
                    top: bottom - 20,
                    bottom,
                    left: 40,
                    right: 120,
                    x: 40,
                    y: bottom - 20,
                    toJSON: () => ({}),
                }) as DOMRect,
        );
        render(<OpenOrders orders={[ORDER]} onCancel={vi.fn()} />);
        const trigger = screen.getByTestId("blotter-filter-trigger");
        fireEvent.click(trigger);
        const list = (): HTMLElement => screen.getByRole("listbox");
        expect(list().style.top).toBe("102px");

        bottom = 200;
        fireEvent.scroll(window);
        expect(list().style.top).toBe("202px");

        fireEvent.keyDown(trigger, { key: "Escape" });
        expect(screen.queryByRole("listbox")).toBeNull();

        fireEvent.click(trigger);
        fireEvent.blur(trigger);
        expect(screen.queryByRole("listbox")).toBeNull();
    });

    it("styles the book reader depth options as numbers (§11.5)", () => {
        render(<DepthLadder book={BOOK} />);
        fireEvent.click(screen.getByTestId("depth-select-trigger"));
        for (const v of ["8", "10", "14"]) {
            expect(screen.getByTestId(`depth-select-opt-${v}`).classList.contains("field-menu__opt--num")).toBe(true);
        }
        const num = rule(".field-menu__opt--num");
        expect(num).toContain("var(--mono)");
        expect(num).toContain("tabular-nums");
        expect(num).toContain("text-align: right");
    });
});

describe("§11.4 every settings popup has the silver Done footer", () => {
    it("tape", () => {
        render(<TradeTape tape={TAPE} />);
        fireEvent.click(screen.getByTestId("tape-settings-gear"));
        const done = screen.getByTestId("tape-settings-done");
        expect(done.className).toBe("settings-pop__done");
        expect(done.closest(".settings-pop__foot")).not.toBeNull();
        fireEvent.click(done);
        expect(screen.queryByTestId("tape-settings")).toBeNull();
    });

    it("price chart", () => {
        render(<PriceChart tape={TAPE} sessionOpenPx={1_500_000} />);
        fireEvent.click(screen.getByTestId("pc-settings-gear"));
        fireEvent.click(screen.getByTestId("pc-settings-done"));
        expect(screen.queryByTestId("pc-settings")).toBeNull();
    });

    it("depth chart window", () => {
        render(<DepthChartOverlay open onClose={vi.fn()} book={BOOK} />);
        fireEvent.click(screen.getByTestId("depth-overlay-gear"));
        fireEvent.click(screen.getByTestId("depth-overlay-settings-done"));
        expect(screen.queryByTestId("depth-overlay-settings")).toBeNull();
    });
});

describe("§9 panel caret menus use the shared white list", () => {
    it("blotter cancel menu: shared classes, CANCEL BY ID by CSS, behaviour unchanged", () => {
        render(<OpenOrders orders={[]} onCancel={vi.fn()} />);
        fireEvent.click(screen.getByTestId("blotter-menu-btn"));
        const item = screen.getByTestId("cancel-by-id-open");
        const menu = item.closest('[role="menu"]')!;
        expect(menu.classList.contains("field-menu__menu")).toBe(true);
        expect(item.classList.contains("field-menu__opt")).toBe(true);
        expect(item.classList.contains("field-menu__opt--caps")).toBe(true);
        expect(item.textContent).toBe("Cancel by ID"); // DOM text unchanged
        expect(rule(".field-menu__opt--caps")).toContain("text-transform: uppercase");
        fireEvent.click(item);
        expect(screen.getByTestId("cancel-by-id-overlay")).not.toBeNull();
    });

    it("price chart view menu: shared classes, behaviour unchanged", () => {
        const onView = vi.fn();
        render(<PriceChart tape={TAPE} sessionOpenPx={1_500_000} onViewInTrading={onView} />);
        fireEvent.click(screen.getByTestId("pc-view-caret"));
        const menu = screen.getByTestId("pc-view-menu");
        expect(menu.classList.contains("field-menu__menu")).toBe(true);
        const item = screen.getByTestId("pc-view-trading");
        expect(item.classList.contains("field-menu__opt")).toBe(true);
        fireEvent.click(item);
        expect(onView).toHaveBeenCalledTimes(1);
    });

    it("no per-panel menu variants remain in the stylesheet", () => {
        expect(CSS).not.toMatch(/\.open-orders__menu\s*\{/);
        expect(CSS).not.toMatch(/\.open-orders__menu-item/);
        expect(CSS).not.toMatch(/\.price-chart__viewmenu/);
        expect(CSS).not.toMatch(/\.price-chart__viewopt/);
    });
});

describe("§10 primary-action labels render uppercase (CSS only)", () => {
    it("Confirm Cancel, Send Order and Done are uppercased by text-transform", () => {
        for (const sel of [".cxid-confirm", ".order-confirm__send", ".header-settings__done", ".settings-pop__done"]) {
            expect(rule(sel)).toContain("text-transform: uppercase");
        }
    });

    it("keeps the DOM text and accessible names as written", () => {
        render(<OpenOrders orders={[ORDER]} onCancel={vi.fn()} />);
        fireEvent.click(screen.getByTestId("cancel-1"));
        expect(screen.getByRole("button", { name: "Confirm Cancel" })).not.toBeNull();
    });
});
