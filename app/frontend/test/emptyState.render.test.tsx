import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { TradeTape } from "../src/components/TradeTape";
import { OpenOrders } from "../src/components/OpenOrders";
import { ExecutionsTable } from "../src/components/ExecutionsTable";
import { FixInspector } from "../src/components/FixInspector";
import { PriceChart } from "../src/components/PriceChart";
import { DepthCurve } from "../src/components/DepthCurve";
import { DepthChartOverlay } from "../src/components/DepthChartOverlay";
import { initialState } from "../src/state/reducer";
import { SETTING_KEYS } from "../src/settings/persist";

// One standard empty state across the terminal (UI polish): every empty or
// placeholder line takes the shared .term-empty (HTML) or .term-empty-svg (SVG).

afterEach(() => {
    cleanup();
    localStorage.clear();
});

const CSS = readFileSync(resolve(__dirname, "../src/styles/terminal.css"), "utf-8");

/** The declaration block of the rule whose selector list is exactly `selector`. */
function block(selector: string): string {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace(/\s+/g, "\\s*");
    const m = new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`).exec(CSS);
    if (m === null) throw new Error(`no rule for ${selector}`);
    return m[1];
}

/** WCAG relative-luminance contrast of two #rrggbb colours. */
function contrast(a: string, b: string): number {
    const lum = (hex: string): number => {
        const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
        const l = c.map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
        return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2];
    };
    const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
}

describe("the shared empty-state class", () => {
    it("applies to the tape, blotter, executions and FIX inspector empties", () => {
        render(<TradeTape tape={[]} />);
        expect(screen.getByTestId("tape-empty").classList.contains("term-empty")).toBe(true);
        cleanup();

        render(<OpenOrders orders={[]} onCancel={vi.fn()} />);
        expect(screen.getByTestId("open-orders-empty").classList.contains("term-empty")).toBe(true);
        cleanup();

        render(<ExecutionsTable rows={[]} />);
        expect(screen.getByTestId("executions-empty").classList.contains("term-empty")).toBe(true);
        cleanup();

        render(<FixInspector entries={[]} open onClose={vi.fn()} />);
        expect(screen.getByTestId("fix-inspector-empty").classList.contains("term-empty")).toBe(true);
        expect(screen.getByTestId("fix-inspector-select-empty").classList.contains("term-empty")).toBe(true);
        expect(screen.getByTestId("fix-inspector-select-empty").textContent?.trim()).toBe("Select a message");
    });

    it("is Plex Sans 400 in --dim at --empty-size, centred, with no per-panel ink left", () => {
        const r = block(".term-empty, td.term-empty");
        expect(r).toContain("font-family: var(--sans)");
        expect(r).toContain("font-weight: 400");
        expect(r).toContain("font-size: var(--empty-size)");
        expect(r).toContain("color: var(--dim, #8f8f8f)");
        expect(r).toContain("text-align: center");
        for (const sel of [".fix-inspector__empty", ".open-orders__filter-empty"]) {
            expect(block(sel)).not.toMatch(/color|font-size|font-family/);
        }
        expect(block(".trade-tape__empty,\n.open-orders__empty")).not.toMatch(/color|font-size/);
    });
});

describe("the SVG empty states on both grounds", () => {
    it("the price chart and depth curve empties take .term-empty-svg, with no inline ink or size", () => {
        render(<PriceChart tape={[]} bars={initialState.bars} sessionOpenPx={-1} />);
        const pc = document.querySelector(".price-chart__empty")!;
        expect(pc.classList.contains("term-empty-svg")).toBe(true);
        expect(pc.getAttribute("fill")).toBeNull();
        expect(pc.getAttribute("font-size")).toBeNull();
        expect(pc.closest(".price-chart--black")).not.toBeNull();
        cleanup();

        render(<DepthCurve book={initialState.book} variant="compact" />);
        const dc = document.querySelector(".depth-curve__empty") as SVGTextElement;
        expect(dc.classList.contains("term-empty-svg")).toBe(true);
        expect(dc.getAttribute("fill")).toBeNull();
        expect(dc.style.getPropertyValue("--svg-scale")).not.toBe("");
    });

    it("the white price chart ground and the light depth window override the ink", () => {
        localStorage.setItem(`${SETTING_KEYS.priceChartBackground}`, JSON.stringify("white"));
        render(<PriceChart tape={[]} bars={initialState.bars} sessionOpenPx={-1} />);
        expect(document.querySelector(".price-chart__empty")!.closest(".price-chart--white")).not.toBeNull();
        cleanup();

        localStorage.setItem(`${SETTING_KEYS.depthChartBackground}`, JSON.stringify("light"));
        render(<DepthChartOverlay open onClose={vi.fn()} book={initialState.book} />);
        expect(document.querySelector(".depth-curve__empty")!.closest(".depth-overlay__body--light")).not.toBeNull();

        const svg = block(".term-empty-svg");
        expect(svg).toContain("fill: var(--empty-ink)");
        expect(svg).toContain("font-size: calc(var(--empty-size) / var(--svg-scale, 1))");
        expect(block(".price-chart--white,\n.depth-overlay__body--light")).toContain("--empty-ink: #6b6b6b");
    });

    it("the white-ground ink passes AA contrast on white, where --dim does not", () => {
        expect(contrast("#6b6b6b", "#ffffff")).toBeGreaterThanOrEqual(4.5);
        expect(contrast("#8f8f8f", "#ffffff")).toBeLessThan(4.5);
        // The dark grounds keep --dim, which reads on the near-black chart.
        expect(contrast("#8f8f8f", "#000000")).toBeGreaterThanOrEqual(4.5);
    });
});
