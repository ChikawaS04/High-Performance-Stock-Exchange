import { afterEach, describe, it, expect } from "vitest";
import { render, screen, cleanup, fireEvent } from "@testing-library/react";

import { DepthLadder, buildLadder, spreadLabel } from "../src/components/DepthLadder";
import { EMPTY_PRICE } from "../src/format";
import type { BookState } from "../src/state/reducer";
import type { Level } from "../src/protocol/messages";

// P5-0 chose no `globals: true`, so RTL's auto-cleanup (which looks for a global
// afterEach) never registers. Wire it explicitly to keep renders isolated.
afterEach(cleanup);

function book(partial: Partial<BookState>): BookState {
    return { bestBid: -1, bestAsk: -1, bids: [], asks: [], timestamp: 0, ...partial };
}

const BIDS: Level[] = [
    [1500000, 10],
    [1499000, 4],
];
const ASKS: Level[] = [
    [1502500, 5],
    [1505000, 3],
];

describe("buildLadder (pure)", () => {
    it("accumulates quantity best-first on each side", () => {
        const m = buildLadder(BIDS, ASKS);
        // bids highest-first: 1500000 (cum 10), 1499000 (cum 14)
        expect(m.bids.map((r) => r.pricePx)).toEqual([1500000, 1499000]);
        expect(m.bids.map((r) => r.cumQty)).toEqual([10, 14]);
        // P9-2: asks are returned touch-first (lowest-first): 1502500 (cum 5), 1505000
        // (cum 8). CSS column-reverse paints them highest-on-top at render; the model
        // is touch-first so cumQty rises down the array, same as the bids.
        expect(m.asks.map((r) => r.pricePx)).toEqual([1502500, 1505000]);
        expect(m.asks.map((r) => r.cumQty)).toEqual([5, 8]);
    });

    it("scales both sides to the shared max (imbalance is visible)", () => {
        // bid depth 14, ask depth 8 -> sharedMax 14
        const m = buildLadder(BIDS, ASKS);
        const bidWidths = m.bids.map((r) => r.widthPct);
        expect(bidWidths[bidWidths.length - 1]).toBe(100); // furthest bid = full
        // furthest ask is the LAST element now (touch-first order): cum 8 of shared 14
        expect(m.asks[m.asks.length - 1].widthPct).toBeCloseTo((8 / 14) * 100);
        // best ask (nearest the touch) is lighter
        expect(m.asks[0].widthPct).toBeCloseTo((5 / 14) * 100);
    });

    it("bar width grows monotonically outward from the mid on both sides", () => {
        const m = buildLadder(BIDS, ASKS);
        // both sides are touch-first, so width rises down each returned array
        expect(m.bids[0].widthPct).toBeLessThanOrEqual(m.bids[1].widthPct);
        expect(m.asks[0].widthPct).toBeLessThanOrEqual(m.asks[1].widthPct);
    });

    it("returns both sides touch-first: bids highest-first, asks lowest-first", () => {
        const m = buildLadder(
            [
                [1500000, 10],
                [1499000, 4],
                [1498000, 2],
            ],
            [
                [1502500, 5],
                [1505000, 3],
                [1507500, 1],
            ],
        );
        expect(m.bids.map((r) => r.pricePx)).toEqual([1500000, 1499000, 1498000]);
        expect(m.asks.map((r) => r.pricePx)).toEqual([1502500, 1505000, 1507500]);
    });

    it("handles an empty book without NaN", () => {
        const m = buildLadder([], []);
        expect(m.asks).toEqual([]);
        expect(m.bids).toEqual([]);
    });

    it("handles a one-sided book (present side scales to its own max)", () => {
        const asksOnly = buildLadder([], ASKS);
        expect(asksOnly.bids).toEqual([]);
        // furthest ask is the last element (touch-first): cum 8 / max 8
        expect(asksOnly.asks[asksOnly.asks.length - 1].widthPct).toBe(100);

        const bidsOnly = buildLadder([[1500000, 10]], []);
        expect(bidsOnly.asks).toEqual([]);
        expect(bidsOnly.bids[0].widthPct).toBe(100);
    });
});

