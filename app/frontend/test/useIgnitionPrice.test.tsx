import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";

import { useIgnitionPrice } from "../src/state/useIgnitionPrice";
import { fetchIgnitionOpen } from "../src/market/alpacaClient";

// The hook's only dependency is the fetch client; mock it so no network is touched.
vi.mock("../src/market/alpacaClient", () => ({
    fetchIgnitionOpen: vi.fn(),
}));

const mockFetch = vi.mocked(fetchIgnitionOpen);

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.useRealTimers();
});

describe("useIgnitionPrice", () => {
    it("sets openPx and ready on a successful fetch", async () => {
        mockFetch.mockResolvedValue(1498000);
        const { result } = renderHook(() => useIgnitionPrice());
        await waitFor(() => expect(result.current.status).toBe("ready"));
        expect(result.current.openPx).toBe(1498000);
        expect(mockFetch).toHaveBeenCalledTimes(1);
    });

    it("retries once and succeeds", async () => {
        vi.useFakeTimers();
        mockFetch.mockResolvedValueOnce(null).mockResolvedValueOnce(1498000);
        const { result } = renderHook(() => useIgnitionPrice());
        await act(async () => {
            await vi.runAllTimersAsync();
        });
        expect(result.current.status).toBe("ready");
        expect(result.current.openPx).toBe(1498000);
        expect(mockFetch).toHaveBeenCalledTimes(2);
    });

    it("reports unavailable after two failures", async () => {
        vi.useFakeTimers();
        mockFetch.mockResolvedValue(null);
        const { result } = renderHook(() => useIgnitionPrice());
        await act(async () => {
            await vi.runAllTimersAsync();
        });
        expect(result.current.status).toBe("unavailable");
        expect(result.current.openPx).toBeUndefined();
        expect(mockFetch).toHaveBeenCalledTimes(2);
    });

    it("aborts on unmount with no post-unmount state update", async () => {
        let resolveFetch: (v: number | null) => void = () => {};
        mockFetch.mockImplementation(
            () =>
                new Promise<number | null>((res) => {
                    resolveFetch = res;
                }),
        );
        const { result, unmount } = renderHook(() => useIgnitionPrice());
        expect(result.current.status).toBe("loading");
        unmount();
        await act(async () => {
            resolveFetch(1498000);
            await Promise.resolve();
        });
        expect(result.current.status).toBe("loading");
    });
});
