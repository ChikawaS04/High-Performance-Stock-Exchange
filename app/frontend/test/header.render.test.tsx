import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { Header } from "../src/components/Header";
import type { HeaderProps } from "../src/components/Header";
import type { BookState, MyOrder, TapeEntry } from "../src/state/reducer";

// P5-0's no-globals stance means RTL's auto-cleanup never registers; wire it
// explicitly so renders don't bleed across tests.
afterEach(cleanup);

const BOOK: BookState = {
    bestBid: 1500000,
    bestAsk: 1502500,
    bids: [[1500000, 10]],
    asks: [[1502500, 7]],
    timestamp: 111,
};

const TAPE: TapeEntry[] = [
    { tradeId: 1, pricePx: 1502500, quantity: 4, aggressorOrderId: 2, passiveOrderId: 1, timestamp: 222, mine: true },
];

const ORDERS: MyOrder[] = [
    {
        clOrdId: 1,
        side: "BUY",
        pricePx: 1500000,
        originalQty: 10,
        remainingQty: 4,
        status: "PARTIALLY_FILLED",
        ordType: "LIMIT",
        tif: "GTC",
        displayQty: 0,
    },
    {
        clOrdId: 2,
        side: "SELL",
        pricePx: 1502500,
        originalQty: 5,
        remainingQty: 5,
        status: "OPEN",
        ordType: "LIMIT",
        tif: "GTC",
        displayQty: 0,
    },
];

function renderHeader(over: Partial<HeaderProps> = {}) {
    const props: HeaderProps = {
        book: BOOK,
        tape: TAPE,
        orders: ORDERS,
        sessionVolume: 40,
        sessionOpenPx: 1500000,
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

    it("shows the spread in ticks and basis points", () => {
        renderHeader();
        expect(screen.getByTestId("header-spread-ticks").textContent).toContain("25");
        expect(screen.getByTestId("header-spread-bps").textContent).toContain("16.65"); // 20000*2500/3002500
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
    it("renders the empty marker when no openPx is supplied", () => {
        renderHeader();
        expect(screen.getByTestId("header-open").textContent).toBe("—");
    });

    it("formats a supplied openPx as a dollar string", () => {
        renderHeader({ openPx: 1498000 });
        expect(screen.getByTestId("header-open").textContent).toBe("149.80");
    });

    it("does not re-anchor Chg when openPx is supplied (P11 D6)", () => {
        // Chg stays anchored to sessionOpenPx (the first trade), independent of the
        // market-open value in the Open field. A wildly different openPx must leave
        // Chg and its percent identical to the no-openPx render.
        const first = renderHeader();
        const chg = screen.getByTestId("header-change").textContent;
        const pct = screen.getByTestId("header-change-pct").textContent;
        first.unmount();

        renderHeader({ openPx: 9999900 });
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
