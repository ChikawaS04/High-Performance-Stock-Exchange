/**
 * Fetch client for the ignition price (P11-2, moved behind the venue in P16-3).
 *
 * The one impure edge of the ignition path. It calls the venue's same-origin GET /market/ignition;
 * the venue holds the Alpaca credentials and caches the open, so nothing here knows about Alpaca and
 * no credential is ever referenced or shipped to the client.
 *
 * Never throws: every outcome is an IgnitionResult. A 503 (nothing cached yet) is told apart from other
 * failures and carries its Retry-After, so the hook can wait and try again. Parsing is delegated to the
 * pure parseIgnitionOpen.
 */

import { parseIgnitionOpen, parseRetryAfter } from "./ignition";
import type { IgnitionResult } from "./ignition";

/** The venue route, same-origin (P16-4 proxies it in development). */
export const IGNITION_PATH = "/market/ignition";

const FAILED: IgnitionResult = { kind: "failed" };

/** One fetch of the ignition price. Never throws. */
export async function fetchIgnition(signal?: AbortSignal): Promise<IgnitionResult> {
    try {
        const res = await fetch(IGNITION_PATH, { signal });
        if (res.status === 503) {
            return { kind: "unavailable", retryAfterS: parseRetryAfter(res.headers.get("Retry-After")) };
        }
        if (!res.ok) return FAILED;
        const body: unknown = await res.json();
        const openPx = parseIgnitionOpen(body);
        return openPx === null ? FAILED : { kind: "ok", openPx };
    } catch {
        return FAILED;
    }
}
