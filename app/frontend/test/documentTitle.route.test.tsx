import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { Navbar } from "../src/components/Navbar";
import { TradingPage } from "../src/pages/TradingPage";
import { PriceChartPage } from "../src/pages/PriceChartPage";
import { PAGE_NAMES } from "../src/documentTitle";
import { initialState } from "../src/state/reducer";

// No globals, so RTL's auto-cleanup never registers; wire it explicitly.
afterEach(cleanup);

function renderShell(path: string) {
    return render(
        <MemoryRouter initialEntries={[path]}>
            <Navbar />
            <Routes>
                <Route
                    path="/trading"
                    element={
                        <TradingPage
                            state={initialState}
                            onSubmitOrder={vi.fn()}
                            onCancelOrder={vi.fn()}
                            priceOverlayOpen={false}
                            onClosePriceOverlay={vi.fn()}
                        />
                    }
                />
                <Route
                    path="/chart"
                    element={<PriceChartPage state={initialState} onSubmitOrder={vi.fn()} />}
                />
            </Routes>
        </MemoryRouter>,
    );
}

describe("browser tab title follows the routed page (DESIGN_SYSTEM.md §15.6)", () => {
    it("sets the title for each page across navigation", () => {
        document.title = "directROUTE";
        renderShell("/trading");
        expect(document.title).toBe("ASML · Trading | directROUTE");

        fireEvent.click(screen.getByRole("link", { name: "Price Chart" }));
        expect(document.title).toBe("ASML · Price Chart | directROUTE");

        fireEvent.click(screen.getByRole("link", { name: "Trading" }));
        expect(document.title).toBe("ASML · Trading | directROUTE");
    });

    it("uses the same page-name constants the Navbar renders", () => {
        renderShell("/trading");
        const labels = screen.getAllByRole("link").map((a) => a.textContent);
        expect(labels).toEqual([PAGE_NAMES.trading, PAGE_NAMES.priceChart]);
    });
});
