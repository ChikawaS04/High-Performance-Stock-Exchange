import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";

import { useIgnitionPrice } from "../src/state/useIgnitionPrice";
import { fetchIgnition } from "../src/market/ignitionClient";
import { MAX_ATTEMPTS } from "../src/market/ignition";
import type { IgnitionResult } from "../src/market/ignition";

// The hook's only dependency is the fetch client; mock it so no network is touched.
vi.mock("../src/market/ignitionClient", () => ({
    fetchIgnition: vi.fn(),
}));

const mockFetch = vi.mocked(fetchIgnition);

const OK: IgnitionResult = { kind: "ok", openPx: 1498000 };
const FAILED: IgnitionResult = { kind: "failed" };
const unavailable = (retryAfterS: number | null): IgnitionResult => ({ kind: "unavailable", retryAfterS });

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.useRealTimers();
});

/** Lets the pending fetch promise settle without advancing any timer. */
async function settle(): Promise<void> {
    await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
    });
}

async function advance(ms: number): Promise<void> {
    await act(async () => {
        await vi.advanceTimersByTimeAsync(ms);
    });
}

describe("useIgnitionPrice", () => {
    it("sets openPx and ready on a successful fetch", async () => {
        mockFetch.mockResolvedValue(OK);
        const { result } = renderHook(() => useIgnitionPrice());
        await waitFor(() => expect(result.current.status).toBe("ready"));
        expect(result.current.openPx).toBe(1498000);
        expect(mockFetch).toHaveBeenCalledTimes(1);
    });

    it("retries once after another failure and succeeds", async () => {
        vi.useFakeTimers();
        mockFetch.mockResolvedValueOnce(FAILED).mockResolvedValueOnce(OK);
        const { result } = renderHook(() => useIgnitionPrice());
        await act(async () => {
            await vi.runAllTimersAsync();
        });
        expect(result.current.status).toBe("ready");
        expect(result.current.openPx).toBe(1498000);
        expect(mockFetch).toHaveBeenCalledTimes(2);
    });

    it("reports unavailable after two other failures", async () => {
        vi.useFakeTimers();
        mockFetch.mockResolvedValue(FAILED);
        const { result } = renderHook(() => useIgnitionPrice());
        await act(async () => {
            await vi.runAllTimersAsync();
        });
        expect(result.current.status).toBe("unavailable");
        expect(result.current.openPx).toBeUndefined();
        expect(mockFetch).toHaveBeenCalledTimes(2);
    });

    it("shows the value after a 503 then a 200", async () => {
        vi.useFakeTimers();
        mockFetch.mockResolvedValueOnce(unavailable(3)).mockResolvedValueOnce(OK);
        const { result } = renderHook(() => useIgnitionPrice());
        await settle();
        expect(result.current.status).toBe("loading");
        await advance(3000);
        expect(result.current.status).toBe("ready");
        expect(result.current.openPx).toBe(1498000);
        expect(mockFetch).toHaveBeenCalledTimes(2);
    });

    it("honours Retry-After", async () => {
        vi.useFakeTimers();
        mockFetch.mockResolvedValueOnce(unavailable(5)).mockResolvedValueOnce(OK);
        const { result } = renderHook(() => useIgnitionPrice());
        await settle();
        await advance(4999);
        expect(mockFetch).toHaveBeenCalledTimes(1);
        await advance(1);
        expect(mockFetch).toHaveBeenCalledTimes(2);
        expect(result.current.status).toBe("ready");
    });

    it("clamps Retry-After to at most 60 s", async () => {
        vi.useFakeTimers();
        mockFetch.mockResolvedValueOnce(unavailable(3600)).mockResolvedValueOnce(OK);
        renderHook(() => useIgnitionPrice());
        await settle();
        await advance(59_999);
        expect(mockFetch).toHaveBeenCalledTimes(1);
        await advance(1);
        expect(mockFetch).toHaveBeenCalledTimes(2);
    });

    it("clamps Retry-After to at least 1 s, also when it is missing", async () => {
        vi.useFakeTimers();
        mockFetch
            .mockResolvedValueOnce(unavailable(0))
            .mockResolvedValueOnce(unavailable(null))
            .mockResolvedValueOnce(OK);
        renderHook(() => useIgnitionPrice());
        await settle();
        await advance(999);
        expect(mockFetch).toHaveBeenCalledTimes(1);
        await advance(1);
        expect(mockFetch).toHaveBeenCalledTimes(2);
        await advance(999);
        expect(mockFetch).toHaveBeenCalledTimes(2);
        await advance(1);
        expect(mockFetch).toHaveBeenCalledTimes(3);
    });

    it("stops at the attempt cap and shows the dash", async () => {
        vi.useFakeTimers();
        mockFetch.mockResolvedValue(unavailable(1));
        const { result } = renderHook(() => useIgnitionPrice());
        await act(async () => {
            await vi.runAllTimersAsync();
        });
        expect(mockFetch).toHaveBeenCalledTimes(MAX_ATTEMPTS);
        expect(result.current.status).toBe("unavailable");
        expect(result.current.openPx).toBeUndefined();
        await advance(120_000);
        expect(mockFetch).toHaveBeenCalledTimes(MAX_ATTEMPTS);
    });

    it("unmount cancels a pending 503 retry", async () => {
        vi.useFakeTimers();
        mockFetch.mockResolvedValue(unavailable(5));
        const { unmount } = renderHook(() => useIgnitionPrice());
        await settle();
        expect(mockFetch).toHaveBeenCalledTimes(1);
        unmount();
        await advance(60_000);
        expect(mockFetch).toHaveBeenCalledTimes(1);
        expect(vi.getTimerCount()).toBe(0);
    });

    it("aborts on unmount with no post-unmount state update", async () => {
        let resolveFetch: (v: IgnitionResult) => void = () => {};
        mockFetch.mockImplementation(
            () =>
                new Promise<IgnitionResult>((res) => {
                    resolveFetch = res;
                }),
        );
        const { result, unmount } = renderHook(() => useIgnitionPrice());
        expect(result.current.status).toBe("loading");
        const signal = mockFetch.mock.calls[0][0];
        unmount();
        expect(signal?.aborted).toBe(true);
        await act(async () => {
            resolveFetch(OK);
            await Promise.resolve();
        });
        expect(result.current.status).toBe("loading");
    });
});
