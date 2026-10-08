import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { TradeTape } from "../src/components/TradeTape";
import { EMPTY_PRICE } from "../src/format";
import type { TapeEntry } from "../src/state/reducer";

// P5-0's no-globals stance means RTL's auto-cleanup never registers; wire it
// explicitly so renders don't bleed across tests (same as depthLadder.test.tsx).
afterEach(cleanup);

/** A valid fill TapeEntry; override only the fields a case cares about. */
function entry(overrides: Partial<TapeEntry> = {}): TapeEntry {
    return {
        tradeId: 1,
        pricePx: 1500000,
        quantity: 10,
        aggressorOrderId: 2,
        passiveOrderId: 1,
        timestamp: 1_700_000_000_123_000_000,
        mine: false,
        ...overrides,
    };
}

function priceOf(row: HTMLElement): string {
    return row.querySelector(".trade-tape__price")!.textContent ?? "";
}
function qtyOf(row: HTMLElement): string {
    return row.querySelector(".trade-tape__qty")!.textContent ?? "";
}
function timeOf(row: HTMLElement): string {
    return row.querySelector(".trade-tape__time")!.textContent ?? "";
}
function sideOf(row: HTMLElement): string {
    return row.querySelector(".trade-tape__side")!.textContent ?? "";
}
function priceClassOf(row: HTMLElement): string {
    return row.querySelector(".trade-tape__price")!.className;
}

describe("TradeTape", () => {
    it("renders exactly the entries it is given (one row per TapeEntry)", () => {
        // The component only accepts TapeEntry[], which the reducer builds fills-only;
        // a 1:1 row count is the structural guarantee that non-fills never appear.
        const tape = [entry({ tradeId: 3 }), entry({ tradeId: 2 }), entry({ tradeId: 1 })];
        render(<TradeTape tape={tape} />);
        expect(screen.getAllByTestId("tape-row")).toHaveLength(3);
    });

    it("preserves slice order (newest-first as handed in)", () => {
        const tape = [
            entry({ tradeId: 3, pricePx: 1503000 }),
            entry({ tradeId: 2, pricePx: 1502000 }),
            entry({ tradeId: 1, pricePx: 1501000 }),
        ];
        render(<TradeTape tape={tape} />);
        const prices = screen.getAllByTestId("tape-row").map(priceOf);
        expect(prices).toEqual(["150.30", "150.20", "150.10"]);
    });

    it("renders price in dollars via format.ts, never a sentinel or negative", () => {
        const tape = [entry({ tradeId: 1, pricePx: 500 }), entry({ tradeId: 2, pricePx: 1502500 })];
        render(<TradeTape tape={tape} />);
        const prices = screen.getAllByTestId("tape-row").map(priceOf);
        expect(prices).toEqual(["0.05", "150.25"]);
        for (const p of prices) {
            expect(p).not.toBe(EMPTY_PRICE);
            expect(p.startsWith("-")).toBe(false);
            expect(p).toMatch(/^\d+\.\d{2}$/);
        }
    });

    it("renders the filled quantity", () => {
        render(<TradeTape tape={[entry({ quantity: 7 })]} />);
        expect(qtyOf(screen.getByTestId("tape-row"))).toBe("7");
    });

    it("marks the client's own fills with the --mine modifier", () => {
        const tape = [entry({ tradeId: 1, mine: true }), entry({ tradeId: 2, mine: false })];
        render(<TradeTape tape={tape} />);
        const rows = screen.getAllByTestId("tape-row");
        expect(rows[0].className).toContain("trade-tape__row--mine");
        expect(rows[1].className).not.toContain("trade-tape__row--mine");
    });

    it("tags the aggressor side on own trades only, blank on anonymous prints", () => {
        // P7-6 deliberately, partially reverses the P5-3 side omission: side is shown
        // exactly where it is derivable (own-trade rows), and nowhere else.
        const tape = [
            entry({ tradeId: 2, mine: true, aggressorSide: "BUY" }),
            entry({ tradeId: 1, mine: false }),
        ];
        render(<TradeTape tape={tape} />);
        const rows = screen.getAllByTestId("tape-row");
        expect(sideOf(rows[0])).toBe("BUY");
        expect(sideOf(rows[1])).toBe("");
    });

    it("renders a local wall-clock time at millisecond precision by default", () => {
        render(<TradeTape tape={[entry({ timestamp: 1_700_000_000_123_000_000 })]} />);
        expect(timeOf(screen.getByTestId("tape-row"))).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{3}$/);
    });

    it("toggles the time column to full nanoseconds", () => {
        render(<TradeTape tape={[entry({ timestamp: 1_700_000_000_123_000_000 })]} />);
        expect(timeOf(screen.getByTestId("tape-row"))).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{3}$/);
        fireEvent.click(screen.getByTestId("tape-precision"));
        expect(timeOf(screen.getByTestId("tape-row"))).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{9}$/);
    });

    it("filters by block size, strict-greater, preserving order", () => {
        const tape = [
            entry({ tradeId: 3, quantity: 700 }),
            entry({ tradeId: 2, quantity: 300 }),
            entry({ tradeId: 1, quantity: 100 }),
        ];
        render(<TradeTape tape={tape} />);
        expect(screen.getAllByTestId("tape-row")).toHaveLength(3);

        fireEvent.click(screen.getByTestId("tape-filter-gt200"));
        expect(screen.getAllByTestId("tape-row").map(qtyOf)).toEqual(["700", "300"]);

        fireEvent.click(screen.getByTestId("tape-filter-gt500"));
        expect(screen.getAllByTestId("tape-row").map(qtyOf)).toEqual(["700"]);

        fireEvent.click(screen.getByTestId("tape-filter-all"));
        expect(screen.getAllByTestId("tape-row")).toHaveLength(3);
    });

    it("colours each price against the previous (older) print", () => {
        // newest-first: 150.20 (up vs 150.10), 150.10 (flat vs 150.10), 150.10 (oldest, flat)
        const tape = [
            entry({ tradeId: 3, pricePx: 1502000 }),
            entry({ tradeId: 2, pricePx: 1501000 }),
            entry({ tradeId: 1, pricePx: 1501000 }),
        ];
        render(<TradeTape tape={tape} />);
        const rows = screen.getAllByTestId("tape-row");
        expect(priceClassOf(rows[0])).toContain("trade-tape__price--up");
        expect(priceClassOf(rows[1])).toContain("trade-tape__price--flat");
        expect(priceClassOf(rows[2])).toContain("trade-tape__price--flat");
    });

    it("renders an empty state cleanly with no rows", () => {
        render(<TradeTape tape={[]} />);
        expect(screen.queryAllByTestId("tape-row")).toHaveLength(0);
        expect(screen.getByTestId("tape-empty")).toBeTruthy();
    });
});