describe("buildLadder (P9-2 full-book render and shared-max shading)", () => {
    it("renders every level the book carries, with no depth cap", () => {
        const bids: Level[] = [
            [1500000, 5],
            [1499000, 5],
            [1498000, 5],
            [1497000, 5],
        ];
        const asks: Level[] = [
            [1502500, 5],
            [1505000, 5],
            [1507500, 5],
            [1510000, 5],
        ];
        const m = buildLadder(bids, asks);
        expect(m.bids.map((r) => r.pricePx)).toEqual([1500000, 1499000, 1498000, 1497000]);
        // touch-first (lowest-first); column-reverse paints highest-on-top at render
        expect(m.asks.map((r) => r.pricePx)).toEqual([1502500, 1505000, 1507500, 1510000]);
    });

    it("renders a book deeper than the pre-P9 10-level cap in full", () => {
        // 20 levels per side is the new server top-N (P9-1); the ladder must render
        // all of them and let the pane scroll (P9-2), where before it stopped at 10.
        // One-cent level spacing is 100 units, so the step is i * 1000 (ten ticks).
        const deepBids: Level[] = Array.from({ length: 20 }, (_, i): Level => [1500000 - i * 1000, 1]);
        const m = buildLadder(deepBids, []);
        expect(m.bids).toHaveLength(20);
        expect(m.bids[0].pricePx).toBe(1500000); // best bid first (touch-first)
        expect(m.bids[m.bids.length - 1].cumQty).toBe(20); // full-book cumulation
    });

    it("cumulative quantity is monotonic from the touch outward on each side", () => {
        const bids: Level[] = [
            [1500000, 3],
            [1499000, 1],
            [1498000, 4],
        ];
        const asks: Level[] = [
            [1502500, 2],
            [1505000, 6],
            [1507500, 1],
        ];
        const m = buildLadder(bids, asks);

        // both sides are touch-first, so cumulation runs down each array and rises
        const bidCum = m.bids.map((r) => r.cumQty);
        expect(bidCum).toEqual([3, 4, 8]);
        for (let i = 1; i < bidCum.length; i++) {
            expect(bidCum[i]).toBeGreaterThanOrEqual(bidCum[i - 1]);
        }

        const askCum = m.asks.map((r) => r.cumQty);
        expect(askCum).toEqual([2, 8, 9]);
        for (let i = 1; i < askCum.length; i++) {
            expect(askCum[i]).toBeGreaterThanOrEqual(askCum[i - 1]);
        }
    });

    it("bounds every shading fraction to [0,1], saturating the heavy side at exactly 1", () => {
        const m = buildLadder(BIDS, ASKS);
        for (const r of [...m.bids, ...m.asks]) {
            expect(r.shadeFraction).toBeGreaterThanOrEqual(0);
            expect(r.shadeFraction).toBeLessThanOrEqual(1);
        }
        expect(m.bids[m.bids.length - 1].shadeFraction).toBe(1); // furthest bid, heaviest side
    });

    it("normalises shading to the largest cumulative value across the FULL book", () => {
        const bids: Level[] = [
            [1500000, 1],
            [1499000, 1],
            [1498000, 100],
        ];
        const asks: Level[] = [
            [1502500, 1],
            [1505000, 1],
        ];

        // the 100-lot deep bid dominates the shared max (102), so asks read as slivers;
        // there is no longer a visible window that could rescale this away (P9-2).
        const m = buildLadder(bids, asks);
        expect(m.bids[m.bids.length - 1].shadeFraction).toBe(1);
        expect(m.asks[0].shadeFraction).toBeCloseTo(1 / 102); // best ask, cum 1
        expect(m.asks[m.asks.length - 1].shadeFraction).toBeCloseTo(2 / 102); // furthest ask, cum 2
    });

    it("an empty or one-sided book yields zero shading with no NaN", () => {
        const empty = buildLadder([], []);
        expect(empty.bids).toEqual([]);
        expect(empty.asks).toEqual([]);

        const asksOnly = buildLadder([], ASKS);
        for (const r of asksOnly.asks) {
            expect(Number.isNaN(r.shadeFraction)).toBe(false);
            expect(Number.isNaN(r.widthPct)).toBe(false);
        }
        // present side scales to its own max: furthest ask saturates at 1
        expect(asksOnly.asks[asksOnly.asks.length - 1].shadeFraction).toBe(1);
    });

    it("emits exactly one row per real level and never a stale tail", () => {
        expect(buildLadder([[1500000, 1]], []).bids).toHaveLength(1);
        expect(buildLadder([], []).bids).toHaveLength(0);

        // BOOK is authoritative and replaced wholesale, so a shrunk input shrinks the model
        const wide = buildLadder([[1500000, 1], [1499000, 1], [1498000, 1]], []);
        const narrow = buildLadder([[1500000, 1]], []);
        expect(wide.bids).toHaveLength(3);
        expect(narrow.bids).toHaveLength(1);
    });
});

