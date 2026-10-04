import { describe, expect, it } from "vitest";

import { buildSessionStats } from "../src/sessionStats";
import type { SessionStatsInput } from "../src/sessionStats";
import { EMPTY_PRICE } from "../src/format";
import { SESSION_OPEN_UNSET } from "../src/state/reducer";
import type { TapeEntry } from "../src/state/reducer";

/**
 * Minimal TapeEntry fixture, mirroring priceSeries.test.ts. buildSessionStats reads
 * only `priceCents` and `quantity` from a tape row; every other field is given a
 * fixed default here and can be overridden per case to prove it is ignored. Built
 * by hand with local values, never via IDGenerator (its AtomicLong counters are
 * JVM-global on the backend and the frontend equivalent is equally shared state).
 */
function entry(partial: Partial<TapeEntry> = {}): TapeEntry {
    return {
        tradeId: 1,
        priceCents: 15000,
        quantity: 1,
        aggressorOrderId: 1,
        passiveOrderId: 2,
        timestamp: 1,
        mine: false,
        ...partial,
    };
}

/** An unset session: nothing has traded. Matches initialState's three slices. */
function unsetInput(over: Partial<SessionStatsInput> = {}): SessionStatsInput {
    return {
        tape: [],
        sessionHighCents: SESSION_OPEN_UNSET,
        sessionLowCents: SESSION_OPEN_UNSET,
        sessionTradeCount: 0,
        ...over,
    };
}

describe("buildSessionStats", () => {
    it("formats all five fields from a populated session", () => {
        const model = buildSessionStats({
            // Newest first, so 150.25 x 4 is the last trade.
            tape: [
                entry({ tradeId: 3, priceCents: 15025, quantity: 4 }),
                entry({ tradeId: 2, priceCents: 15075, quantity: 9 }),
                entry({ tradeId: 1, priceCents: 14925, quantity: 2 }),
            ],
            sessionHighCents: 15075,
            sessionLowCents: 14925,
            sessionTradeCount: 12,
        });

        expect(model).toEqual({
            high: "150.75",
            low: "149.25",
            trades: "12",
            lastPrice: "150.25",
            lastSize: "4",
        });
    });

    it("renders the dash for every unset price, and a real zero for the count", () => {
        const model = buildSessionStats(unsetInput());

        expect(model.high).toBe(EMPTY_PRICE);
        expect(model.low).toBe(EMPTY_PRICE);
        expect(model.lastPrice).toBe(EMPTY_PRICE);
        expect(model.lastSize).toBe(EMPTY_PRICE);

        // Zero trades is a known fact, not missing data, so it is never the dash.
        expect(model.trades).toBe("0");
        expect(model.trades).not.toBe(EMPTY_PRICE);
    });

    it("dashes only the last-trade fields when the tape is empty but the session is not", () => {
        // A state the reducer cannot produce today (a fill always writes the tape),
        // but the function must not crash or blank the accumulators if it ever can:
        // the three held values are independent of the tape by construction.
        const model = buildSessionStats({
            tape: [],
            sessionHighCents: 15075,
            sessionLowCents: 14925,
            sessionTradeCount: 7,
        });

        expect(model.high).toBe("150.75");
        expect(model.low).toBe("149.25");
        expect(model.trades).toBe("7");
        expect(model.lastPrice).toBe(EMPTY_PRICE);
        expect(model.lastSize).toBe(EMPTY_PRICE);
    });

    it("renders high, low and last as the same price after a single print", () => {
        const model = buildSessionStats({
            tape: [entry({ priceCents: 10150, quantity: 3 })],
            sessionHighCents: 10150,
            sessionLowCents: 10150,
            sessionTradeCount: 1,
        });

        expect(model.high).toBe("101.50");
        expect(model.low).toBe("101.50");
        expect(model.lastPrice).toBe("101.50");
        expect(model.high).toBe(model.lastPrice);
        expect(model.low).toBe(model.lastPrice);
        expect(model.trades).toBe("1");
        expect(model.lastSize).toBe("3");
    });

    it("groups large counts and sizes the way the header Volume does", () => {
        const model = buildSessionStats({
            tape: [entry({ priceCents: 15000, quantity: 12_500 })],
            sessionHighCents: 15000,
            sessionLowCents: 15000,
            sessionTradeCount: 1240,
        });

        expect(model.trades).toBe("1,240");
        expect(model.lastSize).toBe("12,500");
    });

    it("reads only the newest tape row, ignoring the rest and the unread fields", () => {
        const newest = entry({
            tradeId: 99,
            priceCents: 20000,
            quantity: 6,
            aggressorOrderId: 7,
            passiveOrderId: 8,
            timestamp: 1_700_000_000_000_000_000,
            mine: true,
            aggressorSide: "SELL",
        });
        const older = entry({ tradeId: 1, priceCents: 10000, quantity: 500 });

        const model = buildSessionStats({
            tape: [newest, older, older],
            sessionHighCents: 20000,
            sessionLowCents: 10000,
            sessionTradeCount: 3,
        });

        expect(model.lastPrice).toBe("200.00");
        expect(model.lastSize).toBe("6");
    });

    it("renders sub-dollar prices with a leading zero, not a bare cent count", () => {
        const model = buildSessionStats({
            tape: [entry({ priceCents: 5, quantity: 1 })],
            sessionHighCents: 5,
            sessionLowCents: 5,
            sessionTradeCount: 1,
        });

        expect(model.high).toBe("0.05");
        expect(model.lastPrice).toBe("0.05");
    });

    it("is pure: it mutates neither the input nor the tape", () => {
        const tape = [entry({ priceCents: 15025, quantity: 4 })];
        const input: SessionStatsInput = {
            tape,
            sessionHighCents: 15025,
            sessionLowCents: 15025,
            sessionTradeCount: 1,
        };

        const first = buildSessionStats(input);
        const second = buildSessionStats(input);

        expect(second).toEqual(first);
        expect(input.tape).toBe(tape);
        expect(tape).toHaveLength(1);
        expect(tape[0]).toEqual(entry({ priceCents: 15025, quantity: 4 }));
    });
});
