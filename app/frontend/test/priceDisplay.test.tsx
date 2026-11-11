import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { Header } from "../src/components/Header";
import { TradeTape } from "../src/components/TradeTape";
import { DepthCurve } from "../src/components/DepthCurve";
import { MID_LABEL_PX } from "../src/depthChartLayout";
import type { BookState, TapeEntry } from "../src/state/reducer";

// No vitest globals, so RTL's auto-cleanup never registers. Wire it explicitly.
afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
});

const BOOK: BookState = {
    bestBid: 14_466_800,
    bestAsk: 14_466_900,
    bids: [[14_466_800, 10]],
    asks: [[14_466_900, 7]],
    timestamp: 1,
};

function print(tradeId: number, pricePx: number): TapeEntry {
    return { tradeId, pricePx, quantity: 1, aggressorOrderId: 9, passiveOrderId: 8, timestamp: tradeId, mine: false };
}

describe("tape and header format the same move the same way (D1)", () => {
    it("renders 1446.69 -> 1446.68 as -0.01 / -0.0007% in both, coloured down", () => {
        const tape = [print(2, 14_466_800), print(1, 14_466_900)];
        render(<TradeTape tape={tape} />);
        const row = screen.getAllByTestId("tape-row")[0];
        const tapePct = row.querySelector('[data-testid="tape-change-percent"]')!;
        const tapeDollar = row.querySelector('[data-testid="tape-change-dollar"]')!;

        render(
            <Header
                book={BOOK}
                tape={tape}
                orders={[]}
                sessionVolume={2}
                sessionOpenPx={14_466_900}
                lastFrameNanos={1}
            />,
        );
        const headerPct = screen.getByTestId("header-change-pct");
        const headerChange = screen.getByTestId("header-change");

        expect(tapeDollar.textContent).toBe("-0.01");
        expect(tapePct.textContent).toBe("-0.0007%");
        expect(headerPct.textContent).toBe(tapePct.textContent);
        expect(headerChange.textContent?.replace(/\s+/g, "")).toBe("-0.01-0.0007%");
        expect(tapePct.className).toContain("trade-tape__change--down");
        expect(headerChange.className).toContain("header__value--down");
    });
});

describe("header BID is the terminal bid blue (D2)", () => {
    it("styles the BID metric with --quote-bid, not the legacy green --bid", () => {
        render(
            <Header
                book={BOOK}
                tape={[]}
                orders={[]}
                sessionVolume={0}
                sessionOpenPx={-1}
                lastFrameNanos={0}
            />,
        );
        const bid = screen.getByTestId("header-bid");
        expect(bid.closest(".header__metric--bid")).not.toBeNull();

        const css = readFileSync(resolve(__dirname, "../src/styles/terminal.css"), "utf-8");
        const rule = /\.header__metric--bid \.header__value\s*\{([^}]*)\}/.exec(css);
        expect(rule).not.toBeNull();
        expect(rule![1]).toContain("var(--quote-bid)");
        expect(rule![1]).not.toMatch(/var\(--bid\)/);
    });
});

describe("depth chart mid label size (D3)", () => {
    function stubBox(width: number, height: number): void {
        vi.spyOn(Element.prototype, "getBoundingClientRect").mockReturnValue({
            width,
            height,
            top: 0,
            left: 0,
            right: width,
            bottom: height,
            x: 0,
            y: 0,
            toJSON: () => ({}),
        } as DOMRect);
        vi.stubGlobal(
            "ResizeObserver",
            class {
                observe(): void {}
                disconnect(): void {}
            },
        );
    }

    function renderedMidPx(scale: number): number {
        const label = screen.getByTestId("depth-curve-mid").querySelector(".depth-curve__mid-label")!;
        return Number(label.getAttribute("font-size")) * scale;
    }

    it("renders the regular view's mid label at the shared MID_LABEL_PX", () => {
        stubBox(396, 176); // regular panel, scale 0.55
        render(<DepthCurve book={BOOK} variant="compact" />);
        expect(renderedMidPx(0.55)).toBeCloseTo(MID_LABEL_PX, 4);
    });

    it("renders the expanded view's mid label at the same MID_LABEL_PX", () => {
        stubBox(1018, 526); // expanded window, width-bound scale
        render(<DepthCurve book={BOOK} variant="full" />);
        expect(renderedMidPx(1018 / 720)).toBeCloseTo(MID_LABEL_PX, 4);
    });

    it("keeps the compact plot below the enlarged tag", () => {
        stubBox(300, 133); // a narrow panel, scale ~0.417
        render(<DepthCurve book={BOOK} variant="compact" />);
        const tag = screen.getByTestId("depth-curve-mid").querySelector(".depth-curve__mid-tag")!;
        const tagBottom = Number(tag.getAttribute("y")) + Number(tag.getAttribute("height"));
        const band = screen.getByTestId(`curve-hit-${BOOK.bestBid}`);
        expect(Number(band.getAttribute("y"))).toBeGreaterThanOrEqual(tagBottom);
    });
});
