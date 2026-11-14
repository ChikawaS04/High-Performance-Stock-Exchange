/**
 * Composition root. Owns the socket and the routed shell (P10-2).
 *
 * The socket lives here, above <Routes>, and must never move into a page: a route
 * change swaps only the element below the persistent strip and navbar, so the one
 * useOrderBook instance, the reducer state, and the book all survive navigation.
 * App also stays the sole encoder call site (nextClOrdId + the frame builders); the
 * pages receive state slices and the two bound handlers, never the encoders, so the
 * established "App is the only place a ClOrdId is minted" invariant is preserved.
 *
 * P10-3: the whole trading body (toolbar, workspace panels, FIX inspector, and the
 * entryRef / click-to-ticket seam) moved verbatim into pages/TradingPage. App no
 * longer holds inspector or entryRef state; those are Trading-local and live there.
 *
 * P10-5: the Header gains an optional openPx seam for the P11 ignition price.
 *
 * P11-4: that seam is now filled. useIgnitionPrice() fetches the official market
 * open once on app open, entirely off the socket, and its openPx flows into the
 * Header prop (undefined until it resolves, which renders the "—" sentinel). It is a
 * separate hook from useOrderBook and never touches the reducer or the hot path. Chg
 * is not affected: it stays anchored to the session's first trade (P11 D6).
 *
 * P13-4: the chart route (/charts since P17-1) receives the same order-entry prop /trading already had,
 * onSubmitOrder, because the Charts side panel mounts a ticket. Both routes
 * therefore share one handleSubmit and one nextClOrdId call site, which is the
 * point: the second ticket adds a second CONSUMER of the encoder seam, never a
 * second owner of it.
 *
 * Ticket redesign: the ticket dropped its client-assigned Order ID readout, so the
 * display-only clOrdIdPreview prop (nextClOrdId.peek()) is no longer threaded to
 * either page. nextClOrdId() stays the one mint point on submit; only the preview
 * peek is gone.
 *
 * P14-6: handleSubmit takes the ticket's resolved OrderIntent (decision H) and
 * hands it to newOrderFrame with a freshly minted clOrdId. The intent carries the
 * order type and time in force; App neither inspects nor defaults them, so it needs
 * no further change at P14-8 / P14-10.
 *
 * The TitleBar, Header strip and Navbar render above <Routes> so all three persist
 * across pages. The connection badge lives in the TitleBar (DESIGN_SYSTEM.md §15.3),
 * visible on every route; the Header no longer takes the connection.
 *
 * P17-1: the Price Chart page is renamed Charts (route /charts, page ChartsPage).
 * /chart stays as a replace redirect so a bookmark or persisted link still lands.
 *
 * UI polish D4: the OrderToasts stack also renders here, outside <Routes>, so an
 * order notification shows whichever page is open.
 *
 * P18-5: drag to reprice. handleReprice is the one seam both chart surfaces call
 * after Confirm Modify. It sends the CANCEL with reprice meta, which starts the
 * sequence in the reducer (reprice.ts). When the reducer marks a sequence ready
 * (ORDER_CANCELLED for the original arrived), the effect below mints the
 * replacement's id and sends the NEW here, so App stays the sole encoder call site
 * (P10 D3). A ref set of originals whose replacement was already sent makes that
 * exactly once whatever the render timing (StrictMode included), and a failed write
 * aborts the sequence through REPRICE_SEND_FAILED rather than leaving it ready. The
 * timeout timer lives here too, at the component edge (useRepriceTimer).
 */

import { useEffect, useRef, useState } from "react";

import { Navigate, Route, Routes, useNavigate } from "react-router-dom";

import { useOrderBook } from "./state/useOrderBook";
import { useIgnitionPrice } from "./state/useIgnitionPrice";
import { useRepriceTimer } from "./state/useRepriceTimer";
import { isCancellable, sessionVwap } from "./state/reducer";
import { arrivalFrom } from "./arrival";
import { replacementIntent } from "./reprice";
import { cancelOrderFrame, newOrderFrame, nextClOrdId } from "./protocol/encode";
import type { OrderIntent } from "./protocol/messages";

import { TitleBar } from "./components/TitleBar";
import { Header } from "./components/Header";
import { Navbar } from "./components/Navbar";
import { OrderToasts } from "./components/OrderToasts";
import { TradingPage } from "./pages/TradingPage";
import { ChartsPage } from "./pages/ChartsPage";

