/**
 * Trade tape (SRS §3.7), refined in P7-6 into a real time-and-sales.
 *
 * Still purely presentational and single-slice: it takes only the reducer's
 * `tape` slice and owns no socket, hook, or reducer access. The reducer remains
 * the sole authority on what enters the tape (fills only, newest-first, capped at
 * TAPE_CAP), so this component never sorts or caps; the only state it owns is view
 * state (a block-size filter and a clock-precision toggle), and the filter / tick
 * maths are pure exported helpers (mirroring buildLadder), tested separately.
 *
 * What P7-6 adds, and the limits it states rather than hides:
 *
 *  - Wall-clock time column. TapeEntry.timestamp is epoch nanoseconds since P7-1
 *    (one shared clock across the wire), so it renders as local HH:MM:SS.mmm with
 *    a precision toggle extending to full nanoseconds. The P5-3 "no wall-clock"
 *    limit is retired. Precision limit, stated in formatClockNanos: below roughly a
 *    microsecond the digits reflect JSON-number (IEEE-754) transport rounding, not
 *    engine truth.
 *
 *  - Aggressor side, at the only fidelity the wire supports. No EXEC frame carries
 *    a side, so it is derivable only for trades touching one of this client's own
 *    orders (aggressorSide on TapeEntry, computed in the reducer where myOrders is
 *    in scope). That set is exactly the `mine` set, so a BUY/SELL tag appears on the
 *    focus-blue own-trade rows and anonymous prints show none. Shown as the Side
 *    cell, styled exactly like the open-orders Side cell (DESIGN_SYSTEM.md §9): the
 *    legible quote colour on a translucent wash of the same hue.
 *
 *  - Tick colouring: each print's price is coloured against the previous print (the
 *    older neighbour in this newest-first slice) — up green, down red, equal flat.
 *    Equal consecutive prices are pure flat (no zero-tick carry): the colour says
 *    "did THIS print move the price," and a repeat did not. The mine focus-blue wins
 *    on own rows.
 *
 *  - Block-size filters (all / >200 / >500), strict-greater, applied at render over
 *    the already-capped newest-first slice, so filtering preserves order and the cap
 *    and adds no reaccumulation.
 *
 * P8 terminal rework (TWS charcoal): the panel now carries its own card chrome (a
 * --head header band over a --well body, 4px radius, overflow hidden) so it reads as
 * one family with the blotter. The old inline control row (All / >200 / >500 and the
 * ms/ns button) is consolidated behind a single settings gear in the header band;
 * clicking it opens the standard settings popup (DESIGN_SYSTEM.md §10/§11) with a
 * Display category holding a Levels radio set and a Time-units toggle. The gear, the
 * radio set and the toggle are the terminal-wide shared controls (§11), not
 * tape-private widgets. View state and the pure helpers are unchanged.
 *
 * Prices go through format.ts; a -1 would render as EMPTY_PRICE, never a negative.
 * The P5-3 class/testid hooks and the `--mine` marker are preserved.
 */

import { useState } from "react";

import { formatPrice, formatClockNanos } from "../format";
import type { ClockPrecision } from "../format";
import type { TapeEntry } from "../state/reducer";

export type BlockFilter = "all" | "gt200" | "gt500";

/** Strict-greater block-size predicate, matching the ">200" / ">500" labels. */
export function passesBlockFilter(quantity: number, filter: BlockFilter): boolean {
    switch (filter) {
        case "gt200":
            return quantity > 200;
        case "gt500":
            return quantity > 500;
        case "all":
            return true;
    }
}

export type TickDirection = "up" | "down" | "flat";

/**
 * Per-print price direction against the chronologically previous print. The tape
 * is newest-first, so the older neighbour of entry i is entry i+1; the oldest
 * print (last) has no previous and is flat. Equal consecutive prices are flat, not
 * a zero-tick carry. Pure over whatever list it is given, so filtering before
 * calling makes each visible row compare to the previous VISIBLE print.
 */
export function tickDirections(entries: readonly TapeEntry[]): TickDirection[] {
    return entries.map((entry, i) => {
        const prev = entries[i + 1];
        if (prev === undefined) return "flat";
        if (entry.pricePx > prev.pricePx) return "up";
        if (entry.pricePx < prev.pricePx) return "down";
        return "flat";
    });
}

const FILTERS: readonly { readonly value: BlockFilter; readonly label: string }[] = [
    { value: "all", label: "All" },
    { value: "gt200", label: ">200" },
    { value: "gt500", label: ">500" },
];

interface TradeTapeProps {
    readonly tape: readonly TapeEntry[];
}

