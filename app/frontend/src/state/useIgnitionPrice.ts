/**
 * One-shot ignition-price fetch, isolated from the socket and the reducer (P11 D1).
 *
 * This hook is deliberately separate from useOrderBook: it never imports the socket
 * hook, never touches the pure reducer, and never runs on the hot path. It owns its
 * own state, fetches the official market open once on mount, retries exactly once on
 * failure after a fixed delay, and then reports "unavailable". In-flight work is
 * aborted and the retry timer cleared on unmount, so no state update runs after
 * teardown. Mounted once above the router (P10 D2), so it fetches once per app open
 * and survives navigation with no refetch.
 *
 * The resulting openPx flows into the header's existing openPx prop (P10-5),
 * where an undefined value renders the EMPTY_PRICE sentinel. Chg is not affected:
 * it stays anchored to the engine's first trade (P11 D6).
 */

import { useEffect, useState } from "react";

import { fetchIgnitionOpen } from "../market/alpacaClient";

const RETRY_DELAY_MS = 2000;

export type IgnitionStatus = "loading" | "ready" | "unavailable";

export interface IgnitionState {
    /** Official market open in long units of $0.0001, or undefined when not available. */
    readonly openPx?: number;
    readonly status: IgnitionStatus;
}

export function useIgnitionPrice(): IgnitionState {
    const [state, setState] = useState<IgnitionState>({ status: "loading" });

    useEffect(() => {
        const controller = new AbortController();
        let timer: ReturnType<typeof setTimeout> | null = null;
        let disposed = false;

        const attempt = async (retriesLeft: number): Promise<void> => {
            const px = await fetchIgnitionOpen(controller.signal);
            if (disposed) return;
            if (px !== null) {
                setState({ openPx: px, status: "ready" });
                return;
            }
            if (retriesLeft > 0) {
                timer = setTimeout(() => {
                    void attempt(retriesLeft - 1);
                }, RETRY_DELAY_MS);
                return;
            }
            setState({ status: "unavailable" });
        };

        void attempt(1);

        return () => {
            disposed = true;
            if (timer !== null) clearTimeout(timer);
            controller.abort();
        };
    }, []);

    return state;
}
