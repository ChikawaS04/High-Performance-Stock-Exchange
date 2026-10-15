/**
 * Maximized depth-chart window (2026-10).
 *
 * The inline Depth Chart panel runs the "compact" DepthCurve; its header carries a
 * maximize glyph that opens this floating window, which runs the SAME component in
 * its "full" variant at a large size, where the axes, walls and chips have room to
 * read. It is a free-floating window, not a modal: there is no backdrop scrim, so
 * the rest of the terminal stays live underneath while the big chart is open.
 *
 * Draggable by its thin header (pointer capture on the grip, clamped to the
 * viewport); the × on the right closes it, styled exactly as the §10 popup exit
 * button (header-colour fill so the control blends, the lone mark a large × in
 * --exit-x). Escape also closes. Position is held in component state and persists
 * across open/close within a page mount (the component stays mounted and renders
 * null while closed), so re-opening returns the window where the user left it.
 *
 * Settings gear (THIS window only, not the inline panel): the standard §11.1 gear
 * opens the standard §11.4 settings popup with a Display category holding a
 * Background two-state toggle (§11.3). "White" flips the chart canvas to a light
 * theme by re-scoping the chart's colour tokens on the body (see terminal.css
 * .depth-overlay__body--light); the chart's own text/line/axis colours follow,
 * since DepthCurve draws everything through those tokens. The window chrome (frame,
 * header, gear, close) stays charcoal regardless — only the chart canvas flips.
 *
 * Presentational: it forwards book + onPriceSelect straight to DepthCurve, so a
 * click on a level in the big chart populates the ticket exactly as the inline one
 * does. It owns only the open/close affordances, the drag position, and the
 * chart-background choice.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { DepthCurve } from "./DepthCurve";
import type { BookState } from "../state/reducer";

export interface DepthChartOverlayProps {
    readonly open: boolean;
    readonly onClose: () => void;
    readonly book: BookState;
    readonly onPriceSelect?: (pricePx: number) => void;
}

interface Pos {
    readonly x: number;
    readonly y: number;
}

type ChartBg = "dark" | "light";

// Nominal window size; the actual element is clamped to the viewport in CSS
// (max-width/height), and the initial position is centred against these.
const WIN_W = 1040;
const WIN_H = 560;

function initialPos(): Pos {
    if (typeof window === "undefined") {
        return { x: 80, y: 80 };
    }
    const x = Math.max(16, Math.round((window.innerWidth - WIN_W) / 2));
    const y = Math.max(16, Math.round((window.innerHeight - WIN_H) / 2));
    return { x, y };
}

export function DepthChartOverlay({ open, onClose, book, onPriceSelect }: DepthChartOverlayProps) {
    const [pos, setPos] = useState<Pos>(initialPos);
    const [settingsOpen, setSettingsOpen] = useState(false);
    const [displayOpen, setDisplayOpen] = useState(true);
    const [bg, setBg] = useState<ChartBg>("dark");
    // Pointer offset from the window's top-left at drag start; null when not dragging.
    const grab = useRef<{ dx: number; dy: number } | null>(null);

    // Escape closes, matching the FIX inspector.
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
        (e: React.PointerEvent<HTMLDivElement>): void => {
            // Don't start a drag from an interactive control (gear, close) or the
            // settings popup; only a bare grip of the header bar drags the window.
            if (
                (e.target as HTMLElement).closest(
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

    const onPointerMove = useCallback((e: React.PointerEvent<HTMLDivElement>): void => {
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

    const endDrag = useCallback((e: React.PointerEvent<HTMLDivElement>): void => {
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
            className="depth-overlay"
            role="dialog"
            aria-label="Depth chart (maximized)"
            data-testid="depth-overlay"
            style={{ left: pos.x, top: pos.y }}
        >
            <div
                className="depth-overlay__head"
                data-testid="depth-overlay-grip"
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={endDrag}
                onPointerCancel={endDrag}
            >
                <span className="depth-overlay__title">Depth Chart</span>

                <button
                    type="button"
                    className="term-gear"
                    aria-label="Chart settings"
                    aria-haspopup="dialog"
                    aria-expanded={settingsOpen}
                    data-testid="depth-overlay-gear"
                    onClick={() => setSettingsOpen((o) => !o)}
                >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path
                            fill="currentColor"
                            d="M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.82,11.69,4.82,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z"
                        />
                    </svg>
                </button>

                <button
                    type="button"
                    className="depth-overlay__close"
                    data-testid="depth-overlay-close"
                    onClick={onClose}
                    aria-label="Close maximized depth chart"
                >
                    ×
                </button>

                {settingsOpen && (
                    <>
                        <div
                            className="settings-pop__backdrop"
                            onClick={() => setSettingsOpen(false)}
                            data-testid="depth-overlay-settings-backdrop"
                        />
                        <div
                            className="settings-pop"
                            role="dialog"
                            aria-label="Chart settings"
                            data-testid="depth-overlay-settings"
                        >
                            <div className="settings-pop__head">
                                <span className="settings-pop__title">Settings</span>
                                <button
                                    type="button"
                                    className="settings-pop__x"
                                    aria-label="Close"
                                    onClick={() => setSettingsOpen(false)}
                                    data-testid="depth-overlay-settings-close"
                                >
                                    {"×"}
                                </button>
                            </div>

                            <div className="settings-pop__body">
                                <button
                                    type="button"
                                    className="settings-pop__cat"
                                    aria-expanded={displayOpen}
                                    aria-controls="depth-overlay-settings-display"
                                    onClick={() => setDisplayOpen((o) => !o)}
                                    data-testid="depth-overlay-settings-display-toggle"
                                >
                                    <span>Display</span>
                                    <span className="settings-pop__caret" aria-hidden="true">
                                        {"▸"}
                                    </span>
                                </button>

                                {displayOpen && (
                                    <div
                                        className="settings-pop__panel"
                                        id="depth-overlay-settings-display"
                                    >
                                        <div className="settings-pop__row">
                                            <span className="settings-pop__name">Background</span>
                                            <span
                                                className="settings-toggle"
                                                role="group"
                                                aria-label="Chart background"
                                            >
                                                <button
                                                    type="button"
                                                    className="settings-toggle__opt"
                                                    aria-pressed={bg === "dark"}
                                                    onClick={() => setBg("dark")}
                                                    data-testid="depth-overlay-bg-dark"
                                                >
                                                    Black
                                                </button>
                                                <button
                                                    type="button"
                                                    className="settings-toggle__opt"
                                                    aria-pressed={bg === "light"}
                                                    onClick={() => setBg("light")}
                                                    data-testid="depth-overlay-bg-light"
                                                >
                                                    White
                                                </button>
                                            </span>
                                        </div>
                                    </div>
                                )}
                            </div>
                        </div>
                    </>
                )}
            </div>

            <div
                className={`depth-overlay__body${bg === "light" ? " depth-overlay__body--light" : ""}`}
            >
                <DepthCurve book={book} onPriceSelect={onPriceSelect} variant="full" />
            </div>
        </div>
    );
}
