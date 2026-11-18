import { afterEach, describe, expect, it, vi } from "vitest";

import { IGNITION_PATH, fetchIgnition } from "../src/market/ignitionClient";

afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});

const GOOD = { symbol: "ASML", openPx: 1498000, date: 20261118 };

function response(status: number, body: unknown, headers: Record<string, string> = {}) {
    return {
        ok: status >= 200 && status < 300,
        status,
        headers: new Headers(headers),
        json: async () => body,
    };
}

describe("fetchIgnition", () => {
    it("requests the venue's same-origin /market/ignition", async () => {
        const fetchMock = vi.fn().mockResolvedValue(response(200, GOOD));
        vi.stubGlobal("fetch", fetchMock);
        await fetchIgnition();
        expect(IGNITION_PATH).toBe("/market/ignition");
        expect(fetchMock.mock.calls[0][0]).toBe("/market/ignition");
    });

    it("returns ok with units on a 200 with a parseable body", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, GOOD)));
        expect(await fetchIgnition()).toEqual({ kind: "ok", openPx: 1498000 });
    });

    it("tells a 503 apart and carries its Retry-After", async () => {
        vi.stubGlobal(
            "fetch",
            vi.fn().mockResolvedValue(response(503, { error: "UPSTREAM_UNAVAILABLE" }, { "Retry-After": "42" })),
        );
        expect(await fetchIgnition()).toEqual({ kind: "unavailable", retryAfterS: 42 });
    });

    it("a 503 without a readable Retry-After still reads as unavailable", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(503, {})));
        expect(await fetchIgnition()).toEqual({ kind: "unavailable", retryAfterS: null });
    });

    it("returns failed on another non-ok status", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(404, { error: "NOT_FOUND" })));
        expect(await fetchIgnition()).toEqual({ kind: "failed" });
    });

    it("returns failed when fetch rejects", async () => {
        vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network down")));
        expect(await fetchIgnition()).toEqual({ kind: "failed" });
    });

    it("returns failed when the body has no usable open", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(200, { symbol: "ASML", date: 20261118 })));
        expect(await fetchIgnition()).toEqual({ kind: "failed" });
    });

    it("returns failed when the body is not JSON", async () => {
        vi.stubGlobal(
            "fetch",
            vi.fn().mockResolvedValue({
                ok: true,
                status: 200,
                headers: new Headers(),
                json: async () => {
                    throw new SyntaxError("bad json");
                },
            }),
        );
        expect(await fetchIgnition()).toEqual({ kind: "failed" });
    });

    it("forwards the abort signal on the request", async () => {
        const fetchMock = vi.fn().mockResolvedValue(response(200, GOOD));
        vi.stubGlobal("fetch", fetchMock);
        const controller = new AbortController();
        await fetchIgnition(controller.signal);
        expect(fetchMock).toHaveBeenCalledTimes(1);
        const init = fetchMock.mock.calls[0][1];
        expect(init.signal).toBe(controller.signal);
    });
});
