/**
 * Pure ignition-price logic (P11-1, reworked P16-3).
 *
 * No network, no React. Since P16-3 the venue fetches the market open from Alpaca and serves it at
 * GET /market/ignition as {"symbol":"ASML","openPx":<units>,"date":YYYYMMDD}, already in long integer
 * units of $0.0001 and on the one-cent tick, so no unit conversion is left here. The parser checks the
 * body's shape and returns openPx, or null when anything is off, which the header renders as the
 * EMPTY_PRICE sentinel.
 *
 * The retry rule for the hook lives here too: a 503 means "not cached yet" and is retried after its
 * Retry-After, clamped to 1 to 60 s, up to MAX_ATTEMPTS calls in all; any other failure gets one retry
 * after OTHER_RETRY_MS, as in P11.
 */

/** The venue's /market/ignition body. */
export interface IgnitionBody {
    readonly symbol: string;
    readonly openPx: number;
    readonly date: number;
}

/** Units per cent: every open the venue serves is a multiple of this. */
const CENT_UNITS = 100;

/** Official market open in long units of $0.0001 from a /market/ignition body, or null. */
export function parseIgnitionOpen(body: unknown): number | null {
    if (typeof body !== "object" || body === null) return null;
    const b = body as Partial<Record<keyof IgnitionBody, unknown>>;
    if (typeof b.symbol !== "string" || b.symbol.length === 0) return null;
    const px = b.openPx;
    if (typeof px !== "number" || !Number.isSafeInteger(px) || px <= 0 || px % CENT_UNITS !== 0) return null;
    const date = b.date;
    if (typeof date !== "number" || !Number.isInteger(date) || date < 10_000_000 || date > 99_999_999) return null;
    return px;
}

/** What one fetch of /market/ignition came to. */
export type IgnitionResult =
    | { readonly kind: "ok"; readonly openPx: number }
    /** 503: nothing cached yet; retryAfterS is the header's whole seconds, or null when absent or unreadable. */
    | { readonly kind: "unavailable"; readonly retryAfterS: number | null }
    /** Anything else: a network error, another status, an unusable body. */
    | { readonly kind: "failed" };

/** Calls at most, in all, before the hook gives up. */
export const MAX_ATTEMPTS = 10;
/** Delay before the single retry after a failure other than a 503. */
export const OTHER_RETRY_MS = 2000;
export const MIN_RETRY_AFTER_S = 1;
export const MAX_RETRY_AFTER_S = 60;

/** A Retry-After header as whole seconds, or null when absent or not a non-negative integer. */
export function parseRetryAfter(header: string | null): number | null {
    if (header === null) return null;
    const trimmed = header.trim();
    if (!/^\d{1,9}$/.test(trimmed)) return null;
    return Number(trimmed);
}

/**
 * Milliseconds until the next attempt after a failed one, or null to stop.
 *
 * @param result         the failed attempt's result (never "ok")
 * @param attemptsMade   calls made so far, including this one
 * @param otherRetryUsed whether the one retry for a non-503 failure has been spent
 */
export function nextRetryDelayMs(
    result: Exclude<IgnitionResult, { kind: "ok" }>,
    attemptsMade: number,
    otherRetryUsed: boolean,
): number | null {
    if (attemptsMade >= MAX_ATTEMPTS) return null;
    if (result.kind === "unavailable") {
        const s = result.retryAfterS ?? MIN_RETRY_AFTER_S;
        return Math.min(MAX_RETRY_AFTER_S, Math.max(MIN_RETRY_AFTER_S, s)) * 1000;
    }
    return otherRetryUsed ? null : OTHER_RETRY_MS;
}
