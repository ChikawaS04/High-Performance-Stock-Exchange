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
 *    focus-blue own-trade rows and anonymous prints show none. Shown as plain text,
 *    never a green/red colour, so it never competes with the tick colour or mine.
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
 * Prices go through format.ts; a -1 would render as EMPTY_PRICE, never a negative.
 * The P5-3 class/testid hooks and the `--mine` marker are preserved.
 */

import { useState } from "react";

import { centsToDollars, formatClockNanos } from "../format";
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
        if (entry.priceCents > prev.priceCents) return "up";
        if (entry.priceCents < prev.priceCents) return "down";
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

    // Filter the already-capped, newest-first slice at render: an order-preserving
    // subset, no reaccumulation, no refetch, cap and order intact by construction.
    const shown = tape.filter((entry) => passesBlockFilter(entry.quantity, filter));
    const directions = tickDirections(shown);

    const emptyMessage = tape.length === 0 ? "No trades yet" : "No prints at this size";

    return (
        <div className="trade-tape">
            <div className="trade-tape__controls">
                <div className="trade-tape__filter" role="group" aria-label="Block size filter">
                    {FILTERS.map((f) => (
                        <button
                            key={f.value}
                            type="button"
                            className={
                                filter === f.value
                                    ? "trade-tape__filter-btn trade-tape__filter-btn--active"
                                    : "trade-tape__filter-btn"
                            }
                            data-testid={`tape-filter-${f.value}`}
                            onClick={() => setFilter(f.value)}
                        >
                            {f.label}
                        </button>
                    ))}
                </div>
                <button
                    type="button"
                    className="trade-tape__precision"
                    data-testid="tape-precision"
                    aria-label="Toggle timestamp precision"
                    onClick={() => setPrecision((p) => (p === "ms" ? "ns" : "ms"))}
                >
                    {precision}
                </button>
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
                  {centsToDollars(entry.priceCents)}
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