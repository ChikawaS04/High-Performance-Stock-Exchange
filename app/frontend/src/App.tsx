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
 * P13-4: the /chart route now receives the same two order-entry props /trading
 * already had, onSubmitOrder and clOrdIdPreview, because the Price Chart side panel
 * mounts a ticket. Both routes therefore share one handleSubmit and one nextClOrdId
 * call site, which is the point: the second ticket adds a second CONSUMER of the
 * encoder seam, never a second owner of it. This is App's only P13 change.
 *
 * P14-6: handleSubmit takes the ticket's resolved OrderIntent (decision H) and
 * hands it to newOrderFrame with a freshly minted clOrdId. The intent carries the
 * order type and time in force; App neither inspects nor defaults them, so it needs
 * no further change at P14-8 / P14-10.
 *
 * The Header strip and Navbar render above <Routes> so both persist across pages;
 * the connection badge stays inside the Header, visible on every route.
 */

import { Navigate, Route, Routes } from "react-router-dom";

import { useOrderBook } from "./state/useOrderBook";
import { useIgnitionPrice } from "./state/useIgnitionPrice";
import { cancelOrderFrame, newOrderFrame, nextClOrdId } from "./protocol/encode";
import type { OrderIntent } from "./protocol/messages";

import { Header } from "./components/Header";
import { Navbar } from "./components/Navbar";
import { TradingPage } from "./pages/TradingPage";
import { PriceChartPage } from "./pages/PriceChartPage";

import "./styles/terminal.css";

export default function App() {
    const { state, send } = useOrderBook();
    const { openPx } = useIgnitionPrice();

    const handleSubmit = (intent: OrderIntent): void => {
        send(newOrderFrame(nextClOrdId(), intent));
    };

    const handleCancel = (origClOrdId: number): void => {
        send(cancelOrderFrame(nextClOrdId(), origClOrdId));
    };

    return (
        <div className="app">
            <header className="topbar">
                <Header
                    book={state.book}
                    tape={state.tape}
                    orders={state.myOrders}
                    sessionVolume={state.sessionVolume}
                    sessionOpenPx={state.sessionOpenPx}
                    lastFrameNanos={state.lastFrameNanos}
                    connection={state.connection}
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
                            onSubmitOrder={handleSubmit}
                            onCancelOrder={handleCancel}
                            clOrdIdPreview={nextClOrdId.peek()}
                        />
                    }
                />
                <Route
                    path="/chart"
                    element={
                        <PriceChartPage
                            state={state}
                            openPx={openPx}
                            onSubmitOrder={handleSubmit}
                            clOrdIdPreview={nextClOrdId.peek()}
                        />
                    }
                />
            </Routes>
        </div>
    );
}
