import { afterEach, describe, it, expect } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";

import { PriceChart } from "../src/components/PriceChart";
import type { TapeEntry } from "../src/state/reducer";

// P5-0 chose no `globals: true`, so RTL's auto-cleanup never registers. Wire it
// explicitly to keep renders isolated, matching depthCurve.render.test.tsx.
afterEach(cleanup);

/**
 * Minimal TapeEntry fixture. PriceChart reads only what buildPriceSeries reads
 * (`timestamp` and `priceCents`); every other field gets a fixed default here.
 * Built by hand, never via IDGenerator (P12: local fixtures only).
 */
function entry(partial: Partial<TapeEntry>): TapeEntry {
    return {
        tradeId: 0,
        priceCents: 10000,
        quantity: 1,
        aggressorOrderId: 0,
        passiveOrderId: 0,
        timestamp: 0,
        mine: false,
        ...partial,
    };
}

// Newest-first, as the reducer holds the tape. buildPriceSeries reverses it to
// oldest-first for the line. Price domain here is [10000, 10200].
const MULTI: TapeEntry[] = [
    entry({ timestamp: 300, priceCents: 10050 }), // newest
    entry({ timestamp: 200, priceCents: 10200 }),
    entry({ timestamp: 100, priceCents: 10000 }), // oldest
];

// No reference in these base cases: sessionOpenCents = -1 (the reducer's
// SESSION_OPEN_UNSET sentinel) is not > 0, so no session line draws, and omitting
// openCents draws no market line. This keeps the render/guard cases about the line
// and marker only; the reference cases are a separate block below.
const NO_REF = -1;

describe("PriceChart (render and guards)", () => {
    it("renders the empty state (No trades yet) and no line for an empty tape", () => {
        render(<PriceChart tape={[]} sessionOpenCents={NO_REF} />);
        expect(screen.getByTestId("price-chart")).not.toBeNull();
        expect(screen.getByTestId("price-chart").textContent).toContain("No trades yet");
        expect(screen.queryByTestId("price-chart-line")).toBeNull();
        expect(screen.queryByTestId("price-chart-last")).toBeNull();
    });

    it("renders only the last-point marker and no line for a single print", () => {
        render(
            <PriceChart
                tape={[entry({ timestamp: 100, priceCents: 10000 })]}
                sessionOpenCents={NO_REF}
            />,
        );
        expect(screen.getByTestId("price-chart-last")).not.toBeNull();
        expect(screen.queryByTestId("price-chart-line")).toBeNull();
    });

    it("renders both the price line and the last-point marker for a multi-print tape", () => {
        render(<PriceChart tape={MULTI} sessionOpenCents={NO_REF} />);
        expect(screen.getByTestId("price-chart-line")).not.toBeNull();
        expect(screen.getByTestId("price-chart-last")).not.toBeNull();
    });

    it("draws the line without throwing when all prices are equal (cMin === cMax y-guard)", () => {
        const flat: TapeEntry[] = [
            entry({ timestamp: 300, priceCents: 10000 }),
            entry({ timestamp: 200, priceCents: 10000 }),
            entry({ timestamp: 100, priceCents: 10000 }),
        ];
        expect(() => render(<PriceChart tape={flat} sessionOpenCents={NO_REF} />)).not.toThrow();
        expect(screen.getByTestId("price-chart-line")).not.toBeNull();
        expect(screen.getByTestId("price-chart-last")).not.toBeNull();
    });
});

describe("PriceChart (reference lines)", () => {
    it("draws the session-open reference when sessionOpenCents is in domain", () => {
        // 10100 sits inside [10000, 10200].
        render(<PriceChart tape={MULTI} sessionOpenCents={10100} />);
        expect(screen.getByTestId("price-chart-ref-session")).not.toBeNull();
    });

    it("omits the session-open reference when sessionOpenCents is out of domain", () => {
        // 9000 is below the domain [10000, 10200].
        render(<PriceChart tape={MULTI} sessionOpenCents={9000} />);
        expect(screen.queryByTestId("price-chart-ref-session")).toBeNull();
    });

    it("omits the session-open reference for the unset sentinel (-1)", () => {
        render(<PriceChart tape={MULTI} sessionOpenCents={NO_REF} />);
        expect(screen.queryByTestId("price-chart-ref-session")).toBeNull();
    });

    it("draws the market-open reference when openCents is in domain", () => {
        // 10150 sits inside [10000, 10200].
        render(<PriceChart tape={MULTI} sessionOpenCents={NO_REF} openCents={10150} />);
        expect(screen.getByTestId("price-chart-ref-market")).not.toBeNull();
    });

    it("omits the market-open reference when openCents is far out of domain, and still draws the line across the full plot", () => {
        // The ASML market open (~$1740.01) against synthetic prints near $100: the
        // market open must not draw and must not widen the domain, so the price line
        // still renders over the actual prints (D5).
        render(<PriceChart tape={MULTI} sessionOpenCents={NO_REF} openCents={174001} />);
        expect(screen.queryByTestId("price-chart-ref-market")).toBeNull();
        expect(screen.getByTestId("price-chart-line")).not.toBeNull();
    });
});
