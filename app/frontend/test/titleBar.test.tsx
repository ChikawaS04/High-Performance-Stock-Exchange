import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { TitleBar } from "../src/components/TitleBar";
import type { ConnectionStatus } from "../src/state/reducer";

// No globals, so RTL's auto-cleanup never registers; wire it explicitly.
afterEach(cleanup);

describe("<TitleBar /> (DESIGN_SYSTEM.md §15.3)", () => {
    it("renders the directROUTE lockup as text", () => {
        render(<TitleBar connection="open" />);
        expect(screen.getByTestId("titlebar-lockup").textContent).toBe("directROUTE →");
    });

    it("carries no icon (no img or svg anywhere in the bar)", () => {
        render(<TitleBar connection="open" />);
        const bar = screen.getByTestId("titlebar");
        expect(bar.querySelector("img, svg")).toBeNull();
    });

    it("names the venue CREDO", () => {
        render(<TitleBar connection="open" />);
        const venue = screen.getByTestId("titlebar-venue");
        expect(venue.textContent).toContain("Venue");
        expect(venue.textContent).toContain("CREDO");
    });

    it("is a plain band: not a landmark and nothing focusable", () => {
        render(<TitleBar connection="open" />);
        const bar = screen.getByTestId("titlebar");
        expect(bar.tagName).toBe("DIV");
        expect(bar.getAttribute("role")).toBeNull();
        expect(bar.querySelector("button, a, input, [tabindex]")).toBeNull();
    });

    const cases: ReadonlyArray<readonly [ConnectionStatus, string, string]> = [
        ["open", "Live", "badge--live"],
        ["connecting", "Connecting", "badge--connecting"],
        ["reconnecting", "Reconnecting", "badge--reconnecting"],
    ];
    for (const [status, label, modifier] of cases) {
        it(`renders the connection badge inside the bar when ${status}`, () => {
            render(<TitleBar connection={status} />);
            const bar = screen.getByTestId("titlebar");
            const badge = screen.getByTestId("connection-badge");
            expect(bar.contains(badge)).toBe(true);
            expect(badge.textContent).toContain(label);
            expect(badge.className).toContain(modifier);
        });
    }
});