describe("spreadLabel (sentinel guard)", () => {
    it("computes spread only when both tops are real", () => {
        expect(spreadLabel(1500000, 1502500)).toBe("0.25");
    });

    it("returns EMPTY_PRICE when either or both tops are the -1 sentinel", () => {
        expect(spreadLabel(-1, 1502500)).toBe(EMPTY_PRICE);
        expect(spreadLabel(1500000, -1)).toBe(EMPTY_PRICE);
        expect(spreadLabel(-1, -1)).toBe(EMPTY_PRICE);
    });
});

describe("DepthLadder (render)", () => {
    it("renders one row per level with dollar-formatted prices", () => {
        render(
            <DepthLadder book={book({ bestBid: 1500000, bestAsk: 1502500, bids: BIDS, asks: ASKS })} />,
        );
        expect(screen.getAllByTestId("ask-row")).toHaveLength(2);
        expect(screen.getAllByTestId("bid-row")).toHaveLength(2);
        expect(screen.queryByText("150.25")).not.toBeNull();
        expect(screen.queryByText("150.00")).not.toBeNull();
    });

    it("emits asks touch-first and bids touch-first in the DOM (CSS paints asks bottom-up)", () => {
        render(
            <DepthLadder book={book({ bestBid: 1500000, bestAsk: 1502500, bids: BIDS, asks: ASKS })} />,
        );
        // P9-2: DOM order is best-first on both sides. The asks pane's CSS
        // column-reverse paints the best ask at the bottom near the divider; jsdom
        // applies no layout, so we assert DOM order and verify the visual live (P9-3).
        const askRows = screen.getAllByTestId("ask-row");
        expect(askRows[0].textContent).toContain("150.25"); // best ask, first in DOM
        expect(askRows[askRows.length - 1].textContent).toContain("150.50"); // deepest ask

        const bidRows = screen.getAllByTestId("bid-row");
        expect(bidRows[0].textContent).toContain("150.00"); // best bid nearest mid
        expect(bidRows[bidRows.length - 1].textContent).toContain("149.90");
    });

    it("shows the true spread and never leaks a sentinel as a price", () => {
        render(
            <DepthLadder book={book({ bestBid: 1500000, bestAsk: 1502500, bids: BIDS, asks: ASKS })} />,
        );
        expect(screen.getByTestId("spread-value").textContent).toBe("0.25");

        const rows = [...screen.getAllByTestId("ask-row"), ...screen.getAllByTestId("bid-row")];
        for (const r of rows) {
            const price = r.querySelector(".depth-ladder__price")?.textContent ?? "";
            expect(price).not.toBe(EMPTY_PRICE);
            expect(price.startsWith("-")).toBe(false);
        }

        // furthest bid is the heavy side here -> full-width bar (ties pure math to DOM)
        const bidRows = screen.getAllByTestId("bid-row");
        const furthestBar = bidRows[bidRows.length - 1].querySelector(
            ".depth-ladder__bar",
        ) as HTMLElement;
        expect(furthestBar.style.width).toBe("100%");
    });

    it("renders a one-sided book cleanly with a guarded spread", () => {
        render(
            <DepthLadder book={book({ bestBid: -1, bestAsk: 1502500, bids: [], asks: [[1502500, 5]] })} />,
        );
        expect(screen.queryAllByTestId("bid-row")).toHaveLength(0);
        expect(screen.getAllByTestId("ask-row")).toHaveLength(1);
        // best ask price still shows as a row, but the spread is guarded to EMPTY_PRICE
        expect(screen.getByTestId("spread-value").textContent).toBe(EMPTY_PRICE);
    });

    it("renders an empty book with no rows and an empty spread", () => {
        render(<DepthLadder book={book({})} />);
        expect(screen.queryAllByTestId("ask-row")).toHaveLength(0);
        expect(screen.queryAllByTestId("bid-row")).toHaveLength(0);
        expect(screen.getByTestId("spread-value").textContent).toBe(EMPTY_PRICE);
    });
});

