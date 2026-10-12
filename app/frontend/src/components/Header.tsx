/**
 * Header (SRS §3.7, rebuilt in P7-3; header polish in P9-Extras; charcoal rework
 * + settings gear in P-UI). One instrument row now, the way a real terminal
 * carries the top line. No product name, no tagline: the phase strips marketing
 * chrome and shows only what the server sent or a documented client-side
 * derivation.
 *
 * Instrument row, all derived from BOOK plus the session aggregates and your own
 * orders (never from EXEC arrival order): symbol, last, session change, bid, ask,
 * mid, spread in ticks and basis points, session volume, and the P9-Extras Filled
 * and Rem counters. The last-frame clock rides at the end of the same row as
 * session meta, and the reused ConnectionBadge is pinned to the top-right corner,
 * now preceded by a settings gear.
 *
 * Charcoal rework (P-UI): the strip is a thin (30px) panel-header band. ASML stays
 * bold but white; every value is white at the blotter row-data weight (IBM Plex
 * Mono, normal); labels are IBM Plex Sans, white by default. The green/red
 * switchers (Chg, Bid, Ask) keep their colours. A gear sits just left of the Live
 * badge in the dropdown-caret colour; it opens a settings popup (DESIGN_SYSTEM.md
 * §10) whose Layout section lets you set the header labels to white or faint grey.
 * The choice persists per browser in localStorage.
 *
 * Filled and Rem are YOUR-order aggregates, distinct from Volume (whole-market
 * session volume). Filled sums the executed portion over every one of your orders;
 * Rem sums the still-working remainder over your non-terminal orders. Both are
 * pure exported helpers (mirroring deriveHeader / buildLadder / validateOrderInput)
 * so they are unit-tested without a DOM. Spread ticks/bps and change stay local
 * pure helpers here; the midpoint and clock formatters live in format.ts so this
 * header, the depth-ladder divider, and the trade tape share one definition each.
 */

import { useEffect, useState } from "react";
import { formatPrice, EMPTY_PRICE, formatClockNanos, formatQty, midpointLabel } from "../format";
import { ConnectionBadge } from "./ConnectionBadge";
import { isTerminal } from "../state/reducer";
import type { BookState, ConnectionStatus, MyOrder, TapeEntry } from "../state/reducer";

const SYMBOL = "ASML";

/** One cent, in units of $0.0001: the on-tick spacing the spread is counted in. */
const TICK_PX = 100;

/** Header label appearance, chosen in the settings popup and persisted per browser. */
type LabelMode = "white" | "faint";
const LABEL_STORAGE_KEY = "header.labelMode";

function readLabelMode(): LabelMode {
    try {
        return localStorage.getItem(LABEL_STORAGE_KEY) === "faint" ? "faint" : "white";
    } catch {
        return "white";
    }
}

export type ChangeDirection = "up" | "down" | "flat" | "none";

export interface HeaderModel {
    readonly last: string;
    readonly changeAbs: string;
    readonly changePct: string;
    readonly changeDir: ChangeDirection;
    readonly bestBid: string;
    readonly bestAsk: string;
    readonly mid: string;
    readonly spreadTicks: string;
    readonly spreadBps: string;
    readonly volume: string;
    readonly filledQty: string;
    readonly remainingQty: string;
    readonly lastFrame: string;
}

export interface HeaderInput {
    readonly book: BookState;
    readonly tape: readonly TapeEntry[];
    readonly orders: readonly MyOrder[];
    readonly sessionVolume: number;
    readonly sessionOpenPx: number;
    readonly lastFrameNanos: number;
}

/** Both tops present and real (guards the -1 empty-book sentinel). */
function twoSided(bestBid: number, bestAsk: number): boolean {
    return bestBid > 0 && bestAsk > 0;
}

/** Spread as an integer number of ticks ("50"), or EMPTY_PRICE when one-sided. */
function spreadTicksLabel(bestBid: number, bestAsk: number): string {
    if (!twoSided(bestBid, bestAsk)) return EMPTY_PRICE;
    // Both tops are on the one-cent tick, so their difference is a whole number of
    // ticks: one cent is TICK_PX (100) units, so divide to count ticks.
    return String((bestAsk - bestBid) / TICK_PX);
}

