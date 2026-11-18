/**
 * Resolves the terminal's WebSocket URL (P16-4, D10). Pure: the page location is passed in.
 *
 * In production the page, /ws, /api, /health and /market share one origin, so the default is the
 * relative path /ws. A relative URL in the WebSocket constructor is not safe to rely on in every
 * browser, so it is resolved here against the page: wss: under https:, ws: otherwise, same host and
 * port. An absolute ws:// or wss:// URL (a VITE_WS_URL override) passes through unchanged; an
 * absolute http:// or https:// one keeps its host and becomes ws:// or wss://.
 */

/** The parts of window.location the resolution reads. */
export type PageLocation = Pick<Location, "protocol" | "host">;

/** The default endpoint, same-origin. */
export const DEFAULT_WS_PATH = "/ws";

export function resolveWsUrl(base: string, location: PageLocation): string {
    if (/^wss?:\/\//i.test(base)) return base;
    const url = new URL(base, `${location.protocol}//${location.host}/`);
    // A relative base takes the page's scheme; an absolute http(s) base keeps its own.
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    return url.href;
}
