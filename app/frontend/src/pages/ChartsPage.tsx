/**
 * Charts page (P10-4 scaffold, P12-3 chart fill, P13-4 panel fill, renamed from
 * the Price Chart page at P17-1; the chart panel keeps the title "Price chart").
 *
 * A two-region layout, the chart region (left) and the side-panel region (right).
 * P12-3 filled the chart region with <PriceChart>, fed from the shared reducer
 * state: state.tape is the session's trade-print series, and state.sessionOpenPx
 * is the engine's first-trade anchor (the same value the header Chg uses). The
 * optional openPx is the Alpaca market open (P11), threaded from App and drawn
 * only when it falls inside the plotted price domain.
 *
 * P13-4 fills the side-panel region, closing the arc that began at P10-4: the
 * "Side panel arrives in P13" placeholder is gone, replaced by <SessionStats> over
 * the session accumulators and a working ticket. The two self-contained charcoal
 * cards (each owning its own header band) stack directly in the one section, the
 * same way TradingPage stacks Depth over Depth curve, rather than through a
 * SidePanel wrapper that would hold two children and no logic (P13 D7).
 *
 * The ticket is the SAME <OrderEntry> the Trading page mounts, with no new prop and
 * no behavioural branch (P13 D3): a second entry component would duplicate the
 * dollars-to-units parse and the validation, which is the part least worth having
 * twice. Compact panel density is a CSS concern and lands in P13-5. bestBidPx
 * and bestAskPx are passed from state.book deliberately: omitting them leaves
 * OrderEntry's -1 defaults in place, which permanently disables the Bid, Mid and
 * Ask chips and renders three dashes, so the ticket would read as broken rather
 * than compact (P13-0 decision C).
 *
 * This page mounts OrderEntry WITHOUT a ref. The Trading page needs the
 * OrderEntryHandle seam because a depth-curve click prefills its price; there is no
 * depth curve here, so there is no handle, no OrderEntryHandle import and no page
 * state at all. OrderEntry is a forwardRef component, so mounting it without a ref
 * is ordinary.
 *
 * No encoder import and no ClOrdId minting here either: App stays the sole call
 * site of nextClOrdId and the frame builders (the P10 D3 invariant), passing down
 * the bound handler and the display-only preview. The only protocol import this
 * page gains is the OrderIntent type in the handler signature (P14-6).
 *
 * P17: the chart reads the bars slice on the page surface's own chart settings,
 * and the side panel gains the session VWAP row (sessionNotional / sessionVolume).
 * Charts follow-up: the chart draws in fit mode at its real size (a 12:5 plot box,
 * measured), so labels read at their true pixel size rather than a scaled drawing.
 *
 * An order submitted here goes through App's one handleSubmit, so it is the same
 * wire path as the Trading page: it appears in the blotter and open orders when
 * navigating back, and its fill prints on the tape, the chart and these stats.
 *
 * Because useOrderBook lives above the router in App, leaving and returning to this
 * route remounts the page. The socket, reducer state and session accumulators
 * survive (that is the load-bearing routing guarantee), but a half-typed ticket
 * clears, which is the consequence already accepted at P10 and not introduced here.
 */

import type { AppState } from "../state/reducer";
import type { OrderIntent } from "../protocol/messages";

import { PriceChart } from "../components/PriceChart";
import { buildOwnExecutions } from "../ownExecutions";
import { SessionStats } from "../components/SessionStats";
import { OrderEntry } from "../components/OrderEntry";
import { PAGE_NAMES } from "../documentTitle";
import { useDocumentTitle } from "../useDocumentTitle";

export interface ChartsPageProps {
    readonly state: AppState;
    readonly openPx?: number;
    readonly onSubmitOrder: (intent: OrderIntent) => void;
    /** Opens the floating chart on the trading page (wired by App). */
    readonly onViewInTrading?: () => void;
}

export function ChartsPage({
                                   state,
                                   openPx,
                                   onSubmitOrder,
                                   onViewInTrading,
                               }: ChartsPageProps) {
    useDocumentTitle(PAGE_NAMES.charts);
    const connected = state.connection === "open";
    // P18-2: the one own-execution model (P18 D1) feeds the chart's overlays.
    const own = buildOwnExecutions(state);

    return (
        <main className="chart-page" aria-label="Price chart">
            <section tabIndex={-1} className="chart-page__region chart-page__region--chart" aria-label="Chart">
                <PriceChart
                    tape={state.tape}
                    bars={state.bars}
                    surface="page"
                    fills={own.fills}
                    sessionOpenPx={state.sessionOpenPx}
                    openPx={openPx}
                    onViewInTrading={onViewInTrading}
                    fit
                />
            </section>

            <section tabIndex={-1} className="chart-page__region chart-page__region--panel" aria-label="Side panel">
                <SessionStats
                    tape={state.tape}
                    sessionHighPx={state.sessionHighPx}
                    sessionLowPx={state.sessionLowPx}
                    sessionTradeCount={state.sessionTradeCount}
                    sessionNotional={state.sessionNotional}
                    sessionVolume={state.sessionVolume}
                />

                <OrderEntry
                    onSubmit={onSubmitOrder}
                    disabled={!connected}
                    bestBidPx={state.book.bestBid}
                    bestAskPx={state.book.bestAsk}
                />
            </section>
        </main>
    );
}