describe("DepthLadder (cumulative column, divider, depth viewport)", () => {
    it("renders the cumulative-quantity column alongside per-level size", () => {
        render(
            <DepthLadder book={book({ bestBid: 1500000, bestAsk: 1502500, bids: BIDS, asks: ASKS })} />,
        );
        const bidRows = screen.getAllByTestId("bid-row");
        const cum = bidRows.map((r) => r.querySelector(".depth-ladder__cum")?.textContent);
        expect(cum).toEqual(["10", "14"]); // BIDS cumulate 10, then 14 down the list
    });

    it("shows spread, mid, and last in the divider", () => {
        render(
            <DepthLadder
                book={book({ bestBid: 1500000, bestAsk: 1502500, bids: BIDS, asks: ASKS })}
                lastPx={1502500}
            />,
        );
        expect(screen.getByTestId("spread-value").textContent).toBe("0.25");
        expect(screen.getByTestId("mid-value").textContent).toBe("150.125");
        expect(screen.getByTestId("last-value").textContent).toBe("150.25");
    });

    it("blanks mid and last on an empty book, with no divide-by-zero", () => {
        render(<DepthLadder book={book({})} />);
        expect(screen.getByTestId("spread-value").textContent).toBe(EMPTY_PRICE);
        expect(screen.getByTestId("mid-value").textContent).toBe(EMPTY_PRICE);
        expect(screen.getByTestId("last-value").textContent).toBe(EMPTY_PRICE);
    });

    it("blanks last before the first trade even with a two-sided book", () => {
        render(
            <DepthLadder book={book({ bestBid: 1500000, bestAsk: 1502500, bids: BIDS, asks: ASKS })} />,
        );
        expect(screen.getByTestId("last-value").textContent).toBe(EMPTY_PRICE); // no lastPx -> -1 -> blank
        expect(screen.getByTestId("mid-value").textContent).toBe("150.125"); // mid still live from the book
    });

    it("sizes the viewport to the selected depth without changing how many rows render (P9-2)", () => {
        const wideBook = book({
            bestBid: 1500000,
            bestAsk: 1502500,
            bids: [
                [1500000, 1],
                [1499000, 1],
                [1498000, 1],
                [1497000, 1],
                [1496000, 1],
                [1495000, 1],
                [1494000, 1],
                [1493000, 1],
                [1492000, 1],
                [1491000, 1],
                [1490000, 1],
                [1489000, 1],
            ],
            asks: [[1502500, 1]],
        });
        const { container } = render(<DepthLadder book={wideBook} />);
        const bidsPane = () => container.querySelector(".depth-ladder__bids") as HTMLElement;

        // the full book renders regardless of the selector; the selector sizes the pane
        expect(screen.getAllByTestId("bid-row")).toHaveLength(12);
        expect(bidsPane().style.getPropertyValue("--depth-rows")).toBe("10"); // default depth

        fireEvent.change(screen.getByTestId("depth-select"), { target: { value: "8" } });
        expect(screen.getAllByTestId("bid-row")).toHaveLength(12); // same rows, deeper than the viewport
        expect(bidsPane().style.getPropertyValue("--depth-rows")).toBe("8"); // viewport shrank
    });

    it("never shows a stale tail when the book shrinks", () => {
        const { rerender } = render(
            <DepthLadder
                book={book({ bestBid: 1500000, bestAsk: -1, bids: [[1500000, 1], [1499000, 1], [1498000, 1]], asks: [] })}
            />,
        );
        expect(screen.getAllByTestId("bid-row")).toHaveLength(3);

        rerender(
            <DepthLadder book={book({ bestBid: 1500000, bestAsk: -1, bids: [[1500000, 1]], asks: [] })} />,
        );
        expect(screen.getAllByTestId("bid-row")).toHaveLength(1);
    });
});

