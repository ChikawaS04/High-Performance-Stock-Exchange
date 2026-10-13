/**
 * Trading terminal page (extracted from App in P10-3).
 *
 * A presentational consumer of the shared reducer state and the order / cancel
 * handlers owned by App. No encoder or ClOrdId logic lives here: App remains the
 * sole call site of nextClOrdId and the frame builders. The ticket redesign dropped
 * its Order ID readout, so the page no longer receives or forwards the display-only
 * clOrdIdPreview.
 *
 * The entryRef seam (OrderEntryHandle via forwardRef / useImperativeHandle) and the
 * click-to-ticket prefill relocate here verbatim from the pre-P10 App: a depth-curve
 * click calls entryRef.current?.setPrice(pricePx) with no intermediate state. The
 * FIX inspector open / close flag is likewise page-local.
 *
 * Because useOrderBook lives above the router in App, leaving and returning to this
 * route remounts the page: the socket and reducer state survive (that is the load-
 * bearing routing guarantee), but this page's own local UI state resets, which is
 * within the P10 decision. That covers the inspector open flag here and any state
 * held inside the panels (the depth selector, tape filters, a half-typed ticket).
 *
 * P14-6: onSubmitOrder now carries the ticket's resolved OrderIntent (decision H).
 * The page only forwards it to OrderEntry, so the change is a pure type widening.
 *
 * Blotter TWS rework (2026-10): the blotter panel now renders only OpenOrders, which
 * owns its own ORDERS header band and the Cancel-by-ID flow (a header dropdown ->
 * popup). The old loose "Open orders" / "Cancel by ID" headings and the always-
 * visible CancelTicket section are gone; the blotter fills the panel. The cancel-by-
 * id route is unchanged: OpenOrders emits the same onCancelOrder the per-row cancel
 * uses. OpenOrders takes disabled while the socket is down, as the ticket did.
 */

import { useRef, useState } from "react";

import type { AppState } from "../state/reducer";
import type { OrderIntent } from "../protocol/messages";

import { DepthLadder } from "../components/DepthLadder";
import { DepthCurve } from "../components/DepthCurve";
import { TradeTape } from "../components/TradeTape";
import { OrderEntry } from "../components/OrderEntry";
import type { OrderEntryHandle } from "../components/OrderEntry";
import { OpenOrders } from "../components/OpenOrders";
import { FixInspector } from "../components/FixInspector";

export interface TradingPageProps {
    readonly state: AppState;
    readonly onSubmitOrder: (intent: OrderIntent) => void;
    readonly onCancelOrder: (origClOrdId: number) => void;
}

export function TradingPage({
    state,
    onSubmitOrder,
    onCancelOrder,
}: TradingPageProps) {
    const connected = state.connection === "open";
    const [inspectorOpen, setInspectorOpen] = useState(false);
    const entryRef = useRef<OrderEntryHandle>(null);

    return (
        <>
            <div className="toolbar">
                <button
                    type="button"
                    className="toolbar__inspector-btn"
                    data-testid="open-fix-inspector"
                    onClick={() => setInspectorOpen(true)}
                >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path fill="currentColor" d="M4 5h16v2H4zm0 6h16v2H4zm0 6h10v2H4z" />
                    </svg>
                    FIX inspector
                </button>
            </div>

            <main className="workspace">
                <div className="workspace__col">
                    <section
                        tabIndex={-1}
                        className={`panel panel--ladder${connected ? "" : " panel--stale"}`}
                        aria-label="Order book"
                    >
                        <DepthLadder
                            book={state.book}
                            lastPx={state.tape.length > 0 ? state.tape[0].pricePx : -1}
                        />
                    </section>

                    <section
                        tabIndex={-1}
                        className={`panel panel--depthchart${connected ? "" : " panel--stale"}`}
                        aria-label="Depth chart"
                    >
                        <div className="depth-chart__head">
                            <span className="depth-chart__panel-title">Depth Chart</span>
                        </div>
                        <DepthCurve
                            book={state.book}
                            onPriceSelect={(pricePx) => entryRef.current?.setPrice(pricePx)}
                        />
                    </section>
                </div>

                <section tabIndex={-1} className="panel panel--tape" aria-label="Trade tape">
                    <h2 className="panel__title">Trades</h2>
                    <TradeTape tape={state.tape} />
                </section>

                <div className="workspace__col">
                    <section tabIndex={-1} className="panel panel--entry" aria-label="Order entry">
                        <OrderEntry
                            ref={entryRef}
                            onSubmit={onSubmitOrder}
                            disabled={!connected}
                            bestBidPx={state.book.bestBid}
                            bestAskPx={state.book.bestAsk}
                        />
                    </section>

                    <section tabIndex={-1} className="panel panel--blotter" aria-label="Order blotter">
                        <OpenOrders
                            orders={state.myOrders}
                            onCancel={onCancelOrder}
                            disabled={!connected}
                        />
                    </section>
                </div>
            </main>

            <FixInspector
                entries={state.inspectorLog}
                open={inspectorOpen}
                onClose={() => setInspectorOpen(false)}
            />
        </>
    );
}
