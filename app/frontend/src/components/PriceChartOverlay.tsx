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
 * second header: the window is dragged by that band (pointer capture, clamped to the
 * viewport), except from its interactive controls (gear, close, popups). The × on
 * the band closes it (the §10 exit button), and Escape also closes. Position is held
 * in state and persists across open/close within a page mount.
 *
 * Presentational: it forwards the reducer slices straight to PriceChart and owns only
 * the open/close affordances and the drag position. The chart's "View in trading"
 * dropdown is not passed here (onViewInTrading omitted), so the floated chart shows a
 * close button instead of the now-redundant jump control.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import type { PointerEvent as ReactPointerEvent } from "react";

import { PriceChart } from "./PriceChart";
import type { TapeEntry } from "../state/reducer";

export interface PriceChartOverlayProps {
    readonly open: boolean;
    readonly onClose: () => void;
    readonly tape: readonly TapeEntry[];
    readonly sessionOpenPx: number;
    readonly openPx?: number;
}

interface Pos {
    readonly x: number;
    readonly y: number;
}

// Nominal window size; the element is clamped to the viewport in CSS (max-width),
// and the initial position is centred against these.
const WIN_W = 760;
const WIN_H = 380;

function initialPos(): Pos {
    if (typeof window === "undefined") {
        return { x: 96, y: 96 };
    }
    const x = Math.max(16, Math.round((window.innerWidth - WIN_W) / 2));
    const y = Math.max(16, Math.round((window.innerHeight - WIN_H) / 2));
    return { x, y };
}

export function PriceChartOverlay({
    open,
    onClose,
    tape,
    sessionOpenPx,
    openPx,
}: PriceChartOverlayProps) {
    const [pos, setPos] = useState<Pos>(initialPos);
    // Pointer offset from the window's top-left at drag start; null when not dragging.
    const grab = useRef<{ dx: number; dy: number } | null>(null);

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

    const onPointerDown = useCallback(
        (e: ReactPointerEvent<HTMLDivElement>): void => {
            const target = e.target as HTMLElement;
            // Only the chart's --head band drags the window, and never from an
            // interactive control or an open popup inside it.
            if (target.closest(".price-chart__band") === null) {
                return;
            }
            if (
                target.closest(
                    "button, .settings-pop, .settings-pop__backdrop",
                ) !== null
            ) {
                return;
            }
            grab.current = { dx: e.clientX - pos.x, dy: e.clientY - pos.y };
            e.currentTarget.setPointerCapture(e.pointerId);
        },
        [pos.x, pos.y],
    );

    const onPointerMove = useCallback((e: ReactPointerEvent<HTMLDivElement>): void => {
        const g = grab.current;
        if (g === null) {
            return;
        }
        const maxX = Math.max(0, window.innerWidth - 80);
        const maxY = Math.max(0, window.innerHeight - 40);
        const x = Math.min(Math.max(0, e.clientX - g.dx), maxX);
        const y = Math.min(Math.max(0, e.clientY - g.dy), maxY);
        setPos({ x, y });
    }, []);

    const endDrag = useCallback((e: ReactPointerEvent<HTMLDivElement>): void => {
        if (grab.current !== null) {
            grab.current = null;
            if (e.currentTarget.hasPointerCapture(e.pointerId)) {
                e.currentTarget.releasePointerCapture(e.pointerId);
            }
        }
    }, []);

    if (!open) {
        return null;
    }

    return (
        <div
            className="price-overlay"
            role="dialog"
            aria-label="Price chart (floating)"
            data-testid="price-overlay"
            style={{ left: pos.x, top: pos.y }}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
        >
            <PriceChart
                tape={tape}
                sessionOpenPx={sessionOpenPx}
                openPx={openPx}
                onClose={onClose}
            />
        </div>
    );
}
