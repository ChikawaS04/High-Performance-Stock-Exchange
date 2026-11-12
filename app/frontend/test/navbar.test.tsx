import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { Navbar } from "../src/components/Navbar";
import { PAGE_NAMES } from "../src/documentTitle";

// P5-0's no-globals stance means RTL's auto-cleanup never registers; wire it
// explicitly so renders don't bleed across tests.
afterEach(cleanup);

function renderAt(path: string) {
    return render(
        <MemoryRouter initialEntries={[path]}>
            <Navbar />
        </MemoryRouter>,
    );
}

describe("<Navbar />", () => {
    it("renders both navigation links", () => {
        renderAt("/trading");
        expect(screen.getByText("Trading")).not.toBeNull();
        expect(screen.getByText("Charts")).not.toBeNull();
    });

    it("marks Trading active on /trading, not Charts", () => {
        renderAt("/trading");
        expect(screen.getByText("Trading").className).toContain("nav__link--active");
        expect(screen.getByText("Charts").className).not.toContain("nav__link--active");
    });

    it("marks Charts active on /charts, not Trading", () => {
        renderAt("/charts");
        expect(screen.getByText("Charts").className).toContain("nav__link--active");
        expect(screen.getByText("Trading").className).not.toContain("nav__link--active");
    });
});

describe("<Navbar /> page names (P17-1)", () => {
    it("renders the Charts label from PAGE_NAMES and links it to /charts", () => {
        renderAt("/trading");
        const link = screen.getByRole("link", { name: PAGE_NAMES.charts });
        expect(link.textContent).toBe("Charts");
        expect(link.getAttribute("href")).toBe("/charts");
    });
});