/**
 * Spread in basis points relative to the mid, at fixed precision. bps =
 * spread / mid * 10000 = 20000 * (ask - bid) / (ask + bid). The ratio is
 * unit-invariant, so it is identical at the old cent scale or the new unit scale.
 * Only bps (an inherently fractional ratio) touches float, at the display edge;
 * prices stay integer units. EMPTY_PRICE when one-sided.
 */
function spreadBpsLabel(bestBid: number, bestAsk: number): string {
    if (!twoSided(bestBid, bestAsk)) return EMPTY_PRICE;
    const bps = (20000 * (bestAsk - bestBid)) / (bestAsk + bestBid);
    return bps.toFixed(2);
}

interface ChangeParts {
    readonly abs: string;
    readonly pct: string;
    readonly dir: ChangeDirection;
}

/**
 * Session change against the session's first trade price. Signed dollar delta
 * (from integer units) plus a signed percent (fractional, display edge only).
 * Blank until both a last price and a session-open price exist. There is no
 * previous close, so this is a session change, never a daily change. dir drives
 * the up/down colour.
 */
function changeLabel(lastPx: number, sessionOpenPx: number): ChangeParts {
    if (lastPx <= 0 || sessionOpenPx <= 0) {
        return { abs: EMPTY_PRICE, pct: EMPTY_PRICE, dir: "none" };
    }
    const deltaPx = lastPx - sessionOpenPx;
    const dir: ChangeDirection = deltaPx > 0 ? "up" : deltaPx < 0 ? "down" : "flat";
    const sign = deltaPx > 0 ? "+" : deltaPx < 0 ? "-" : "";
    const abs = `${sign}${formatPrice(Math.abs(deltaPx))}`;
    const pctValue = (deltaPx / sessionOpenPx) * 100;
    const pct = `${deltaPx > 0 ? "+" : ""}${pctValue.toFixed(2)}%`;
    return { abs, pct, dir };
}

/**
 * Total quantity of your orders executed this session (P9-Extras): the sum of the
 * filled portion, originalQty - remainingQty clamped at zero, over every order.
 * Mirrors OpenOrders.filledOf per row, kept inline so this header derivation stays
 * free of a sibling-component import. Honest across statuses because the reducer
 * preserves a cancelled row's unfilled remainder (nextOrder ORDER_CANCELLED), so a
 * cancelled order contributes only its pre-cancel fill and a PENDING / REJECTED row
 * contributes zero. Pure and exported for direct unit testing.
 */
export function sessionFilledQty(orders: readonly MyOrder[]): number {
    return orders.reduce((sum, o) => sum + Math.max(0, o.originalQty - o.remainingQty), 0);
}

/**
 * Your still-working size this session (P9-Extras): the sum of remainingQty over
 * non-terminal orders only (!isTerminal, i.e. PENDING / OPEN / PARTIALLY_FILLED).
 * Terminal rows (FILLED / CANCELLED / REJECTED) are excluded, so a cancel's
 * unfilled leftover never counts as working. Pure and exported for direct unit
 * testing.
 */
export function sessionWorkingQty(orders: readonly MyOrder[]): number {
    return orders.reduce((sum, o) => (isTerminal(o.status) ? sum : sum + o.remainingQty), 0);
}

/** Pure, exported: every header field from one state slice. */
export function deriveHeader(input: HeaderInput): HeaderModel {
    const { book, tape, orders, sessionVolume, sessionOpenPx, lastFrameNanos } = input;
    const lastPx = tape.length > 0 ? tape[0].pricePx : -1;
    const change = changeLabel(lastPx, sessionOpenPx);

    return {
        last: formatPrice(lastPx),
        changeAbs: change.abs,
        changePct: change.pct,
        changeDir: change.dir,
        bestBid: formatPrice(book.bestBid),
        bestAsk: formatPrice(book.bestAsk),
        mid: midpointLabel(book.bestBid, book.bestAsk),
        spreadTicks: spreadTicksLabel(book.bestBid, book.bestAsk),
        spreadBps: spreadBpsLabel(book.bestBid, book.bestAsk),
        volume: formatQty(sessionVolume),
        filledQty: formatQty(sessionFilledQty(orders)),
        remainingQty: formatQty(sessionWorkingQty(orders)),
        lastFrame: formatClockNanos(lastFrameNanos),
    };
}