import "./styles/terminal.css";

export default function App() {
    const { state, send, dispatchLocal } = useOrderBook();
    const { openPx } = useIgnitionPrice();
    const navigate = useNavigate();
    const [priceOverlayOpen, setPriceOverlayOpen] = useState(false);

    // "View in trading" on the price-chart page: jump to the trading terminal and
    // float the chart there as a draggable window. The state lives here, above the
    // routes, so the intent survives the navigation that unmounts the chart page.
    const handleViewInTrading = (): void => {
        setPriceOverlayOpen(true);
        navigate("/trading");
    };

    const handleSubmit = (intent: OrderIntent): void => {
        // Arrival stamp (P18-8): the market as this render last saw it, at most one
        // render stale, which is what the trader saw when deciding: a fair
        // decision-time benchmark.
        const arrival = arrivalFrom(state.book.bestBid, state.book.bestAsk, sessionVwap(state), state.book.timestamp);
        send(newOrderFrame(nextClOrdId(), intent), { arrival });
    };

    const handleCancel = (origClOrdId: number): void => {
        send(cancelOrderFrame(nextClOrdId(), origClOrdId));
    };

    // Drag to reprice (P18-5): a working limit order with no reprice in flight.
    const handleReprice = (origClOrdId: number, newPx: number): void => {
        const row = state.myOrders.find((o) => o.clOrdId === origClOrdId);
        if (row === undefined || !isCancellable(row.status) || row.ordType !== "LIMIT") return;
        if (state.reprices.some((r) => r.origClOrdId === origClOrdId)) return;
        send(cancelOrderFrame(nextClOrdId(), origClOrdId), { reprice: { newPx } });
    };

    // The replacement NEW, once per original, after its cancel is confirmed.
    const replaced = useRef(new Set<number>());
    useEffect(() => {
        for (const r of state.reprices) {
            if (r.status !== "ready" || replaced.current.has(r.origClOrdId)) continue;
            const row = state.myOrders.find((o) => o.clOrdId === r.origClOrdId);
            if (row === undefined) continue;
            replaced.current.add(r.origClOrdId);
            const ok = send(newOrderFrame(nextClOrdId(), replacementIntent(row, r)), { repriceOf: r.origClOrdId });
            if (!ok) dispatchLocal({ type: "REPRICE_SEND_FAILED", origClOrdId: r.origClOrdId });
        }
    }, [state.reprices, state.myOrders, send, dispatchLocal]);

    useRepriceTimer(state.reprices, (origClOrdId) => dispatchLocal({ type: "REPRICE_TIMEOUT", origClOrdId }));
    const repricing = state.reprices.map((r) => r.origClOrdId);

    return (
        <div className="app">
            <header className="topbar">
                <TitleBar connection={state.connection} />
                <Header
                    book={state.book}
                    tape={state.tape}
                    orders={state.myOrders}
                    sessionVolume={state.sessionVolume}
                    sessionOpenPx={state.sessionOpenPx}
                    lastFrameNanos={state.lastFrameNanos}
                    openPx={openPx}
                />
            </header>

            <Navbar />

            <Routes>
                <Route index element={<Navigate to="/trading" replace />} />
                <Route
                    path="/trading"
                    element={
                        <TradingPage
                            state={state}
                            openPx={openPx}
                            onSubmitOrder={handleSubmit}
                            onCancelOrder={handleCancel}
                            priceOverlayOpen={priceOverlayOpen}
                            onClosePriceOverlay={() => setPriceOverlayOpen(false)}
                            onReprice={handleReprice}
                            repricing={repricing}
                        />
                    }
                />
                <Route
                    path="/charts"
                    element={
                        <ChartsPage
                            state={state}
                            openPx={openPx}
                            onSubmitOrder={handleSubmit}
                            onViewInTrading={handleViewInTrading}
                            onReprice={handleReprice}
                            repricing={repricing}
                        />
                    }
                />
                {/* P17-1: the pre-rename route, kept so an old bookmark still lands on Charts. */}
                <Route path="/chart" element={<Navigate to="/charts" replace />} />
            </Routes>

            {/* UI polish D4: order notifications, above the routes so they show on every page. */}
            <OrderToasts notices={state.orderNotices} />

            <footer className="terminal-footer" aria-label="Terminal status bar">
                <span className="terminal-footer__item terminal-footer__settings">
                    Settings
                </span>
            </footer>
        </div>
    );
}
