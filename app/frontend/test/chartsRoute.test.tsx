import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

// P17-1: the router test runs the real App routes. The socket and the ignition
// fetch are replaced so the test opens no connection and no network request.
vi.mock("../src/state/useOrderBook", async () => {
    const { initialState } = await import("../src/state/reducer");
    return { useOrderBook: () => ({ state: initialState, send: () => false, dispatchLocal: () => {} }) };
});
vi.mock("../src/state/useIgnitionPrice", () => ({ useIgnitionPrice: () => ({ openPx: undefined }) }));

import App from "../src/App";

afterEach(cleanup);

describe("Charts route (P17-1)", () => {
    it("redirects the old /chart route to the Charts page", () => {
        document.title = "directROUTE";
        render(
            <MemoryRouter initialEntries={["/chart"]}>
                <App />
            </MemoryRouter>,
        );
        expect(document.title).toBe("ASML · Charts | directROUTE");
        expect(screen.getByRole("link", { name: "Charts" }).className).toContain("nav__link--active");
        expect(screen.getByRole("main", { name: "Price chart" })).not.toBeNull();
    });

    it("serves the Charts page at /charts", () => {
        render(
            <MemoryRouter initialEntries={["/charts"]}>
                <App />
            </MemoryRouter>,
        );
        expect(document.title).toBe("ASML · Charts | directROUTE");
    });
});