export function TradeTape({ tape }: TradeTapeProps) {
    const [filter, setFilter] = useState<BlockFilter>("all");
    const [precision, setPrecision] = useState<ClockPrecision>("ms");
    // Settings popup open state, and the Display category's expand state.
    const [settingsOpen, setSettingsOpen] = useState(false);
    const [displayOpen, setDisplayOpen] = useState(true);

    // Filter the already-capped, newest-first slice at render: an order-preserving
    // subset, no reaccumulation, no refetch, cap and order intact by construction.
    const shown = tape.filter((entry) => passesBlockFilter(entry.quantity, filter));
    const directions = tickDirections(shown);

    const emptyMessage = tape.length === 0 ? "No trades yet" : "No prints at this size";

    return (
        <div className="trade-tape">
            <div className="trade-tape__head-band">
                <span className="trade-tape__panel-title">Trades</span>
                <button
                    type="button"
                    className="term-gear"
                    aria-label="Tape settings"
                    aria-haspopup="dialog"
                    aria-expanded={settingsOpen}
                    data-testid="tape-settings-gear"
                    onClick={() => setSettingsOpen((open) => !open)}
                >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path
                            fill="currentColor"
                            d="M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.82,11.69,4.82,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z"
                        />
                    </svg>
                </button>

                {settingsOpen && (
                    <>
                        <div
                            className="settings-pop__backdrop"
                            onClick={() => setSettingsOpen(false)}
                            data-testid="tape-settings-backdrop"
                        />
                        <div
                            className="settings-pop"
                            role="dialog"
                            aria-label="Tape settings"
                            data-testid="tape-settings"
                        >
                            <div className="settings-pop__head">
                                <span className="settings-pop__title">Settings</span>
                                <button
                                    type="button"
                                    className="settings-pop__x"
                                    aria-label="Close"
                                    onClick={() => setSettingsOpen(false)}
                                    data-testid="tape-settings-close"
                                >
                                    {"×"}
                                </button>
                            </div>

                            <div className="settings-pop__body">
                                <button
                                    type="button"
                                    className="settings-pop__cat"
                                    aria-expanded={displayOpen}
                                    aria-controls="tape-settings-display"
                                    onClick={() => setDisplayOpen((open) => !open)}
                                    data-testid="tape-settings-display-toggle"
                                >
                                    <span>Display</span>
                                    <span className="settings-pop__caret" aria-hidden="true">{"▸"}</span>
                                </button>

                                {displayOpen && (
                                    <div className="settings-pop__panel" id="tape-settings-display">
                                        <div className="settings-pop__row">
                                            <span className="settings-pop__name">Levels</span>
                                            <span
                                                className="settings-radio"
                                                role="radiogroup"
                                                aria-label="Block size levels"
                                            >
                                                {FILTERS.map((f) => (
                                                    <label key={f.value} className="settings-radio__opt">
                                                        <input
                                                            type="radio"
                                                            name="tape-levels"
                                                            checked={filter === f.value}
                                                            onChange={() => setFilter(f.value)}
                                                            data-testid={`tape-filter-${f.value}`}
                                                        />
                                                        {f.label}
                                                    </label>
                                                ))}
                                            </span>
                                        </div>

                                        <div className="settings-pop__row">
                                            <span className="settings-pop__name">Time units</span>
                                            <span
                                                className="settings-toggle"
                                                role="group"
                                                aria-label="Timestamp precision"
                                            >
                                                <button
                                                    type="button"
                                                    className="settings-toggle__opt"
                                                    aria-pressed={precision === "ms"}
                                                    onClick={() => setPrecision("ms")}
                                                    data-testid="tape-precision-ms"
                                                >
                                                    ms
                                                </button>
                                                <button
                                                    type="button"
                                                    className="settings-toggle__opt"
                                                    aria-pressed={precision === "ns"}
                                                    onClick={() => setPrecision("ns")}
                                                    data-testid="tape-precision-ns"
                                                >
                                                    ns
                                                </button>
                                            </span>
                                        </div>
                                    </div>
                                )}
                            </div>
                        </div>
                    </>
                )}
            </div>

            <div className="trade-tape__head" aria-hidden="true">
                <span className="trade-tape__head-time">Time</span>
                <span className="trade-tape__head-price">Price</span>
                <span className="trade-tape__head-qty">Size</span>
                <span className="trade-tape__head-side" title="Aggressor side, shown for your own trades only">
          Side
        </span>
            </div>

            {shown.length === 0 ? (
                <div className="trade-tape__empty" data-testid="tape-empty">
                    {emptyMessage}
                </div>
            ) : (
                <div className="trade-tape__rows">
                    {shown.map((entry, i) => {
                        const dir = directions[i];
                        const rowClass = entry.mine
                            ? "trade-tape__row trade-tape__row--mine"
                            : "trade-tape__row";
                        const sideClass =
                            entry.aggressorSide === "BUY"
                                ? "trade-tape__side trade-tape__side--buy"
                                : entry.aggressorSide === "SELL"
                                ? "trade-tape__side trade-tape__side--sell"
                                : "trade-tape__side";
                        return (
                            <div key={entry.tradeId} className={rowClass} data-testid="tape-row">
                <span className="trade-tape__time">
                  {formatClockNanos(entry.timestamp, precision)}
                </span>
                                <span className={`trade-tape__price trade-tape__price--${dir}`}>
                  {dir !== "flat" && (
                      <span className="trade-tape__arrow" aria-hidden="true">
                          {dir === "up" ? "▲" : "▼"}
                      </span>
                  )}
                                    {formatPrice(entry.pricePx)}
                </span>
                                <span className="trade-tape__qty">{entry.quantity}</span>
                                <span className={sideClass} data-testid="tape-side">
                  {entry.aggressorSide ?? ""}
                </span>
                            </div>
                        );
                    })}
                </div>
            )}
        </div>
    );
}
