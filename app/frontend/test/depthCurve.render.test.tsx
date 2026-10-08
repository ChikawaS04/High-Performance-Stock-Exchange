import { afterEach, describe, it, expect, vi } from "vitest";
import { render, screen, cleanup, fireEvent } from "@testing-library/react";

import { DepthCurve } from "../src/components/DepthCurve";
import type { BookState } from "../src/state/reducer";
import type { Level } from "../src/protocol/messages";

// P5-0 chose no `globals: true`, so RTL's auto-cleanup never registers. Wire it
// explicitly to keep renders isolated.
afterEach(cleanup);

const BIDS: Level[] = [
    [1500000, 10],
    [1499000, 4],
];
const ASKS: Level[] = [
    [1502500, 5],
    [1505000, 3],
];

function book(partial: Partial<BookState>): BookState {
    return { bestBid: -1, bestAsk: -1, bids: [], asks: [], timestamp: 0, ...partial };
}

function twoSided(): BookState {
    return book({ bestBid: 1500000, bestAsk: 1502500, bids: BIDS, asks: ASKS });
}

describe("DepthCurve (click-to-price mapping)", () => {
    it("maps a click on a bid level's band to that level's price in units", () => {
        const onPriceSelect = vi.fn();
        render(<DepthCurve book={twoSided()} onPriceSelect={onPriceSelect} />);

        fireEvent.click(screen.getByTestId("curve-hit-1500000"));
        expect(onPriceSelect).toHaveBeenCalledWith(1500000);

        fireEvent.click(screen.getByTestId("curve-hit-1499000"));
        expect(onPriceSelect).toHaveBeenCalledWith(1499000);
    });

    it("maps a click on an ask level's band to that level's price in units (real level, snapped)", () => {
        const onPriceSelect = vi.fn();
        render(<DepthCurve book={twoSided()} onPriceSelect={onPriceSelect} />);

        fireEvent.click(screen.getByTestId("curve-hit-1502500"));
        expect(onPriceSelect).toHaveBeenCalledWith(1502500);

        fireEvent.click(screen.getByTestId("curve-hit-1505000"));
        expect(onPriceSelect).toHaveBeenCalledWith(1505000);
    });

    it("exposes exactly one hit band per real level across both sides", () => {
        const { container } = render(<DepthCurve book={twoSided()} />);
        const bands = container.querySelectorAll('[data-testid^="curve-hit-"]');
        expect(bands).toHaveLength(4); // two bids + two asks, distinct prices
    });

    it("does not throw when a band is clicked with no handler wired", () => {
        render(<DepthCurve book={twoSided()} />);
        expect(() => fireEvent.click(screen.getByTestId("curve-hit-1500000"))).not.toThrow();
    });
});

describe("DepthCurve (mid marker and guards)", () => {
    it("marks the mid, labelled exactly, on a two-sided book", () => {
        render(<DepthCurve book={twoSided()} />);
        const mid = screen.getByTestId("depth-curve-mid");
        // midpointLabel(1500000, 1502500) -> "150.125"; numeric mid 1501250 positions it
        expect(mid.textContent).toContain("150.125");
    });

    it("plots a one-sided book with no mid marker and only the present side's bands", () => {
        const { container } = render(
            <DepthCurve book={book({ bestAsk: 1502500, asks: [[1502500, 5]] })} />,
        );
        expect(screen.queryByTestId("depth-curve-mid")).toBeNull();
        expect(screen.getByTestId("curve-hit-1502500")).not.toBeNull();
        expect(screen.queryByTestId("curve-hit-1500000")).toBeNull();
        expect(container.querySelectorAll('[data-testid^="curve-hit-"]')).toHaveLength(1);
    });

    it("renders an empty book with no mid and no bands, no crash", () => {
        const { container } = render(<DepthCurve book={book({})} />);
        expect(screen.getByTestId("depth-curve")).not.toBeNull();
        expect(screen.queryByTestId("depth-curve-mid")).toBeNull();
        expect(container.querySelectorAll('[data-testid^="curve-hit-"]')).toHaveLength(0);
    });
});