describe("DepthLadder (P8-4 two-pane scroll layout)", () => {
    it("splits asks and bids into two depth-sized scroll panes with the spread bar pinned between them", () => {
        const { container } = render(
            <DepthLadder
                book={book({ bestBid: 1500000, bestAsk: 1502500, bids: BIDS, asks: ASKS })}
                lastPx={1502500}
            />,
        );

        const asksPane = container.querySelector(".depth-ladder__asks") as HTMLElement;
        const bidsPane = container.querySelector(".depth-ladder__bids") as HTMLElement;
        const divider = screen
            .getByTestId("spread-value")
            .closest(".depth-ladder__divider") as HTMLElement;

        // two distinct pane elements plus the divider
        expect(asksPane).not.toBeNull();
        expect(bidsPane).not.toBeNull();
        expect(divider).not.toBeNull();
        expect(asksPane).not.toBe(bidsPane);

        // each side's rows live only in their own pane -> independent scroll containers
        for (const r of screen.getAllByTestId("ask-row")) {
            expect(asksPane.contains(r)).toBe(true);
        }
        for (const r of screen.getAllByTestId("bid-row")) {
            expect(bidsPane.contains(r)).toBe(true);
        }
        expect(asksPane.querySelectorAll('[data-testid="bid-row"]')).toHaveLength(0);
        expect(bidsPane.querySelectorAll('[data-testid="ask-row"]')).toHaveLength(0);

        // the spread bar is pinned between the panes and scrolls with neither side
        expect(asksPane.contains(divider)).toBe(false);
        expect(bidsPane.contains(divider)).toBe(false);
        expect(
            asksPane.compareDocumentPosition(divider) & Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy();
        expect(
            divider.compareDocumentPosition(bidsPane) & Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy();

        // each pane is sized to the selected Depth window (Q8-5); default depth is 10
        expect(asksPane.style.getPropertyValue("--depth-rows")).toBe("10");
        expect(bidsPane.style.getPropertyValue("--depth-rows")).toBe("10");

        // the sizing hook tracks the selector, symmetrically across both panes
        fireEvent.change(screen.getByTestId("depth-select"), { target: { value: "14" } });
        expect(
            (container.querySelector(".depth-ladder__asks") as HTMLElement).style.getPropertyValue(
                "--depth-rows",
            ),
        ).toBe("14");
        expect(
            (container.querySelector(".depth-ladder__bids") as HTMLElement).style.getPropertyValue(
                "--depth-rows",
            ),
        ).toBe("14");
    });
});
