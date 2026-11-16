import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { TradingPage } from "../src/pages/TradingPage";
import { initialState } from "../src/state/reducer";

// Floating window stacking (§14): whichever window the user presses comes to the front.

beforeEach(() => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1600 });
    Object.defineProperty(window, "innerHeight", { configurable: true, value: 900 });
});

afterEach(() => {
    cleanup();
    localStorage.clear();
});

function renderPage(priceOverlayOpen: boolean) {
    return render(
        <MemoryRouter>
            <TradingPage
                state={initialState}
                onSubmitOrder={vi.fn()}
                onCancelOrder={vi.fn()}
                priceOverlayOpen={priceOverlayOpen}
                onClosePriceOverlay={vi.fn()}
            />
        </MemoryRouter>,
    );
}

const z = (testId: string): number => Number(screen.getByTestId(testId).style.zIndex);
const inactive = (testId: string): boolean =>
    screen.getByTestId(testId).classList.contains("floating-window--inactive");

describe("floating window stacking", () => {
    it("arriving with the price window open puts it in front", () => {
        renderPage(true);
        expect(z("price-overlay")).toBe(200);
        expect(inactive("price-overlay")).toBe(false);
    });

    it("opening the depth window puts it in front of the open price window", () => {
        renderPage(true);
        fireEvent.click(screen.getByTestId("depth-chart-maximize"));
        expect(z("depth-overlay")).toBeGreaterThan(z("price-overlay"));
        expect(inactive("depth-overlay")).toBe(false);
        expect(inactive("price-overlay")).toBe(true);
    });

    it("a press on the window behind raises it, and the other dims", () => {
        renderPage(true);
        fireEvent.click(screen.getByTestId("depth-chart-maximize"));
        fireEvent.pointerDown(screen.getByTestId("price-overlay"));
        expect(z("price-overlay")).toBeGreaterThan(z("depth-overlay"));
        expect(inactive("price-overlay")).toBe(false);
        expect(inactive("depth-overlay")).toBe(true);
    });

    it("a press on a child (the depth gear) raises its window too", () => {
        renderPage(true);
        fireEvent.click(screen.getByTestId("depth-chart-maximize"));
        fireEvent.pointerDown(screen.getByTestId("price-overlay"));
        fireEvent.pointerDown(screen.getByTestId("depth-overlay-gear"));
        fireEvent.click(screen.getByTestId("depth-overlay-gear"));
        expect(z("depth-overlay")).toBeGreaterThan(z("price-overlay"));
        expect(screen.getByTestId("depth-overlay-settings")).toBeTruthy();
    });

    it("closing the front window leaves the other in front", () => {
        renderPage(true);
        fireEvent.click(screen.getByTestId("depth-chart-maximize"));
        fireEvent.click(screen.getByTestId("depth-overlay-close"));
        expect(screen.queryByTestId("depth-overlay")).toBeNull();
        expect(inactive("price-overlay")).toBe(false);
    });
});
