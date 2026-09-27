import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { Header } from "../src/components/Header";
import type { HeaderProps } from "../src/components/Header";
import type { BookState, MyOrder, TapeEntry } from "../src/state/reducer";

// P5-0's no-globals stance means RTL's auto-cleanup never registers; wire it
// explicitly so renders don't bleed across tests.
afterEach(cleanup);

const BOOK: BookState = {
    bestBid: 15000,
    bestAsk: 15025,
    bids: [[15000, 10]],
    asks: [[15025, 7]],
    timestamp: 111,
};

const TAPE: TapeEntry[] = [
    { tradeId: 1, priceCents: 15025, quantity: 4, aggressorOrderId: 2, passiveOrderId: 1, timestamp: 222, mine: true },
];

const ORDERS: MyOrder[] = [
    { clOrdId: 1, side: "BUY", priceCents: 15000, originalQty: 10, remainingQty: 4, status: "PARTIALLY_FILLED" },
    { clOrdId: 2, side: "SELL", priceCents: 15025, originalQty: 5, remainingQty: 5, status: "OPEN" },
];

function renderHeader(over: Partial<HeaderProps> = {}) {
    const props: HeaderProps = {
        book: BOOK,
        tape: TAPE,
        orders: ORDERS,
        sessionVolume: 40,
        sessionOpenCents: 15000,
        lastFrameNanos: 1_700_000_000_123_456_789,
        connection: "open",
        ...over,
    };
    return render(<Header {...props} />);
}

describe("<Header /> instrument row", () => {
    it("renders the ticker and the derived quote fields", () => {
        renderHeader();
        expect(screen.getByText("ASML")).not.toBeNull();
        expect(screen.getByTestId("header-last").textContent).toBe("150.25");
        expect(screen.getByTestId("header-bid").textContent).toBe("150.00");
        expect(screen.getByTestId("header-ask").textContent).toBe("150.25");
        expect(screen.getByTestId("header-mid").textContent).toBe("150.125");
        expect(screen.getByTestId("header-volume").textContent).toBe("40");
    });

    it("shows the spread in cents and basis points", () => {
        renderHeader();
        expect(screen.getByTestId("header-spread-cents").textContent).toContain("25");
        expect(screen.getByTestId("header-spread-bps").textContent).toContain("16.65"); // 20000*25/30025
    });

    it("colours a positive session change up and shows the signed percent", () => {
        renderHeader();
        const change = screen.getByTestId("header-change");
        expect(change.textContent).toContain("+0.25");
        expect(change.className).toContain("header__value--up");
        expect(screen.getByTestId("header-change-pct").textContent).toBe("+0.17%");
    });

    it("renders the Filled and Rem session counters from the orders slice", () => {
        renderHeader();
        // filled: (10-4) + (5-5) = 6; working (both non-terminal): 4 + 5 = 9
        expect(screen.getByTestId("header-filled").textContent).toBe("6");
        expect(screen.getByTestId("header-remaining").textContent).toBe("9");
    });
});

describe("<Header /> open field", () => {
    it("renders the empty marker when no openCents is supplied", () => {
        renderHeader();
        expect(screen.getByTestId("header-open").textContent).toBe("—");
    });

    it("formats a supplied openCents as a dollar string", () => {
        renderHeader({ openCents: 14980 });
        expect(screen.getByTestId("header-open").textContent).toBe("149.80");
    });

    it("does not re-anchor Chg when openCents is supplied (P11 D6)", () => {
        // Chg stays anchored to sessionOpenCents (the first trade), independent of the
        // market-open value in the Open field. A wildly different openCents must leave
        // Chg and its percent identical to the no-openCents render.
        const first = renderHeader();
        const chg = screen.getByTestId("header-change").textContent;
        const pct = screen.getByTestId("header-change-pct").textContent;
        first.unmount();

        renderHeader({ openCents: 99999 });
        expect(screen.getByTestId("header-change").textContent).toBe(chg);
        expect(screen.getByTestId("header-change-pct").textContent).toBe(pct);
    });
});

describe("<Header /> connection badge and session meta", () => {
    it("reuses the connection badge (pinned to the top-right corner)", () => {
        renderHeader({ connection: "open" });
        const badge = screen.getByTestId("connection-badge");
        expect(badge.textContent).toContain("Live");
    });

    it("renders the last-frame time as a wall-clock string", () => {
        renderHeader();
        expect(screen.getByTestId("header-last-frame").textContent).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{3}$/);
    });
});
