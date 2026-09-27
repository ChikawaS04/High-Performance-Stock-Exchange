/**
 * Alpaca fetch client for the ignition price (P11-2).
 *
 * The one impure edge of the ignition path. It calls a same-origin relative URL
 * (/alpaca/...), which the Vite dev-server proxy forwards to Alpaca with the auth
 * headers injected server-side (see vite.config.ts, P11 D2), so no credential is
 * ever referenced here or shipped to the client, and browser CORS never arises.
 *
 * Never throws: any non-ok response or network error resolves to null, so the
 * caller falls back to the header's EMPTY_PRICE sentinel rather than surfacing an
 * exception. Parsing is delegated to the pure parseIgnitionOpen (P11-1).
 */

import { parseIgnitionOpen } from "./ignition";

/** Free-tier data feed; change to "sip" on a paid Alpaca tier. */
const FEED = "iex";

/** Instrument symbol, config-driven (P11 D8); defaults to ASML, the header symbol. */
const SYMBOL = import.meta.env.VITE_IGNITION_SYMBOL ?? "ASML";

/** Official market open in long cents, or null on any failure. Never throws. */
export async function fetchIgnitionOpen(signal?: AbortSignal): Promise<number | null> {
    try {
        const res = await fetch(
            `/alpaca/v2/stocks/${SYMBOL}/snapshot?feed=${FEED}`,
            { signal },
        );
        if (!res.ok) return null;
        const body: unknown = await res.json();
        return parseIgnitionOpen(body);
    } catch {
        return null;
    }
}
