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
 * P10-5: the Header gains an optional openCents seam for the P11 ignition price.
 *
 * P11-4: that seam is now filled. useIgnitionPrice() fetches the official market
 * open once on app open, entirely off the socket, and its openCents flows into the
 * Header prop (undefined until it resolves, which renders the "—" sentinel). It is a
 * separate hook from useOrderBook and never touches the reducer or the hot path. Chg
 * is not affected: it stays anchored to the session's first trade (P11 D6).
 *
 * The Header strip and Navbar render above <Routes> so both persist across pages;
 * the connection badge stays inside the Header, visible on every route.
 */

import { Navigate, Route, Routes } from "react-router-dom";

import { useOrderBook } from "./state/useOrderBook";
import { useIgnitionPrice } from "./state/useIgnitionPrice";
import { cancelOrderFrame, newOrderFrame, nextClOrdId } from "./protocol/encode";
import type { Side } from "./protocol/messages";

import { Header } from "./components/Header";
import { Navbar } from "./components/Navbar";
import { TradingPage } from "./pages/TradingPage";
import { PriceChartPage } from "./pages/PriceChartPage";

import "./styles/terminal.css";

export default function App() {
    const { state, send } = useOrderBook();
    const { openCents } = useIgnitionPrice();

    const handleSubmit = (side: Side, priceCents: number, qty: number): void => {
        send(newOrderFrame(nextClOrdId(), side, priceCents, qty));
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
                    sessionOpenCents={state.sessionOpenCents}
                    lastFrameNanos={state.lastFrameNanos}
                    connection={state.connection}
                    openCents={openCents}
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
                <Route path="/chart" element={<PriceChartPage state={state} />} />
            </Routes>
        </div>
    );
}
