import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { SessionStats } from "../src/components/SessionStats";
import type { SessionStatsProps } from "../src/components/SessionStats";
import { EMPTY_PRICE } from "../src/format";
import { SESSION_OPEN_UNSET } from "../src/state/reducer";
import type { TapeEntry } from "../src/state/reducer";

// P5-0's no-globals stance means RTL's auto-cleanup never registers; wire it
// explicitly so renders don't bleed across tests.
afterEach(cleanup);

/**
 * Minimal TapeEntry fixture, mirroring sessionStats.test.ts and
 * priceChart.render.test.tsx. The component reads only `priceCents` and
 * `quantity` from a row, through buildSessionStats; every other field gets a
 * fixed local default. Built by hand, never via an ID generator.
 */
function entry(partial: Partial<TapeEntry> = {}): TapeEntry {
    return {
        tradeId: 1,
        priceCents: 15000,
        quantity: 1,
        aggressorOrderId: 1,
        passiveOrderId: 2,
        timestamp: 1,
        mine: false,
        ...partial,
    };
}

/** The four labels, in render order. The locked P13 D2 field set. */
const LABELS = ["High", "Low", "Trades", "Last trade"];

/** A traded session: newest first, so 150.25 x 4 is the last print. */
const POPULATED: SessionStatsProps = {
    tape: [
        entry({ tradeId: 3, priceCents: 15025, quantity: 4 }),
        entry({ tradeId: 2, priceCents: 15075, quantity: 9 }),
        entry({ tradeId: 1, priceCents: 14925, quantity: 2 }),
    ],
    sessionHighCents: 15075,
    sessionLowCents: 14925,
    sessionTradeCount: 12,
};

/** A fresh session: nothing has traded. Matches initialState's three slices. */
const UNSET: SessionStatsProps = {
    tape: [],
    sessionHighCents: SESSION_OPEN_UNSET,
    sessionLowCents: SESSION_OPEN_UNSET,
    sessionTradeCount: 0,
};

function renderStats(over: Partial<SessionStatsProps> = {}) {
    return render(<SessionStats {...POPULATED} {...over} />);
}

describe("<SessionStats /> populated session", () => {
    it("renders the four labels in order", () => {
        renderStats();
        for (const label of LABELS) {
            expect(screen.getByText(label)).not.toBeNull();
        }
    });

    it("renders high, low and the trade count from the reducer slices", () => {
        renderStats();
        expect(screen.getByTestId("session-high").textContent).toBe("150.75");
        expect(screen.getByTestId("session-low").textContent).toBe("149.25");
        expect(screen.getByTestId("session-trades").textContent).toBe("12");
    });

    it("renders the last trade's price and size from the newest tape row", () => {
        renderStats();
        // The price value nests the size sub, so assert the sub exactly and the
        // parent by containment rather than equality.
        expect(screen.getByTestId("session-last-price").textContent).toContain("150.25");
        expect(screen.getByTestId("session-last-size").textContent).toBe("4");
    });

    it("reads only the newest tape row for the last trade", () => {
        // A longer tape whose older rows carry distinctive values: if the component
        // ever folded the tape instead of reading index 0, these would leak out.
        renderStats({
            tape: [
                entry({ tradeId: 9, priceCents: 10000, quantity: 7 }),
                entry({ tradeId: 8, priceCents: 99999, quantity: 888 }),
                entry({ tradeId: 7, priceCents: 1, quantity: 999 }),
            ],
        });
        expect(screen.getByTestId("session-last-price").textContent).toContain("100.00");
        expect(screen.getByTestId("session-last-size").textContent).toBe("7");
    });

    it("groups a four-digit trade count, matching the header's Volume formatter", () => {
        renderStats({ sessionTradeCount: 1234 });
        expect(screen.getByTestId("session-trades").textContent).toBe("1,234");
    });
});

describe("<SessionStats /> fresh session", () => {
    it("renders the dash for high, low and the last trade before any print", () => {
        render(<SessionStats {...UNSET} />);
        expect(screen.getByTestId("session-high").textContent).toBe(EMPTY_PRICE);
        expect(screen.getByTestId("session-low").textContent).toBe(EMPTY_PRICE);
        expect(screen.getByTestId("session-last-price").textContent).toContain(EMPTY_PRICE);
        expect(screen.getByTestId("session-last-size").textContent).toBe(EMPTY_PRICE);
    });

    it("renders a zero trade count as 0, not the dash", () => {
        // Zero trades is a known fact, not missing data (P13 D10).
        render(<SessionStats {...UNSET} />);
        expect(screen.getByTestId("session-trades").textContent).toBe("0");
    });
});

describe("<SessionStats /> row stability", () => {
    it("mounts the same labels in the same order whether or not anything has traded", () => {
        const { unmount } = render(<SessionStats {...UNSET} />);
        const unsetLabels = Array.from(
            document.querySelectorAll(".session-stats__label"),
        ).map((el) => el.textContent);
        unmount();

        render(<SessionStats {...POPULATED} />);
        const populatedLabels = Array.from(
            document.querySelectorAll(".session-stats__label"),
        ).map((el) => el.textContent);

        expect(unsetLabels).toEqual(LABELS);
        expect(populatedLabels).toEqual(LABELS);
    });

    it("mounts all five value nodes in both states, never conditionally", () => {
        const ids = [
            "session-high",
            "session-low",
            "session-trades",
            "session-last-price",
            "session-last-size",
        ];

        const { unmount } = render(<SessionStats {...UNSET} />);
        for (const id of ids) {
            expect(screen.getByTestId(id)).not.toBeNull();
        }
        unmount();

        render(<SessionStats {...POPULATED} />);
        for (const id of ids) {
            expect(screen.getByTestId(id)).not.toBeNull();
        }
    });
});
