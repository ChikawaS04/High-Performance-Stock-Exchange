/**
 * Browser tab title (DESIGN_SYSTEM.md §15.6). Pure string builder, no DOM.
 *
 * Format: `<SYMBOL> · <Page> | directROUTE`, most specific first so a narrow tab
 * still shows what it is. The inner separator is a middle dot (U+00B7) with a space
 * either side; the brand follows a pipe. No live prices are ever written here.
 *
 * PAGE_NAMES is the single source for each page's name: the Navbar renders these
 * same strings as its tab labels, so the nav and the tab title never disagree.
 */

export const BRAND = "directROUTE";

export const PAGE_NAMES = {
    trading: "Trading",
    priceChart: "Price Chart",
} as const;

export type PageName = (typeof PAGE_NAMES)[keyof typeof PAGE_NAMES];

export function documentTitle(symbol: string, page: string): string {
    return `${symbol} · ${page} | ${BRAND}`;
}
