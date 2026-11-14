import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { OpenOrders } from "../src/components/OpenOrders";
import { ExecutionsTable } from "../src/components/ExecutionsTable";
import { buildExecutionQuality } from "../src/executionQuality";
import type { ExecutionQualityRow } from "../src/executionQuality";
import { arrivalFrom } from "../src/arrival";
import { foldExec } from "../src/state/reducer";
import type { MyOrder } from "../src/state/reducer";
import { SETTING_KEYS } from "../src/settings/persist";
import { EMPTY_PRICE } from "../src/format";

// P18-10: the Executions tab in the blotter panel.

afterEach(() => {
    cleanup();
    localStorage.clear();
});

function filled(clOrdId: number, side: "BUY" | "SELL", px: number, role: "aggressive" | "passive", sentAtNanos: number): MyOrder {
    return {
        clOrdId,
        side,
        pricePx: px,
        originalQty: 100,
        remainingQty: 0,
        status: "FILLED",
        ordType: "LIMIT",
        tif: "GTC",
        displayQty: 0,
        sentAtNanos,
        arrival: arrivalFrom(1_000_000, 1_000_200, 1_000_100, 1),
        exec: foldExec(undefined, px, 100, role, 1_000_100),
    };
}

// A buy that paid the full half-spread (cost), and a passive sell above the mid (improvement).
const COST = filled(11, "BUY", 1_000_200, "aggressive", 100);
const IMPROVE = filled(12, "SELL", 1_000_200, "passive", 200);
const ROWS: readonly ExecutionQualityRow[] = buildExecutionQuality([COST, IMPROVE]);

function blotter(executions: readonly ExecutionQualityRow[] = ROWS) {
    return render(<OpenOrders orders={[COST, IMPROVE]} executions={executions} onCancel={vi.fn()} />);
}

describe("blotter view switch (P18-10)", () => {
    it("opens on Orders, and the switch moves to Executions and back", () => {
        blotter();
        expect(screen.getByTestId("blotter-tab-orders").getAttribute("aria-pressed")).toBe("true");
        expect(screen.queryByTestId("executions-table")).toBeNull();
        expect(screen.getAllByTestId("open-orders-row")).toHaveLength(2);
        fireEvent.click(screen.getByTestId("blotter-tab-executions"));
        expect(screen.getByTestId("blotter-tab-executions").getAttribute("aria-pressed")).toBe("true");
        expect(screen.getByTestId("executions-table")).not.toBeNull();
        expect(screen.queryByTestId("open-orders-row")).toBeNull();
        fireEvent.click(screen.getByTestId("blotter-tab-orders"));
        expect(screen.getAllByTestId("open-orders-row")).toHaveLength(2);
    });

    it("persists the selected tab across a remount", () => {
        const { unmount } = blotter();
        fireEvent.click(screen.getByTestId("blotter-tab-executions"));
        expect(JSON.parse(localStorage.getItem(SETTING_KEYS.blotterTab) ?? "null")).toBe("executions");
        expect(SETTING_KEYS.blotterTab).toBe("hpse.v1.blotter.tab");
        unmount();
        blotter();
        expect(screen.getByTestId("executions-table")).not.toBeNull();
    });

    it("shows the filters and the cancel menu on Orders only", () => {
        blotter();
        expect(screen.queryByTestId("blotter-filter-trigger") ?? screen.queryByTestId("blotter-filter")).not.toBeNull();
        expect(screen.getByTestId("blotter-menu-btn")).not.toBeNull();
        fireEvent.click(screen.getByTestId("blotter-tab-executions"));
        expect(document.querySelector(".open-orders__filter")).toBeNull();
        expect(document.querySelector(".open-orders__owner-filter")).toBeNull();
        expect(screen.queryByTestId("blotter-menu-btn")).toBeNull();
    });

    it("drops an unknown stored tab back to Orders", () => {
        localStorage.setItem(SETTING_KEYS.blotterTab, JSON.stringify("fills"));
        blotter();
        expect(screen.getByTestId("blotter-tab-orders").getAttribute("aria-pressed")).toBe("true");
    });
});

describe("Executions table (P18-10)", () => {
    it("lists each order newest first with every metric", () => {
        render(<ExecutionsTable rows={ROWS} />);
        const rows = screen.getAllByTestId("executions-row");
        expect(rows.map((r) => r.querySelector(".open-orders__id")!.textContent)).toEqual(["12", "11"]);
        const heads = Array.from(document.querySelectorAll("thead th")).map((th) => th.textContent);
        expect(heads).toEqual(["Order ID", "Side", "Qty", "Filled", "Avg price", "vs Arrival", "vs VWAP", "Spread paid", "Passive %"]);
        expect(screen.getByTestId("exq-qty-11").textContent).toBe("100/100");
        expect(screen.getByTestId("exq-filled-11").textContent).toBe("100%");
        expect(screen.getByTestId("exq-avg-11").textContent).toBe("100.02");
        expect(screen.getByTestId("exq-arrival-11").textContent).toBe("+1.0");
        expect(screen.getByTestId("exq-spread-11").textContent).toBe("1.00");
        expect(screen.getByTestId("exq-passive-11").textContent).toBe("0%");
        expect(screen.getByTestId("exq-passive-12").textContent).toBe("100%");
    });

    it("colours cost red and improvement green, and shows a dash for a missing value", () => {
        render(<ExecutionsTable rows={ROWS} />);
        expect(screen.getByTestId("exq-arrival-11").className).toContain("exec-q__metric--cost");
        expect(screen.getByTestId("exq-arrival-12").className).toContain("exec-q__metric--improve");
        // A fully passive order has no spread paid.
        expect(screen.getByTestId("exq-spread-12").textContent).toBe(EMPTY_PRICE);
        expect(screen.getByTestId("exq-spread-12").className).toContain("exec-q__metric--none");
    });

    it("shows the empty state with no executions", () => {
        render(<ExecutionsTable rows={[]} />);
        expect(screen.getByTestId("executions-empty").textContent).toBe("No executions yet");
        expect(screen.queryByTestId("executions-table")).toBeNull();
    });

    it("uses the §9 blotter table classes, with the side on the row", () => {
        render(<ExecutionsTable rows={ROWS} />);
        expect(screen.getByTestId("executions-table").className).toContain("open-orders__table");
        expect(screen.getAllByTestId("executions-row")[0].className).toContain("open-orders__row--sell");
    });
});
