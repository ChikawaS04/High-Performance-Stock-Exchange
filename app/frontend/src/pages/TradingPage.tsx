/**
 * Trading terminal page (extracted from App in P10-3).
 *
 * A presentational consumer of the shared reducer state and the order / cancel
 * handlers owned by App. No encoder or ClOrdId logic lives here: App remains the
 * sole call site of nextClOrdId and the frame builders, and passes down only the
 * display-only clOrdIdPreview the ticket needs.
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
 */

import { useRef, useState } from "react";

import type { AppState } from "../state/reducer";
import type { Side } from "../protocol/messages";

import { DepthLadder } from "../components/DepthLadder";
import { DepthCurve } from "../components/DepthCurve";
import { TradeTape } from "../components/TradeTape";
import { OrderEntry } from "../components/OrderEntry";
import type { OrderEntryHandle } from "../components/OrderEntry";
import { OpenOrders } from "../components/OpenOrders";
import { CancelTicket } from "../components/CancelTicket";
import { FixInspector } from "../components/FixInspector";

export interface TradingPageProps {
    readonly state: AppState;
    readonly onSubmitOrder: (side: Side, pricePx: number, qty: number) => void;
    readonly onCancelOrder: (origClOrdId: number) => void;
    readonly clOrdIdPreview: number;
}

export function TradingPage({
    state,
    onSubmitOrder,
    onCancelOrder,
    clOrdIdPreview,
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
                    FIX inspector
                </button>
            </div>

            <main className="workspace">
                <section
                    className={`panel panel--ladder${connected ? "" : " panel--stale"}`}
                    aria-label="Order book depth"
                >
                    <h2 className="panel__title">Depth</h2>
                    <DepthLadder
                        book={state.book}
                        lastPx={state.tape.length > 0 ? state.tape[0].pricePx : -1}
                    />
                    <h2 className="panel__title panel__title--chart">Depth chart</h2>
                    <DepthCurve
                        book={state.book}
                        onPriceSelect={(pricePx) => entryRef.current?.setPrice(pricePx)}
                    />
                </section>

                <section className="panel panel--tape" aria-label="Trade tape">
                    <h2 className="panel__title">Trades</h2>
                    <TradeTape tape={state.tape} />
                </section>

                <div className="workspace__col">
                    <section className="panel panel--entry" aria-label="Order entry">
                        <h2 className="panel__title">Order entry</h2>
                        <OrderEntry
                            ref={entryRef}
                            onSubmit={onSubmitOrder}
                            disabled={!connected}
                            bestBidPx={state.book.bestBid}
                            bestAskPx={state.book.bestAsk}
                            clOrdIdPreview={clOrdIdPreview}
                        />
                    </section>

                    <section className="panel panel--blotter" aria-label="Order blotter">
                        <h2 className="panel__title">Open orders</h2>
                        <OpenOrders orders={state.myOrders} onCancel={onCancelOrder} />
                        <h2 className="panel__title panel__title--spaced panel__title--cancel">Cancel by ID</h2>
                        <CancelTicket onCancel={onCancelOrder} disabled={!connected} />
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
