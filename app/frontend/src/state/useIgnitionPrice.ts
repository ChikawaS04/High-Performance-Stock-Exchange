/**
 * Ignition-price fetch, isolated from the socket and the reducer (P11 D1).
 *
 * This hook is deliberately separate from useOrderBook: it never imports the socket hook, never
 * touches the pure reducer, and never runs on the hot path. It owns its own state and fetches the
 * official market open from the venue on mount. Mounted once above the router (P10 D2), so it fetches
 * once per app open and survives navigation with no refetch.
 *
 * Retries (P16-3): a 503 means the venue has not cached the open yet, so the hook waits the response's
 * Retry-After (clamped to 1 to 60 s) and tries again, up to MAX_ATTEMPTS calls in all; any other
 * failure gets the single P11 retry after a fixed delay. Then it reports "unavailable" and the header
 * shows the dash. In-flight work is aborted and any pending retry timer cleared on unmount, so no state
 * update runs after teardown.
 *
 * The resulting openPx flows into the header's existing openPx prop (P10-5), where an undefined value
 * renders the EMPTY_PRICE sentinel. Chg is not affected: it stays anchored to the engine's first trade
 * (P11 D6).
 */

import { useEffect, useState } from "react";

import { fetchIgnition } from "../market/ignitionClient";
import { nextRetryDelayMs } from "../market/ignition";

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
        let attempts = 0;
        let otherRetryUsed = false;

        const attempt = async (): Promise<void> => {
            timer = null;
            attempts += 1;
            const result = await fetchIgnition(controller.signal);
            if (disposed) return;
            if (result.kind === "ok") {
                setState({ openPx: result.openPx, status: "ready" });
                return;
            }
            const delay = nextRetryDelayMs(result, attempts, otherRetryUsed);
            if (delay === null) {
                setState({ status: "unavailable" });
                return;
            }
            if (result.kind === "failed") otherRetryUsed = true;
            timer = setTimeout(() => {
                void attempt();
            }, delay);
        };

        void attempt();

        return () => {
            disposed = true;
            if (timer !== null) clearTimeout(timer);
            controller.abort();
        };
    }, []);

    return state;
}
