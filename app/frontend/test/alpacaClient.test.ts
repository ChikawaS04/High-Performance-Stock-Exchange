import { afterEach, describe, expect, it, vi } from "vitest";

import { fetchIgnitionOpen } from "../src/market/alpacaClient";

afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});

describe("fetchIgnitionOpen", () => {
    it("returns units on a 200 with a parseable body", async () => {
        vi.stubGlobal(
            "fetch",
            vi.fn().mockResolvedValue({ ok: true, json: async () => ({ dailyBar: { o: 149.8 } }) }),
        );
        expect(await fetchIgnitionOpen()).toBe(1498000);
    });

    it("returns null on a non-ok status", async () => {
        vi.stubGlobal(
            "fetch",
            vi.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({}) }),
        );
        expect(await fetchIgnitionOpen()).toBeNull();
    });

    it("returns null when fetch rejects", async () => {
        vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network down")));
        expect(await fetchIgnitionOpen()).toBeNull();
    });

    it("returns null when the body has no usable open", async () => {
        vi.stubGlobal(
            "fetch",
            vi.fn().mockResolvedValue({ ok: true, json: async () => ({ dailyBar: {} }) }),
        );
        expect(await fetchIgnitionOpen()).toBeNull();
    });

    it("forwards the abort signal on the request", async () => {
        const fetchMock = vi
            .fn()
            .mockResolvedValue({ ok: true, json: async () => ({ dailyBar: { o: 149.8 } }) });
        vi.stubGlobal("fetch", fetchMock);
        const controller = new AbortController();
        await fetchIgnitionOpen(controller.signal);
        expect(fetchMock).toHaveBeenCalledTimes(1);
        const init = fetchMock.mock.calls[0][1];
        expect(init.signal).toBe(controller.signal);
    });
});
