/**
 * Session statistics block for the Price Chart side panel (P13-3).
 *
 * Presentational and total: it takes the four reducer slices, calls the pure
 * buildSessionStats, and renders the result. There is not one conditional in this
 * file. Every degenerate case (unset high/low sentinel, empty tape, zero trade
 * count) is already decided inside the helper, which returns a finished string for
 * every field, so the four rows mount unconditionally and the panel holds a stable
 * shape from first paint (P13 D10). Nothing here reflows when the first trade
 * prints; the dash is simply replaced by a number.
 *
 * Mirrors Header / deriveHeader: the component owns the props and the markup, the
 * pure function owns the derivation and is unit tested without a DOM. The one
 * structural difference is the props type. Header declares its own HeaderProps
 * because it adds two fields the pure input does not have (connection, openCents);
 * this component adds nothing, so SessionStatsProps is an alias of
 * SessionStatsInput rather than a second identical interface to keep in sync.
 *
 * Row markup follows the header's label/value convention so the two read as one
 * system: a dim 10px label above a 16px value, with a secondary value nested
 * INSIDE the primary one (the pattern Chg uses for its percent and Spread uses for
 * its bps). Last trade is one row carrying two numbers for that reason, price as
 * the value and size as the sub, rather than a fifth row, which would break the
 * locked four-row field set (P13 D2).
 *
 * The field set is deliberately what the persistent header strip does NOT show
 * (P13 D2): no Volume, Bid, Ask, Mid or Spread here, because those live in the
 * strip a few hundred pixels above and a second copy is worse than none. No
 * timestamp on the last trade either, which is what keeps spreadBpsLabel and the
 * clock formatter out of this file (P13 D6).
 *
 * Styling arrives in P13-5: the session-stats__* block, and the retheme selector
 * list that redirects numeric values from mono to sans with tabular figures. This
 * component only names the classes.
 */

import { buildSessionStats } from "../sessionStats";
import type { SessionStatsInput } from "../sessionStats";

/**
 * Exactly the pure helper's input. Declared as an alias rather than a duplicate
 * interface: this component adds no props of its own, so a second declaration
 * would be two things to keep in step for no gain.
 */
export type SessionStatsProps = SessionStatsInput;

export function SessionStats({
                                 tape,
                                 sessionHighCents,
                                 sessionLowCents,
                                 sessionTradeCount,
                             }: SessionStatsProps) {
    const m = buildSessionStats({ tape, sessionHighCents, sessionLowCents, sessionTradeCount });

    return (
        <div className="session-stats">
            <div className="session-stats__row">
                <span className="session-stats__label">High</span>
                <span className="session-stats__value" data-testid="session-high">{m.high}</span>
            </div>

            <div className="session-stats__row">
                <span className="session-stats__label">Low</span>
                <span className="session-stats__value" data-testid="session-low">{m.low}</span>
            </div>

            <div className="session-stats__row">
                <span className="session-stats__label">Trades</span>
                <span className="session-stats__value" data-testid="session-trades">{m.trades}</span>
            </div>

            <div className="session-stats__row">
                <span className="session-stats__label">Last trade</span>
                <span className="session-stats__value" data-testid="session-last-price">
          {m.lastPrice}
                    <span className="session-stats__value-sub" data-testid="session-last-size">
            {m.lastSize}
          </span>
        </span>
            </div>
        </div>
    );
}
