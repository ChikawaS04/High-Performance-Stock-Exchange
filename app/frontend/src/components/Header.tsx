/**
 * Header (SRS §3.7, rebuilt in P7-3; header polish in P9-Extras). One instrument
 * row now, the way a real terminal carries the top line. No product name, no
 * tagline: the phase strips marketing chrome and shows only what the server sent
 * or a documented client-side derivation.
 *
 * Instrument row, all derived from BOOK plus the session aggregates and your own
 * orders (never from EXEC arrival order): symbol, last, session change, bid, ask,
 * mid, spread in ticks and basis points, session volume, and the P9-Extras Filled
 * and Rem counters. The last-frame clock rides at the end of the same row as
 * session meta, and the reused ConnectionBadge is pinned to the top-right corner.
 *
 * P9-Extras: the P7-3 second "session row" is dissolved. The last-frame clock
 * moved up into the instrument row and the badge moved to the corner (CSS
 * margin-left:auto). Whole-number counters (Volume, Filled, Rem) render through
 * the shared format.ts formatQty so they carry thousands separators. P8-6 had
 * already removed the FIX session identity strip (SenderCompID / TargetCompID /
 * MsgSeqNum); the client MsgSeqNum counter still lives in reducer state and is
 * still incremented, only its header display is gone.
 *
 * Filled and Rem are YOUR-order aggregates, distinct from Volume (whole-market
 * session volume). Filled sums the executed portion over every one of your orders;
 * Rem sums the still-working remainder over your non-terminal orders. Both are
 * pure exported helpers (mirroring deriveHeader / buildLadder / validateOrderInput)
 * so they are unit-tested without a DOM. Spread ticks/bps and change stay local
 * pure helpers here; the midpoint and clock formatters live in format.ts so this
 * header, the depth-ladder divider, and the trade tape share one definition each.
 */

import { formatPrice, EMPTY_PRICE, formatClockNanos, formatQty, midpointLabel } from "../format";
import { ConnectionBadge } from "./ConnectionBadge";
import { isTerminal } from "../state/reducer";
import type { BookState, ConnectionStatus, MyOrder, TapeEntry } from "../state/reducer";

const SYMBOL = "ASML";

/** One cent, in units of $0.0001: the on-tick spacing the spread is counted in. */
const TICK_PX = 100;

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

    const changeClass =
        m.changeDir === "up"
            ? " header__value--up"
            : m.changeDir === "down"
                ? " header__value--down"
                : "";

    return (
        <div className="header">
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

                <ConnectionBadge status={connection} />
            </div>
        </div>
    );
}