/** Append a unit only to a real value, leaving the EMPTY_PRICE sentinel bare. */
function withUnit(value: string, unit: string): string {
    return value === EMPTY_PRICE ? value : `${value}${unit}`;
}

export interface HeaderProps {
    readonly book: BookState;
    readonly tape: readonly TapeEntry[];
    readonly orders: readonly MyOrder[];
    readonly sessionVolume: number;
    readonly sessionOpenPx: number;
    readonly lastFrameNanos: number;
    readonly connection: ConnectionStatus;
    /**
     * Session-open price in units for the Open field (P10-5 seam). Distinct from
     * sessionOpenPx above, which is the first-TRADE price that anchors Chg: this
     * is the market open (the Alpaca ignition price, arriving P11), rendered directly
     * in the JSX rather than through deriveHeader so the pure model stays book-derived.
     * Omitted this phase, so the field shows the "—" sentinel via formatPrice(-1)
     * until P11 supplies a value.
     */
    readonly openPx?: number;
}

export function Header({
                           book,
                           tape,
                           orders,
                           sessionVolume,
                           sessionOpenPx,
                           lastFrameNanos,
                           connection,
                           openPx,
                       }: HeaderProps) {
    const m = deriveHeader({ book, tape, orders, sessionVolume, sessionOpenPx, lastFrameNanos });

    // Settings popup: open/closed, the Layout section's expand state, and the
    // persisted header-label appearance.
    const [settingsOpen, setSettingsOpen] = useState(false);
    const [layoutOpen, setLayoutOpen] = useState(true);
    const [labelMode, setLabelMode] = useState<LabelMode>(readLabelMode);

    useEffect(() => {
        try {
            localStorage.setItem(LABEL_STORAGE_KEY, labelMode);
        } catch {
            // Per-viewer convenience only; a storage failure is non-fatal.
        }
    }, [labelMode]);

    useEffect(() => {
        if (!settingsOpen) return;
        function onKey(e: KeyboardEvent) {
            if (e.key === "Escape") setSettingsOpen(false);
        }
        document.addEventListener("keydown", onKey);
        return () => document.removeEventListener("keydown", onKey);
    }, [settingsOpen]);

    const changeClass =
        m.changeDir === "up"
            ? " header__value--up"
            : m.changeDir === "down"
                ? " header__value--down"
                : "";

    const rootClass = labelMode === "faint" ? "header header--labels-faint" : "header";

    return (
        <div className={rootClass}>
            <div className="header__instrument">
                <span className="header__ticker">{SYMBOL}</span>

                <div className="header__metric">
                    <span className="header__label">Last</span>
                    <span className="header__value" data-testid="header-last">{m.last}</span>
                </div>

                <div className="header__metric">
                    <span className="header__label">Chg</span>
                    <span className={`header__value${changeClass}`} data-testid="header-change">
            {m.changeAbs}
                        <span className="header__value-sub" data-testid="header-change-pct">{m.changePct}</span>
          </span>
                </div>

                <div className="header__metric">
                    <span className="header__label">Open</span>
                    <span className="header__value" data-testid="header-open">{formatPrice(openPx ?? -1)}</span>
                </div>

                <div className="header__metric header__metric--bid">
                    <span className="header__label">Bid</span>
                    <span className="header__value" data-testid="header-bid">{m.bestBid}</span>
                </div>

                <div className="header__metric header__metric--ask">
                    <span className="header__label">Ask</span>
                    <span className="header__value" data-testid="header-ask">{m.bestAsk}</span>
                </div>

                <div className="header__metric">
                    <span className="header__label">Mid</span>
                    <span className="header__value" data-testid="header-mid">{m.mid}</span>
                </div>

                <div className="header__metric">
                    <span className="header__label">Spread</span>
                    <span className="header__value" data-testid="header-spread">
            <span data-testid="header-spread-ticks">{withUnit(m.spreadTicks, "¢")}</span>
            <span className="header__value-sub" data-testid="header-spread-bps">
              {withUnit(m.spreadBps, " bps")}
            </span>
          </span>
                </div>

                <div className="header__metric">
                    <span className="header__label">Volume</span>
                    <span className="header__value" data-testid="header-volume">{m.volume}</span>
                </div>

                <div className="header__metric">
                    <span className="header__label">Filled</span>
                    <span className="header__value" data-testid="header-filled">{m.filledQty}</span>
                </div>

                <div className="header__metric">
                    <span className="header__label">Rem</span>
                    <span className="header__value" data-testid="header-remaining">{m.remainingQty}</span>
                </div>

                <div className="header__metric header__metric--last-frame">
                    <span className="header__label">Last frame</span>
                    <span className="header__value" data-testid="header-last-frame">{m.lastFrame}</span>
                </div>

                <div className="header__right">
                    <button
                        type="button"
                        className="header__gear"
                        aria-label="Settings"
                        aria-haspopup="dialog"
                        aria-expanded={settingsOpen}
                        onClick={() => setSettingsOpen((open) => !open)}
                        data-testid="header-settings-gear"
                    >
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                            <path
                                fill="currentColor"
                                d="M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.82,11.69,4.82,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z"
                            />
                        </svg>
                    </button>

                    <ConnectionBadge status={connection} />
                </div>
            </div>

            {settingsOpen && (
                <>
                    <div
                        className="header-settings__backdrop"
                        onClick={() => setSettingsOpen(false)}
                        data-testid="header-settings-backdrop"
                    />
                    <div
                        className="header-settings"
                        role="dialog"
                        aria-label="Terminal settings"
                        data-testid="header-settings"
                    >
                        <div className="header-settings__head">
                            <span className="header-settings__title">Settings</span>
                            <button
                                type="button"
                                className="header-settings__x"
                                aria-label="Close"
                                onClick={() => setSettingsOpen(false)}
                                data-testid="header-settings-close"
                            >
                                {"×"}
                            </button>
                        </div>

                        <div className="header-settings__body">
                            <button
                                type="button"
                                className="header-settings__cat"
                                aria-expanded={layoutOpen}
                                aria-controls="header-settings-layout"
                                onClick={() => setLayoutOpen((open) => !open)}
                                data-testid="header-settings-layout-toggle"
                            >
                                <span>Layout</span>
                                <span className="header-settings__caret" aria-hidden="true">{"▸"}</span>
                            </button>

                            {layoutOpen && (
                                <div className="header-settings__panel" id="header-settings-layout">
                                    <div className="header-settings__row">
                                        <span className="header-settings__name">Header labels</span>
                                        <span
                                            className="header-settings__choice"
                                            role="group"
                                            aria-label="Header label colour"
                                        >
                                            <button
                                                type="button"
                                                className="header-settings__opt"
                                                aria-pressed={labelMode === "white"}
                                                onClick={() => setLabelMode("white")}
                                                data-testid="header-labels-white"
                                            >
                                                White
                                            </button>
                                            <button
                                                type="button"
                                                className="header-settings__opt"
                                                aria-pressed={labelMode === "faint"}
                                                onClick={() => setLabelMode("faint")}
                                                data-testid="header-labels-faint"
                                            >
                                                Faint grey
                                            </button>
                                        </span>
                                    </div>
                                </div>
                            )}
                        </div>

                        <div className="header-settings__foot">
                            <button
                                type="button"
                                className="header-settings__done"
                                onClick={() => setSettingsOpen(false)}
                                data-testid="header-settings-done"
                            >
                                Done
                            </button>
                        </div>
                    </div>
                </>
            )}
        </div>
    );
}
