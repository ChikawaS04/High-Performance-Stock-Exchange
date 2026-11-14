/**
 * Floating price-chart window (2026-10).
 *
 * The price-chart page's "View in trading" dropdown navigates to the trading
 * terminal and floats the price chart here as a free-floating window, modelled on
 * the maximized depth chart (DepthChartOverlay). It is a window, not a modal: no
 * backdrop scrim, so the terminal stays live underneath.
 *
 * It hosts the SAME <PriceChart> component, so the floated chart keeps its own gear
 * (background / line colour / fill), crosshair and last-price tag. The chart's own
 * --head band doubles as the window title bar and the drag grip, so there is no
 * second header: the window is dragged by that band, except from its interactive
 * controls (gear, close, popups). It is a FloatingWindow (DESIGN_SYSTEM.md §14):
 * resized from any edge or corner, its size and position persisted
 * (hpse.v1.priceWindow.geometry) and clamped to the viewport, and the chart lays out
 * at the window's size (PriceChart `fit`). The × on the band closes it (the §10 exit
 * button), and Escape also closes.
 *
 * P17-11: the window runs the same chart on its own chart settings (the window
 * surface: chart type, bar size, indicators, hpse.v1.priceWindow.*) and the window
 * pane cap of one lower pane, while the display colours stay shared with the page.
 *
 * Presentational: it forwards the reducer slices straight to PriceChart and owns only
 * the open/close affordances; FloatingWindow owns the geometry. The chart's "View in trading"
 * dropdown is not passed here (onViewInTrading omitted), so the floated chart shows a
 * close button instead of the now-redundant jump control.
 */

import { useEffect } from "react";

import { PriceChart } from "./PriceChart";
import { FloatingWindow } from "./FloatingWindow";
import { SETTING_KEYS } from "../settings/persist";
import type { BarSet } from "../bars";
import type { OwnFill, TapeEntry } from "../state/reducer";
import type { WorkingOrder } from "../ownExecutions";
import type { QuotePoint, QuoteSet } from "../quotes";

export interface PriceChartOverlayProps {
    readonly open: boolean;
    readonly onClose: () => void;
    readonly tape: readonly TapeEntry[];
    /** The reducer bars slice (P17-11), for the window's candle chart. */
    readonly bars: BarSet;
    /** Own fills for the chart's markers (P18-2), from buildOwnExecutions. */
    readonly fills?: readonly OwnFill[];
    /** Own resting orders and the lit tops, for the order lines (P18-3). */
    readonly working?: readonly WorkingOrder[];
    readonly bestBid?: number;
    readonly bestAsk?: number;
    /** Drag to reprice (P18-4) and the orders with one in flight (P18-5). */
    readonly onReprice?: (origClOrdId: number, newPx: number) => void;
    readonly repricing?: readonly number[];
    /** Best-quote history for the bid/ask lines (P18-7). */
    readonly quotes?: readonly QuotePoint[];
    readonly quoteBars?: QuoteSet;
    readonly sessionOpenPx: number;
    readonly openPx?: number;
}

// Nominal window size before any resize (the pre-resize 760px width and its 12/5
// chart plus the band), capped to the viewport and centred.
const NOMINAL = { w: 760, h: 350 } as const;

export function PriceChartOverlay({
    open,
    onClose,
    tape,
    bars,
    fills,
    working,
    bestBid,
    bestAsk,
    onReprice,
    repricing,
    quotes,
    quoteBars,
    sessionOpenPx,
    openPx,
}: PriceChartOverlayProps) {
    // Escape closes, matching the depth overlay and the FIX inspector.
    useEffect(() => {
        if (!open) {
            return;
        }
        const onKeyDown = (e: KeyboardEvent): void => {
            if (e.key === "Escape") {
                onClose();
            }
        };
        document.addEventListener("keydown", onKeyDown);
        return () => document.removeEventListener("keydown", onKeyDown);
    }, [open, onClose]);

    if (!open) {
        return null;
    }

    return (
        <FloatingWindow
            className="price-overlay"
            testId="price-overlay"
            ariaLabel="Price chart (floating)"
            dragSelector=".price-chart__band"
            storageKey={SETTING_KEYS.priceWindowGeometry}
            nominal={NOMINAL}
        >
            <PriceChart
                tape={tape}
                bars={bars}
                surface="window"
                fills={fills}
                working={working}
                bestBid={bestBid}
                bestAsk={bestAsk}
                onReprice={onReprice}
                repricing={repricing}
                quotes={quotes}
                quoteBars={quoteBars}
                sessionOpenPx={sessionOpenPx}
                openPx={openPx}
                onClose={onClose}
                fit
            />
        </FloatingWindow>
    );
}
