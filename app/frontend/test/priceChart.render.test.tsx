import { afterEach, describe, it, expect } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import type { TapeEntry } from "../src/state/reducer";

// P5-0 chose no `globals: true`, so RTL's auto-cleanup never registers. Wire it
// explicitly to keep renders isolated, matching depthCurve.render.test.tsx.
afterEach(cleanup);

/**
 * Minimal TapeEntry fixture. PriceChart reads only what buildPriceSeries reads
 * (`timestamp` and `pricePx`); every other field gets a fixed default here.
 * Built by hand, never via IDGenerator (P12: local fixtures only).
 */
function entry(partial: Partial<TapeEntry>): TapeEntry {
    return {
        tradeId: 0,
        pricePx: 1000000,
        quantity: 1,
        aggressorOrderId: 0,
        passiveOrderId: 0,
        timestamp: 0,
        mine: false,
        ...partial,
    };
}

// Newest-first, as the reducer holds the tape. buildPriceSeries reverses it to
// oldest-first for the line. Price domain here is [1000000, 1020000].
const MULTI: TapeEntry[] = [
    entry({ timestamp: 300, pricePx: 1005000 }), // newest
    entry({ timestamp: 200, pricePx: 1020000 }),
    entry({ timestamp: 100, pricePx: 1000000 }), // oldest
];

// No reference in these base cases: sessionOpenPx = -1 (the reducer's
// SESSION_OPEN_UNSET sentinel) is not > 0, so no session line draws, and omitting
// openPx draws no market line. This keeps the render/guard cases about the line
// and marker only; the reference cases are a separate block below.
const NO_REF = -1;

describe("PriceChart (render and guards)", () => {
    it("renders the empty state (No trades yet) and no line for an empty tape", () => {
        render(<PriceChart tape={[]} sessionOpenPx={NO_REF} />);
        expect(screen.getByTestId("price-chart")).not.toBeNull();
        expect(screen.getByTestId("price-chart").textContent).toContain("No trades yet");
        expect(screen.queryByTestId("price-chart-line")).toBeNull();
        expect(screen.queryByTestId("price-chart-last")).toBeNull();
    });

    it("renders only the last-point marker and no line for a single print", () => {
        render(
            <PriceChart
                tape={[entry({ timestamp: 100, pricePx: 1000000 })]}
                sessionOpenPx={NO_REF}
            />,
        );
        expect(screen.getByTestId("price-chart-last")).not.toBeNull();
        expect(screen.queryByTestId("price-chart-line")).toBeNull();
    });

    it("renders both the price line and the last-point marker for a multi-print tape", () => {
        render(<PriceChart tape={MULTI} sessionOpenPx={NO_REF} />);
        expect(screen.getByTestId("price-chart-line")).not.toBeNull();
        expect(screen.getByTestId("price-chart-last")).not.toBeNull();
    });

    it("draws the line without throwing when all prices are equal (cMin === cMax y-guard)", () => {
        const flat: TapeEntry[] = [
            entry({ timestamp: 300, pricePx: 1000000 }),
            entry({ timestamp: 200, pricePx: 1000000 }),
            entry({ timestamp: 100, pricePx: 1000000 }),
        ];
        expect(() => render(<PriceChart tape={flat} sessionOpenPx={NO_REF} />)).not.toThrow();
        expect(screen.getByTestId("price-chart-line")).not.toBeNull();
        expect(screen.getByTestId("price-chart-last")).not.toBeNull();
    });
});

describe("PriceChart (reference lines)", () => {
    it("draws the session-open reference when sessionOpenPx is in domain", () => {
        // 1010000 sits inside [1000000, 1020000].
        render(<PriceChart tape={MULTI} sessionOpenPx={1010000} />);
        expect(screen.getByTestId("price-chart-ref-session")).not.toBeNull();
    });

    it("omits the session-open reference when sessionOpenPx is out of domain", () => {
        // 900000 is below the domain [1000000, 1020000].
        render(<PriceChart tape={MULTI} sessionOpenPx={900000} />);
        expect(screen.queryByTestId("price-chart-ref-session")).toBeNull();
    });

    it("omits the session-open reference for the unset sentinel (-1)", () => {
        render(<PriceChart tape={MULTI} sessionOpenPx={NO_REF} />);
        expect(screen.queryByTestId("price-chart-ref-session")).toBeNull();
    });

    it("draws the market-open reference when openPx is in domain", () => {
        // 1015000 sits inside [1000000, 1020000].
        render(<PriceChart tape={MULTI} sessionOpenPx={NO_REF} openPx={1015000} />);
        expect(screen.getByTestId("price-chart-ref-market")).not.toBeNull();
    });

    it("omits the market-open reference when openPx is far out of domain, and still draws the line across the full plot", () => {
        // The ASML market open (~$1740.01) against synthetic prints near $100: the
        // market open must not draw and must not widen the domain, so the price line
        // still renders over the actual prints (D5).
        render(<PriceChart tape={MULTI} sessionOpenPx={NO_REF} openPx={17400100} />);
        expect(screen.queryByTestId("price-chart-ref-market")).toBeNull();
        expect(screen.getByTestId("price-chart-line")).not.toBeNull();
    });
});
