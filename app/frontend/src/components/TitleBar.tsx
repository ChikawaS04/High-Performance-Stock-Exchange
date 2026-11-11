/**
 * Title bar: the terminal's top line (DESIGN_SYSTEM.md §15.3).
 *
 * A thin app-frame band above the instrument strip, modelled on the TWS Mosaic
 * anchor bar. It carries the product identity and the connection, never market
 * data. Left: the §15.2 directROUTE lockup as text (no icon; the icon is the
 * favicon). Right: the venue (CREDO), a hairline divider, and the connection
 * badge, which moved here from the instrument strip because connection is app
 * state, not instrument data.
 *
 * A plain div band: not a landmark, nothing focusable. The badge keeps its own
 * role="status". Nothing else belongs here until a real feature needs the space.
 */

import { ConnectionBadge } from "./ConnectionBadge";
import type { ConnectionStatus } from "../state/reducer";

export interface TitleBarProps {
    readonly connection: ConnectionStatus;
}

export function TitleBar({ connection }: TitleBarProps) {
    return (
        <div className="titlebar" data-testid="titlebar">
            <span className="titlebar__lockup" data-testid="titlebar-lockup">
                <span className="titlebar__direct">direct</span>
                <span className="titlebar__route">ROUTE</span>
                {" "}
                <span className="titlebar__arrow" aria-hidden="true">{"→"}</span>
            </span>

            <div className="titlebar__right">
                <span className="titlebar__venue" data-testid="titlebar-venue">
                    <span className="titlebar__venue-label">Venue</span>
                    <span className="titlebar__venue-name">CREDO</span>
                </span>
                <span className="titlebar__divider" aria-hidden="true" />
                <ConnectionBadge status={connection} />
            </div>
        </div>
    );
}
